package com.mandopop.settings

import android.content.Context
import kotlin.math.roundToInt

data class SettingsSnapshot(
    val showAudio: Boolean,
    val chineseFontSizeSp: Int,
    val playfulNoResult: Boolean,
)

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun snapshot(): SettingsSnapshot {
        return SettingsSnapshot(
            showAudio = prefs.getBoolean(KEY_SHOW_AUDIO, true),
            chineseFontSizeSp = prefs.getInt(KEY_CHINESE_FONT_SIZE_SP, DEFAULT_FONT_SIZE_SP),
            playfulNoResult = prefs.getBoolean(KEY_PLAYFUL_NO_RESULT, true),
        )
    }

    fun setShowAudio(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SHOW_AUDIO, enabled).apply()
    }

    fun setChineseFontSizeSp(size: Float) {
        val rounded = (size / 2f).roundToInt() * 2
        prefs.edit()
            .putInt(KEY_CHINESE_FONT_SIZE_SP, rounded.coerceIn(MIN_FONT_SIZE_SP, MAX_FONT_SIZE_SP))
            .apply()
    }

    fun setPlayfulNoResult(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_PLAYFUL_NO_RESULT, enabled).apply()
    }

    companion object {
        const val MIN_FONT_SIZE_SP = 16
        const val MAX_FONT_SIZE_SP = 36
        const val DEFAULT_FONT_SIZE_SP = 24

        private const val PREFS_NAME = "mandopop_settings"
        private const val KEY_SHOW_AUDIO = "show_audio"
        private const val KEY_CHINESE_FONT_SIZE_SP = "chinese_font_size_sp"
        private const val KEY_PLAYFUL_NO_RESULT = "playful_no_result"
    }
}
