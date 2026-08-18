// JNI bridge between LlamaComposer and llama.cpp, pinned to the b10472 API.
//
// Deliberately minimal: one model, one context, synchronous single-turn generation. The Kotlin
// side serializes access behind a mutex, so no locking here. Everything the briefing needs is
// "prompt in, short completion out" — chat state, streaming, and tools stay out.

#include <jni.h>
#include <android/log.h>

#include <string>
#include <vector>

#include "llama.h"

#define TAG "MandopopLlama"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static llama_model *  g_model = nullptr;
static llama_context *g_ctx   = nullptr;

static void unload() {
    if (g_ctx)   { llama_free(g_ctx);        g_ctx   = nullptr; }
    if (g_model) { llama_model_free(g_model); g_model = nullptr; }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_mandopop_briefing_LlamaComposer_nativeLoad(
        JNIEnv *env, jclass, jstring jpath, jint n_threads, jint n_ctx) {
    unload();
    llama_backend_init();

    const char *path = env->GetStringUTFChars(jpath, nullptr);
    llama_model_params model_params = llama_model_default_params();
    model_params.n_gpu_layers = 0;
    g_model = llama_model_load_from_file(path, model_params);
    env->ReleaseStringUTFChars(jpath, path);
    if (!g_model) {
        LOGE("model load failed");
        return JNI_FALSE;
    }

    llama_context_params ctx_params = llama_context_default_params();
    ctx_params.n_ctx           = (uint32_t) n_ctx;
    ctx_params.n_threads       = n_threads;
    ctx_params.n_threads_batch = n_threads;
    g_ctx = llama_init_from_model(g_model, ctx_params);
    if (!g_ctx) {
        LOGE("context init failed");
        unload();
        return JNI_FALSE;
    }
    LOGI("model loaded, n_ctx=%d threads=%d", n_ctx, n_threads);
    return JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_mandopop_briefing_LlamaComposer_nativeUnload(JNIEnv *, jclass) {
    unload();
}

// Wraps the raw prompt in the model's own chat template (Qwen wants its im_start framing;
// a bare prompt on a chat-tuned model degrades output badly). Falls back to the raw prompt
// when the model carries no template.
static std::string apply_chat_template(const std::string &prompt) {
    const char *tmpl = llama_model_chat_template(g_model, nullptr);
    if (!tmpl) return prompt;

    llama_chat_message msg = { "user", prompt.c_str() };
    std::vector<char> buf(prompt.size() * 2 + 1024);
    int32_t written = llama_chat_apply_template(tmpl, &msg, 1, true, buf.data(), (int32_t) buf.size());
    if (written < 0) return prompt;
    if ((size_t) written > buf.size()) {
        buf.resize(written);
        written = llama_chat_apply_template(tmpl, &msg, 1, true, buf.data(), (int32_t) buf.size());
        if (written < 0) return prompt;
    }
    std::string formatted(buf.data(), written);

    // Reasoning-tuned templates open the assistant turn thinking, and a short token budget is
    // entirely eaten by it. A prefilled closed think block steps straight to the answer; gated
    // on the template mentioning think so plain chat models are untouched.
    if (std::string(tmpl).find("think") != std::string::npos) {
        formatted += "<think>\n\n</think>\n\n";
    }
    return formatted;
}

// Returns raw UTF-8 bytes, decoded on the Kotlin side. NewStringUTF would abort the process on
// invalid Modified UTF-8 — and hitting the token budget mid-character leaves exactly that, since
// byte-level BPE happily splits a three-byte hanzi across tokens.
extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_mandopop_briefing_LlamaComposer_nativeGenerate(
        JNIEnv *env, jclass, jstring jprompt, jint max_tokens, jfloat temperature, jint top_k,
        jint seed) {
    if (!g_model || !g_ctx) return nullptr;

    const char *cprompt = env->GetStringUTFChars(jprompt, nullptr);
    const std::string formatted = apply_chat_template(cprompt);
    env->ReleaseStringUTFChars(jprompt, cprompt);

    const llama_vocab *vocab = llama_model_get_vocab(g_model);

    std::vector<llama_token> tokens(formatted.size() + 16);
    int n_tokens = llama_tokenize(vocab, formatted.c_str(), (int32_t) formatted.size(),
                                  tokens.data(), (int32_t) tokens.size(), true, true);
    if (n_tokens < 0) {
        LOGE("tokenize failed (%d)", n_tokens);
        return nullptr;
    }
    tokens.resize(n_tokens);
    if ((uint32_t) (n_tokens + max_tokens) > llama_n_ctx(g_ctx)) {
        LOGE("prompt too long: %d tokens", n_tokens);
        return nullptr;
    }

    // Each generation is independent — clear the previous one's cache.
    llama_memory_clear(llama_get_memory(g_ctx), true);

    llama_batch batch = llama_batch_get_one(tokens.data(), n_tokens);
    if (llama_decode(g_ctx, batch) != 0) {
        LOGE("prompt decode failed");
        return nullptr;
    }

    llama_sampler *smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(smpl, llama_sampler_init_top_k(top_k));
    llama_sampler_chain_add(smpl, llama_sampler_init_temp(temperature));
    // Caller-varied by local day: at temp 0.2 a fixed seed makes identical inputs produce the
    // identical sentence forever.
    llama_sampler_chain_add(smpl, llama_sampler_init_dist((uint32_t) seed));

    std::string out;
    std::vector<char> piece(256);
    for (int i = 0; i < max_tokens; i++) {
        llama_token id = llama_sampler_sample(smpl, g_ctx, -1);
        if (llama_vocab_is_eog(vocab, id)) break;
        int n = llama_token_to_piece(vocab, id, piece.data(), (int32_t) piece.size(), 0, false);
        if (n < 0) {
            // Buffer too small: -n is the required size. Rare, but silent truncation would
            // corrupt the byte stream.
            piece.resize(-n);
            n = llama_token_to_piece(vocab, id, piece.data(), (int32_t) piece.size(), 0, false);
        }
        if (n > 0) out.append(piece.data(), n);
        llama_batch next = llama_batch_get_one(&id, 1);
        if (llama_decode(g_ctx, next) != 0) {
            LOGE("decode failed mid-generation");
            break;
        }
    }
    llama_sampler_free(smpl);

    jbyteArray bytes = env->NewByteArray((jsize) out.size());
    if (bytes) {
        env->SetByteArrayRegion(bytes, 0, (jsize) out.size(),
                                reinterpret_cast<const jbyte *>(out.data()));
    }
    return bytes;
}
