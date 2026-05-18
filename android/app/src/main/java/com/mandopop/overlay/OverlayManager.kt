package com.mandopop.overlay

import android.accessibilityservice.AccessibilityService
import android.graphics.PixelFormat
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import com.mandopop.dictionary.CedictEntry
import com.mandopop.settings.SettingsSnapshot
import com.mandopop.tts.ChineseTtsManager
import kotlin.math.roundToInt

class OverlayManager(
    private val service: AccessibilityService,
    private val ttsManager: ChineseTtsManager,
) {
    private val windowManager = service.getSystemService(WindowManager::class.java)
    private var overlayView: View? = null
    private var composeOwner: OverlayComposeOwner? = null

    fun show(
        entries: List<CedictEntry>,
        settings: SettingsSnapshot,
        isNoResult: Boolean,
    ) {
        dismiss()

        val owner = OverlayComposeOwner().also { it.start() }
        val view = ComposeView(service).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setOnTouchListener { touchedView, event ->
                if (event.action == MotionEvent.ACTION_OUTSIDE) {
                    touchedView.performClick()
                    dismiss()
                    true
                } else {
                    false
                }
            }
        }

        view.setTag(androidx.lifecycle.runtime.R.id.view_tree_lifecycle_owner, owner)
        view.setTag(androidx.savedstate.R.id.view_tree_saved_state_registry_owner, owner)
        view.setTag(androidx.lifecycle.viewmodel.R.id.view_tree_view_model_store_owner, owner)

        view.setContent {
            OverlayCard(
                entries = entries,
                showAudio = settings.showAudio,
                chineseFontSizeSp = settings.chineseFontSizeSp,
                isNoResult = isNoResult,
                onSpeak = ttsManager::speak,
                onClose = ::dismiss,
            )
        }

        runCatching {
            windowManager.addView(view, layoutParams())
            overlayView = view
            composeOwner = owner
        }.onFailure { error ->
            view.disposeComposition()
            owner.destroy()
            Log.e(TAG, "Failed to show overlay", error)
        }
    }

    fun dismiss() {
        val view = overlayView
        overlayView = null
        composeOwner?.destroy()
        composeOwner = null

        if (view == null) return
        runCatching {
            windowManager.removeView(view)
        }.onFailure { error ->
            Log.w(TAG, "Failed to remove overlay", error)
        }
    }

    private fun layoutParams(): WindowManager.LayoutParams {
        val density = service.resources.displayMetrics.density
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = (24f * density).roundToInt()
        }
    }

    companion object {
        private const val TAG = "OverlayManager"
    }
}
