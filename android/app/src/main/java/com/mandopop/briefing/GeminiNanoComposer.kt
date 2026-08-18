package com.mandopop.briefing

import android.util.Log
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.common.GenAiException
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest
import kotlinx.coroutines.flow.Flow

/**
 * Gemini Nano through the ML Kit GenAI Prompt API — audition candidate #1 (spec.md §6).
 *
 * Runs in AICore under Private Compute: no inference-time network by construction, zero APK
 * weight, one-time model download. The deployed nano's Chinese tuning is unpublished, which is
 * exactly why this exists behind the verifier and beside the template fallback — the composer is
 * allowed to be bad, the surface is not. If empirical pass rates are poor, the next candidates
 * (Gemma 3n via MediaPipe, then Qwen3+llama.cpp) swap in behind the same interface.
 */
class GeminiNanoComposer {

    enum class Status { AVAILABLE, DOWNLOADABLE, DOWNLOADING, UNAVAILABLE }

    private val model by lazy { Generation.getClient() }

    suspend fun status(): Status = try {
        when (model.checkStatus()) {
            FeatureStatus.AVAILABLE -> Status.AVAILABLE
            FeatureStatus.DOWNLOADABLE -> Status.DOWNLOADABLE
            FeatureStatus.DOWNLOADING -> Status.DOWNLOADING
            else -> Status.UNAVAILABLE
        }
    } catch (error: Exception) {
        // No AICore, unsupported device, unlocked bootloader — all just mean "no model here".
        Log.w(TAG, "Gemini Nano status check failed", error)
        Status.UNAVAILABLE
    }

    fun download(): Flow<DownloadStatus> = model.download()

    /** First inference without this can hang for the better part of a minute (documented). */
    suspend fun warmup() {
        try {
            model.warmup()
        } catch (error: Exception) {
            Log.w(TAG, "Gemini Nano warmup failed", error)
        }
    }

    /** One raw completion. Callers extract, verify, and decide what it was worth. */
    suspend fun generate(prompt: String): String {
        val request = generateContentRequest(TextPart(prompt)) {
            // Low temperature: the prompt already contains everything creative; sampling variety
            // only spends verifier retries.
            temperature = 0.2f
            topK = 16
            maxOutputTokens = 64
        }
        val response = model.generateContent(request)
        return response.candidates.firstOrNull()?.text.orEmpty()
    }

    companion object {
        private const val TAG = "MandopopBriefing"

        /**
         * Names the AICore error code, because two of them are load-bearing for the audition:
         * BACKGROUND_USE_BLOCKED means Nano only runs while *this app* is the top foreground app
         * — which the shade-pull trigger never is — and BUSY is a rate limit, not a model
         * failure. A generic "model error" for either would mislead the composer decision.
         */
        fun describe(error: Exception): String {
            val code = (error as? GenAiException)?.errorCode ?: return error.message
                ?: error::class.java.simpleName
            val name = when (code) {
                GenAiException.ErrorCode.BACKGROUND_USE_BLOCKED ->
                    "BACKGROUND_USE_BLOCKED — Nano only runs while Mandopop is foreground"
                GenAiException.ErrorCode.BUSY -> "BUSY — AICore rate limit, retry later"
                GenAiException.ErrorCode.PER_APP_BATTERY_USE_QUOTA_EXCEEDED ->
                    "PER_APP_BATTERY_USE_QUOTA_EXCEEDED"
                GenAiException.ErrorCode.NOT_AVAILABLE -> "NOT_AVAILABLE"
                GenAiException.ErrorCode.NEEDS_SYSTEM_UPDATE -> "NEEDS_SYSTEM_UPDATE"
                GenAiException.ErrorCode.NOT_ENOUGH_DISK_SPACE -> "NOT_ENOUGH_DISK_SPACE"
                else -> "GenAiException code $code"
            }
            return "$name${error.message?.let { ": $it" }.orEmpty()}"
        }
    }
}
