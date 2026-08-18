package com.mandopop.briefing

/**
 * One on-device model behind one contract, so auditioning composers is a file push, not a
 * refactor. The engine picks an implementation from what sits in the models dir: a `.gguf`
 * loads through llama.cpp ([LlamaComposer]), a `.litertlm` through LiteRT-LM ([GemmaComposer]);
 * `.gguf` wins when both are present, because the smaller/faster candidate is the one under
 * audition. The verifier downstream is identical either way — the composer is allowed to be
 * bad, the surface is not.
 */
sealed interface ComposerStatus {
    /** No model file on the device; carries the path to push one to. */
    data class MissingModel(val expectedPath: String) : ComposerStatus

    /** File present; the runtime loads on the first generation. */
    data object NotLoaded : ComposerStatus

    data class Ready(val backend: String, val model: String) : ComposerStatus

    data class Failed(val message: String) : ComposerStatus
}

interface SentenceComposer : AutoCloseable {
    fun status(): ComposerStatus

    /** One raw completion. Callers extract, verify, and decide what it was worth. */
    suspend fun generate(prompt: String): String
}
