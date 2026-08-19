package com.mandopop.work

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.mandopop.briefing.BriefingEngine
import com.mandopop.notification.DueNotifier
import com.mandopop.notification.StatsTail
import com.mandopop.traverse.TraverseSync
import com.mandopop.tts.ChineseTtsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Keeps the ongoing notification correct without waiting for the next periodic sync.
 *
 * Handles two cases:
 *
 *  - **Dismissal.** Android 14 changed `FLAG_ONGOING_EVENT` so users *can* swipe ongoing
 *    notifications away; there is no longer any flag that makes one truly permanent. Re-posting
 *    from the delete intent is the only way to honour "persistent until the queue is empty", and
 *    it stays honest because it stops the moment the count reaches zero. The swipe also doubles
 *    as the app's manual refresh, since it is the only gesture that forces a full pull without
 *    opening the settings screen.
 *  - **App update.** A posted notification stores its icon as a bare resource id and SystemUI
 *    caches the resolved drawable, so a notification surviving an update can render the previous
 *    build's artwork. Re-posting rebinds it.
 */
class NotificationRefreshReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action !in HANDLED) return

        // Speak stands alone: no sync, no repost — just say the Chinese and finish. The TTS
        // callback fires on the main handler after speech ends, which is what releases the
        // broadcast's async window.
        if (action == ACTION_SPEAK) {
            val text = intent.getStringExtra(EXTRA_SPEAK_TEXT)?.takeIf { it.isNotBlank() } ?: return
            val pending = goAsync()
            val tts = ChineseTtsManager(context.applicationContext)
            tts.speak(text) {
                tts.shutdown()
                pending.finish()
            }
            return
        }

        val hanzi = intent.getStringExtra(EXTRA_HANZI)

        val appContext = context.applicationContext
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Swiping the zero-due ambient line means "done for today" — record it and stop.
                // No repost (that would resurrect what was just dismissed) and no forced sync
                // (there is nothing due to recount). A *newly generated* briefing shows again.
                if (action == ACTION_AMBIENT_DISMISSED) {
                    BriefingEngine.ambientDismissed()
                    return@launch
                }

                val sync = TraverseSync(appContext)
                if (!sync.isSignedIn()) {
                    DueNotifier.cancel(appContext)
                    return@launch
                }

                if (action == ACTION_REVEAL && hanzi != null) {
                    val gloss = sync.glossFor(hanzi)
                    if (gloss != null) {
                        DueNotifier.showAnswer(appContext, sync.localDueCount(), hanzi, gloss)
                        return@launch
                    }
                }
                if (action == Intent.ACTION_MY_PACKAGE_REPLACED) {
                    SyncWorker.ensureScheduled(appContext)

                    // A schema upgrade with no written migration drops the local mirror. Refill it
                    // rather than leaving the user on an empty "0 due" until the next periodic run
                    // — but hand it to the worker. That refill now includes a full ~940-document
                    // content drain, and a background broadcast's window is about a minute even
                    // with goAsync(); the worker posts the result when it lands.
                    if (sync.localLiveCount() == 0) {
                        Log.i(TAG, "post-update refill: enqueueing full sync")
                        SyncWorker.syncNow(appContext, force = true)
                        return@launch
                    }
                }
                // Re-post from local counts first, so the notification never visibly disappears
                // while a network round trip is in flight. Stats refresh beforehand — a repost
                // with a stale-or-empty tail reads as the feature being broken.
                runCatching { StatsTail.refresh(appContext) }
                val due = sync.localDueCount()
                Log.i(TAG, "refresh after $action: due=$due")
                DueNotifier.repost(appContext, due, sync.localLiveCount(), sync.localExample())

                // A swipe then doubles as the refresh gesture, saving a trip into the settings
                // screen. Forced on purpose: the events heartbeat only moves when a review is
                // answered, so an unforced sync here would usually decide nothing had changed and
                // re-post the same number the user just swiped away. The worker posts the result.
                if (action == ACTION_DISMISSED) {
                    SyncWorker.syncNow(appContext, force = true)
                }
            } catch (error: Exception) {
                Log.e(TAG, "Notification refresh failed", error)
                DueNotifier.showError(appContext, error.message ?: "Notification refresh failed")
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_DISMISSED = "com.mandopop.action.NOTIFICATION_DISMISSED"
        const val ACTION_AMBIENT_DISMISSED = "com.mandopop.action.AMBIENT_DISMISSED"
        const val ACTION_REVEAL = "com.mandopop.action.REVEAL"
        const val ACTION_SPEAK = "com.mandopop.action.SPEAK"
        const val EXTRA_HANZI = "hanzi"
        const val EXTRA_SPEAK_TEXT = "speak_text"

        private const val TAG = "MandopopNotif"
        private val HANDLED = setOf(
            Intent.ACTION_MY_PACKAGE_REPLACED,
            ACTION_DISMISSED,
            ACTION_AMBIENT_DISMISSED,
            ACTION_REVEAL,
            ACTION_SPEAK,
        )
    }
}
