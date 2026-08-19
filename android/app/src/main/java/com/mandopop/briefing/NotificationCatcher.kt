package com.mandopop.briefing

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.service.notification.NotificationListenerService
import android.util.Log
import androidx.core.app.NotificationManagerCompat

/**
 * Read-only view of the notification shade, for the daily briefing.
 *
 * `getActiveNotifications()` is the whole point: the OS already distinguishes active from cleared,
 * so "woke up to fifty, cleared them by lunch" needs zero buffering — each briefing generation
 * reads what is actually still pending. This service never posts, cancels, mutates or stores
 * anything, and notification text goes only into the on-device pick/compose/verify pipeline —
 * treated as *untrusted input* there. Fields are extracted and capped, and only a short gist
 * built from the title reaches a model prompt — but a push title is still attacker-authored
 * prose, so the real bound on a hostile push is the verifier: whatever the model does, nothing
 * outside the user's known vocabulary ever renders.
 */
class NotificationCatcher : NotificationListenerService() {

    override fun onListenerConnected() {
        live = this
        Log.i(TAG, "notification listener connected")
    }

    override fun onListenerDisconnected() {
        // Identity-guarded: on a rebind after a crash or update, the old instance's teardown can
        // run after the new one's onListenerConnected — clearing unconditionally would null out
        // a healthy listener and the briefing would silently see an empty shade until rebind.
        if (live === this) live = null
        Log.i(TAG, "notification listener disconnected")
    }

    override fun onDestroy() {
        if (live === this) live = null
        super.onDestroy()
    }

    private fun snapshot(): List<ActiveNotification> {
        val active = try {
            activeNotifications ?: return emptyList()
        } catch (error: SecurityException) {
            // The listener can be revoked while bound; treat it as an empty shade.
            Log.w(TAG, "listener revoked mid-query", error)
            return emptyList()
        }
        return active.mapNotNull { sbn ->
            if (sbn.packageName == packageName) return@mapNotNull null
            val notification = sbn.notification
            // Group summaries duplicate their children; ongoing rows are apps' pinned furniture
            // (players, keyboards, our own counter), not things that happened today.
            if (notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return@mapNotNull null
            if (notification.flags and Notification.FLAG_ONGOING_EVENT != 0) return@mapNotNull null

            val extras = notification.extras
            val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
                ?.trim().orEmpty().take(MAX_FIELD_CHARS)
            val text = (
                extras.getCharSequence(Notification.EXTRA_TEXT)
                    ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
                )?.toString()?.trim().orEmpty().take(MAX_FIELD_CHARS)
            if (title.isEmpty() && text.isEmpty()) return@mapNotNull null

            ActiveNotification(
                appLabel = appLabel(sbn.packageName),
                title = title,
                text = text,
                category = notification.category,
                postTimeMs = sbn.postTime,
            )
        }
    }

    private fun appLabel(pkg: String): String = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (error: Exception) {
        pkg.substringAfterLast('.')
    }

    companion object {
        private const val TAG = "MandopopBriefing"
        private const val MAX_FIELD_CHARS = 200

        @Volatile
        private var live: NotificationCatcher? = null

        /** Whether the user has granted notification access in system settings. */
        fun isEnabled(context: Context): Boolean =
            NotificationManagerCompat.getEnabledListenerPackages(context)
                .contains(context.packageName)

        /**
         * Whether the listener is actually bound right now. Granted-but-unbound is the classic
         * post-crash state and must never masquerade as an empty shade — surfaces that report on
         * the briefing distinguish the two.
         */
        fun isConnected(): Boolean = live != null

        /**
         * The documented recovery for granted-but-unbound: ask the system to rebind. Cheap and
         * idempotent; called opportunistically (app resume) so the user's fallback of re-toggling
         * access in system settings is a last resort, not the first.
         */
        fun requestRebindIfNeeded(context: Context) {
            if (!isEnabled(context) || isConnected()) return
            try {
                requestRebind(ComponentName(context, NotificationCatcher::class.java))
                Log.i(TAG, "requested notification listener rebind")
            } catch (error: Exception) {
                Log.w(TAG, "listener rebind request failed", error)
            }
        }

        /**
         * The shade right now, or empty when access is ungranted / the listener is not yet bound.
         * Degrades like the calendar: the briefing draws on whatever inputs exist.
         */
        fun activeNotifications(): List<ActiveNotification> = live?.snapshot() ?: emptyList()
    }
}
