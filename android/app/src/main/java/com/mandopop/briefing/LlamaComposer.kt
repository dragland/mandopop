package com.mandopop.briefing

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * GGUF models in-process through llama.cpp (vendored at `android/third_party/llama.cpp`,
 * pinned; bridge in `src/main/cpp/llama_bridge.cpp`). This is the path to the Qwen family —
 * the strongest Chinese per parameter — which ships in no Google-runtime format. CPU-only,
 * KleidiAI kernels; a 2B Q4 clears the one-sentence latency budget without touching the
 * GPU-driver lottery. Same rules as its sibling: loads lazily on first generation, stays
 * resident, never touches the network.
 */
class LlamaComposer(private val appContext: Context) : SentenceComposer {

    /**
     * A plain monitor, not a coroutine mutex, because [close] must be able to take it from
     * non-suspend context: an adb model-swap makes the engine close this composer, and an
     * unload racing an in-flight generate is a native use-after-free — every native call sits
     * under this lock. Blocking a Default-dispatcher thread for a generation's duration is what
     * generation costs regardless.
     */
    private val runtimeLock = Any()

    @Volatile
    private var loaded = false
    private var loadedModel: String? = null

    @Volatile
    private var lastError: String? = null

    override fun status(): ComposerStatus {
        if (!nativeLibraryLoaded) return ComposerStatus.Failed("native library failed to load")
        if (loaded) return ComposerStatus.Ready("llama.cpp/CPU", loadedModel ?: "?")
        lastError?.let { return ComposerStatus.Failed(it) }
        if (modelFile() == null) {
            return ComposerStatus.MissingModel(File(modelDir(), "<model>.gguf").absolutePath)
        }
        return ComposerStatus.NotLoaded
    }

    override suspend fun generate(prompt: String): String = withContext(Dispatchers.Default) {
        // The whole call sits under the lock: the native side is a single model+context with
        // no locking of its own.
        synchronized(runtimeLock) {
            ensureLoaded()
            val bytes = nativeGenerate(prompt, MAX_OUTPUT_TOKENS, TEMPERATURE, TOP_K)
                ?: throw IllegalStateException("llama generation failed")
            // Bytes, not a JNI string: the token budget can slice a hanzi mid-sequence, and
            // Kotlin's decoder degrades that to a replacement char for the verifier to refuse
            // instead of ART aborting the process on invalid Modified UTF-8.
            String(bytes, Charsets.UTF_8)
        }
    }

    override fun close() {
        synchronized(runtimeLock) {
            if (loaded) {
                nativeUnload()
                loaded = false
            }
        }
    }

    private fun ensureLoaded() {
        if (loaded) return
        if (!nativeLibraryLoaded) throw IllegalStateException("native library failed to load")
        val file = modelFile()
            ?: throw IllegalStateException("no model — adb push a .gguf to ${modelDir().absolutePath}/")
        if (!nativeLoad(file.absolutePath, THREADS, CONTEXT_TOKENS)) {
            lastError = "llama.cpp failed to load ${file.name}"
            throw IllegalStateException(lastError)
        }
        loaded = true
        loadedModel = file.name.removeSuffix(".gguf")
        lastError = null
        Log.i(TAG, "engine loaded: ${file.name} on llama.cpp/CPU")
    }

    // mkdirs so the dir is app-owned — a shell-created one is untraversable by the app's uid.
    private fun modelDir(): File =
        File(appContext.getExternalFilesDir(null), "models").apply { mkdirs() }

    private fun modelFile(): File? = modelDir()
        .listFiles { file -> file.isFile && file.name.endsWith(".gguf") }
        ?.minByOrNull { it.name }

    companion object {
        private const val TAG = "MandopopBriefing"

        /** Big/mid cores only — spinning the little cores up slows the matmuls down. */
        private const val THREADS = 4

        /** Prompt is ~300 tokens, output ≤64; headroom, not a chat history. */
        private const val CONTEXT_TOKENS = 1024

        private const val MAX_OUTPUT_TOKENS = 64
        private const val TEMPERATURE = 0.2f
        private const val TOP_K = 16

        private val nativeLibraryLoaded: Boolean = try {
            System.loadLibrary("mandopop_llama")
            true
        } catch (error: UnsatisfiedLinkError) {
            Log.e(TAG, "mandopop_llama library load failed", error)
            false
        }

        @JvmStatic
        private external fun nativeLoad(path: String, nThreads: Int, nCtx: Int): Boolean

        @JvmStatic
        private external fun nativeGenerate(
            prompt: String,
            maxTokens: Int,
            temperature: Float,
            topK: Int,
        ): ByteArray?

        @JvmStatic
        private external fun nativeUnload()
    }
}
