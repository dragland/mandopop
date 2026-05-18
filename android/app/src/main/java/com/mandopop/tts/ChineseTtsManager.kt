package com.mandopop.tts

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import java.util.Locale

class ChineseTtsManager(context: Context) : TextToSpeech.OnInitListener {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val callbackLock = Any()
    private val completionCallbacks = mutableMapOf<String, () -> Unit>()
    private var tts: TextToSpeech? = null
    private var ready = false
    private var unavailable = false
    private var pendingSpeech: PendingSpeech? = null

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            Log.w(TAG, "TextToSpeech initialization failed: $status")
            unavailable = true
            tts?.shutdown()
            tts = null
            completePendingSpeech()
            return
        }

        val engine = tts ?: run {
            completePendingSpeech()
            return
        }
        engine.setOnUtteranceProgressListener(progressListener)
        chooseVoice(engine)
        engine.setSpeechRate(SPEECH_RATE)
        ready = true

        pendingSpeech?.let {
            pendingSpeech = null
            speak(it.text, it.onComplete)
        }
    }

    fun speak(text: String, onComplete: () -> Unit = {}) {
        if (text.isBlank()) {
            onComplete()
            return
        }

        if (unavailable) {
            onComplete()
            return
        }

        if (!ready) {
            replacePendingSpeech(PendingSpeech(text, onComplete))
            ensureEngine()
            return
        }

        val engine = tts ?: run {
            onComplete()
            return
        }

        engine.stop()
        val utteranceId = "mandopop-${System.nanoTime()}"
        synchronized(callbackLock) {
            completionCallbacks[utteranceId] = onComplete
        }

        val result = engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
        if (result == TextToSpeech.ERROR) {
            completeUtterance(utteranceId)
        }
    }

    fun shutdown() {
        completePendingSpeech()
        ready = false
        unavailable = true
        tts?.stop()
        tts?.shutdown()
        tts = null
        completeAllUtterances()
    }

    private fun chooseVoice(engine: TextToSpeech) {
        val voice = preferredVoice(engine.voices)
        if (voice != null) {
            engine.voice = voice
            return
        }

        val locale = listOf(Locale.TAIWAN, Locale.CHINA, Locale.CHINESE)
            .firstOrNull { locale ->
                val result = engine.isLanguageAvailable(locale)
                result >= TextToSpeech.LANG_AVAILABLE
            }

        if (locale != null) {
            engine.language = locale
        }
    }

    private fun preferredVoice(voices: Set<Voice>?): Voice? {
        val chineseVoices = voices.orEmpty().filter { it.locale.language == Locale.CHINESE.language }
        val preferredNames = listOf("meijia", "shelley", "sandy", "flo")

        for (name in preferredNames) {
            val match = chineseVoices.firstOrNull {
                it.locale.toLanguageTag().equals("zh-TW", ignoreCase = true) &&
                    it.name.contains(name, ignoreCase = true)
            }
            if (match != null) return match
        }

        return chineseVoices.firstOrNull {
            it.locale.toLanguageTag().equals("zh-TW", ignoreCase = true)
        } ?: chineseVoices.firstOrNull {
            it.locale.toLanguageTag().equals("zh-CN", ignoreCase = true)
        } ?: chineseVoices.firstOrNull()
    }

    private val progressListener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) = Unit

        override fun onDone(utteranceId: String?) {
            completeUtterance(utteranceId)
        }

        @Deprecated("Deprecated in Android framework")
        override fun onError(utteranceId: String?) {
            completeUtterance(utteranceId)
        }

        override fun onError(utteranceId: String?, errorCode: Int) {
            completeUtterance(utteranceId)
        }

        override fun onStop(utteranceId: String?, interrupted: Boolean) {
            completeUtterance(utteranceId)
        }
    }

    private fun replacePendingSpeech(speech: PendingSpeech) {
        val previous = pendingSpeech
        pendingSpeech = speech
        previous?.let { mainHandler.post(it.onComplete) }
    }

    private fun completePendingSpeech() {
        val speech = pendingSpeech
        pendingSpeech = null
        speech?.let { mainHandler.post(it.onComplete) }
    }

    private fun completeUtterance(utteranceId: String?) {
        if (utteranceId == null) return

        val callback = synchronized(callbackLock) {
            completionCallbacks.remove(utteranceId)
        } ?: return

        mainHandler.post(callback)
    }

    private fun completeAllUtterances() {
        val callbacks = synchronized(callbackLock) {
            completionCallbacks.values.toList().also {
                completionCallbacks.clear()
            }
        }

        callbacks.forEach { mainHandler.post(it) }
    }

    private fun ensureEngine() {
        if (tts != null || unavailable) return
        tts = TextToSpeech(appContext, this)
    }

    companion object {
        private const val TAG = "ChineseTtsManager"
        private const val SPEECH_RATE = 0.85f
    }

    private data class PendingSpeech(
        val text: String,
        val onComplete: () -> Unit,
    )
}
