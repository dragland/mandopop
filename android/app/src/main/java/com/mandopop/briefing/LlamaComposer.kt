package com.mandopop.briefing

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * What the model row and the engine need to know about the runtime. MissingModel carries the
 * push path because that is the entire installation UI.
 */
sealed interface ComposerStatus {
    data class MissingModel(val expectedPath: String) : ComposerStatus
    data class NotLoaded(val model: String) : ComposerStatus
    data class Ready(val backend: String, val model: String) : ComposerStatus
    data class Failed(val message: String) : ComposerStatus
}

/**
 * GGUF models in-process through llama.cpp (vendored submodule; bridge in
 * `src/main/cpp/llama_bridge.cpp`). CPU-only with KleidiAI kernels — a 2B Q4 clears the
 * one-sentence latency budget without the GPU-driver lottery. Loads lazily on first
 * generation, stays resident, never touches the network.
 */
class LlamaComposer(private val appContext: Context) {

    /**
     * Plain monitor (close() is non-suspend): every native call sits under it, because an
     * unload racing an in-flight generate is a native use-after-free.
     */
    private val runtimeLock = Any()

    @Volatile
    private var loaded = false
    private var loadedModel: String? = null

    @Volatile
    private var lastError: String? = null

    fun status(): ComposerStatus {
        if (!nativeLibraryLoaded) return ComposerStatus.Failed("native library failed to load")
        if (loaded) return ComposerStatus.Ready("llama.cpp/CPU", loadedModel ?: "?")
        lastError?.let { return ComposerStatus.Failed(it) }
        val file = modelFile()
            ?: return ComposerStatus.MissingModel(File(modelDir(), "<model>.gguf").absolutePath)
        return ComposerStatus.NotLoaded(displayName(file.name))
    }

    suspend fun generate(prompt: String): String = withContext(Dispatchers.Default) {
        // The whole call sits under the lock: the native side is a single model+context with
        // no locking of its own.
        synchronized(runtimeLock) {
            ensureLoaded()
            // Day-keyed seed: deterministic within a day, fresh phrasing across days.
            val seed = java.time.LocalDate.now().toEpochDay().toInt()
            val bytes = nativeGenerate(prompt, MAX_OUTPUT_TOKENS, TEMPERATURE, TOP_K, seed)
                ?: throw IllegalStateException("llama generation failed")
            // Bytes, not a JNI string: a mid-hanzi token cut is invalid Modified UTF-8, which
            // NewStringUTF answers by aborting the process.
            String(bytes, Charsets.UTF_8)
        }
    }

    fun close() {
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
        loadedModel = displayName(file.name)
        lastError = null
        Log.i(TAG, "engine loaded: ${file.name} on llama.cpp/CPU")
    }

    // App-created so the app's uid owns it; a shell-made dir is untraversable.
    private fun modelDir(): File =
        File(appContext.getExternalFilesDir(null), "models").apply { mkdirs() }

    private fun modelFile(): File? = modelDir()
        .listFiles { file -> file.isFile && file.name.endsWith(".gguf") }
        ?.minByOrNull { it.name }

    companion object {
        private const val TAG = "MandopopBriefing"

        /** "Qwen3.5-2B-UD-Q4_K_XL.gguf" -> "Qwen3.5 2B": model identity for the UI, quant noise off. */
        internal fun displayName(fileName: String): String = fileName
            .removeSuffix(".gguf")
            .split('-')
            .takeWhile { !it.matches(Regex("(?i)UD|I?Q\\d.*|F16|BF16|K|XL|XS|S|M|L|GGUF")) }
            .joinToString(" ")
            .ifEmpty { fileName.removeSuffix(".gguf") }

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
            seed: Int,
        ): ByteArray?

        @JvmStatic
        private external fun nativeUnload()
    }
}
