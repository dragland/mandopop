package com.mandopop.briefing

import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.SamplerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The briefing's composer: Gemma 3n running in-process through LiteRT-LM.
 *
 * In-process is the load-bearing property — AICore/Gemini Nano refuses inference unless the
 * calling app is the top foreground app, and the shade-pull trigger runs while SystemUI is
 * foreground, so a bundled runtime is the only architecture that can actually serve this
 * feature. LiteRT-LM is Google's supported path (the MediaPipe LLM Inference API it replaces is
 * in maintenance mode). Inference never touches the network, so no-content-egress holds.
 *
 * The model file is provisioned once at dev time (`adb push` to [expectedModelPath]) rather than
 * bundled — 3 GB in an APK helps nobody. The engine loads lazily on first generation (~10 s,
 * documented) and then stays resident for the process lifetime: the accessibility service
 * process is long-lived, and reloading 3 GB per shade-pull would make every briefing tens of
 * seconds late. If resident memory proves painful on-device, an idle-unload timer is the knob.
 */
class GemmaComposer(private val appContext: Context) {

    sealed interface Status {
        /** No model file on the device; carries the exact path to push to. */
        data class MissingModel(val expectedPath: String) : Status

        /** File present; the engine loads on the first generation (~10 s). */
        data object NotLoaded : Status

        data class Ready(val backend: String) : Status

        data class Failed(val message: String) : Status
    }

    private val mutex = Mutex()
    private var engine: Engine? = null
    private var loadedBackend: String? = null
    private var lastError: String? = null

    fun expectedModelPath(): String = modelFile().absolutePath

    fun status(): Status {
        engine?.let { return Status.Ready(loadedBackend ?: "?") }
        lastError?.let { return Status.Failed(it) }
        if (!modelFile().exists()) return Status.MissingModel(expectedModelPath())
        return Status.NotLoaded
    }

    /** One raw completion. Callers extract, verify, and decide what it was worth. */
    suspend fun generate(prompt: String): String = withContext(Dispatchers.Default) {
        val engine = mutex.withLock { ensureEngine() }
        val config = ConversationConfig(
            samplerConfig = SamplerConfig(topK = 16, topP = 0.9, temperature = 0.2),
            maxOutputToken = MAX_OUTPUT_TOKENS,
        )
        engine.createConversation(config).use { conversation ->
            textOf(conversation.sendMessage(prompt))
        }
    }

    private fun ensureEngine(): Engine {
        engine?.let { return it }
        val file = modelFile()
        if (!file.exists()) {
            throw IllegalStateException("model file missing — adb push it to ${file.absolutePath}")
        }
        // GPU first for prefill speed; some devices/drivers refuse, and a briefing composed on
        // CPU beats no briefing.
        val attempts = listOf<Backend>(Backend.GPU(), Backend.CPU())
        var failure: Exception? = null
        for (backend in attempts) {
            try {
                val candidate = Engine(
                    EngineConfig(
                        modelPath = file.absolutePath,
                        backend = backend,
                        cacheDir = appContext.cacheDir.absolutePath,
                    ),
                )
                candidate.initialize()
                engine = candidate
                loadedBackend = backend.name
                lastError = null
                Log.i(TAG, "Gemma engine loaded on ${backend.name}")
                return candidate
            } catch (error: Exception) {
                Log.w(TAG, "Gemma engine init failed on ${backend.name}", error)
                failure = error
            }
        }
        lastError = failure?.message ?: "engine init failed"
        throw failure ?: IllegalStateException("engine init failed")
    }

    private fun textOf(message: Message): String =
        message.contents.contents
            .filterIsInstance<Content.Text>()
            .joinToString("") { it.text }

    private fun modelFile(): File =
        File(appContext.getExternalFilesDir(null), "models/$MODEL_FILE_NAME")

    private companion object {
        const val TAG = "MandopopBriefing"

        /** google/gemma-3n-E2B-it-litert-lm from Hugging Face (litert-community build). */
        const val MODEL_FILE_NAME = "gemma-3n-e2b.litertlm"

        const val MAX_OUTPUT_TOKENS = 64
    }
}
