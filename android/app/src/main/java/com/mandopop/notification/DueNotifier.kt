package com.mandopop.notification

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.mandopop.MainActivity
import com.mandopop.R
import com.mandopop.traverse.DueExample
import com.mandopop.traverse.SyncOutcome
import com.mandopop.work.NotificationRefreshReceiver

/**
 * The ongoing "cards due" notification.
 *
 * Behaviour: present only while something is actually due, undismissable while it is, and silent.
 * It disappears on its own when the queue hits zero.
 *
 * Deliberately not a foreground service: it avoids the FOREGROUND_SERVICE permission and Android
 * 14+ service-type restrictions.
 *
 * Note that `setOngoing(true)` is NOT sufficient for persistence. Android 14 changed
 * `FLAG_ONGOING_EVENT` so users can swipe these away, and no flag makes a normal app notification
 * truly permanent any more. Persistence is enforced by re-posting from the delete intent — see
 * [NotificationRefreshReceiver].
 *
 * Failures are shown, not hidden. A silently stale counter is indistinguishable from a working one,
 * which makes the sync layer undebuggable on a real device.
 */
object DueNotifier {
    const val CHANNEL_ID = "mandopop_due"
    private const val NOTIFICATION_ID = 1001
    private const val TRAVERSE_PACKAGE = "com.traverse.android"

    fun show(context: Context, outcome: SyncOutcome) {
        when (outcome) {
            // Clearing the queue clears the notification — reaching zero should feel like finishing,
            // not like earning a "well done" that sits in the shade afterwards.
            is SyncOutcome.Success -> if (outcome.dueCount == 0) {
                cancel(context)
            } else {
                val cards = if (outcome.dueCount == 1) "card" else "cards"
                val example = outcome.example
                post(
                    context,
                    title = "${outcome.dueCount} $cards due today",
                    // Characters only. Printing the reading and meaning alongside would turn a
                    // retrieval prompt into passive exposure, which is the opposite of what
                    // spaced repetition is for — the answer lives behind the Reveal action.
                    text = example?.hanzi ?: "${outcome.liveCount} cards in rotation",
                    needsAttention = false,
                    reveal = example?.hanzi,
                )
            }
            // Anything unhealthy needs the user in *our* settings screen, not in Traverse, and must
            // stay dismissable — an undismissable error would be a permanent nuisance.
            is SyncOutcome.NotSignedIn -> post(
                context,
                title = "Sign in to Traverse",
                text = "Open mandopop to connect your account",
                needsAttention = true,
            )
            is SyncOutcome.Failure -> post(
                context,
                title = "Traverse sync failed",
                text = outcome.message,
                needsAttention = true,
            )
        }
    }

    /** Re-post from already-synced local state, without touching the network. */
    fun showLocal(context: Context, dueCount: Int, liveCount: Int, example: DueExample? = null) {
        show(
            context,
            SyncOutcome.Success(
                dueCount = dueCount,
                liveCount = liveCount,
                pulled = false,
                example = example,
            ),
        )
    }

    /**
     * Cancel-then-post, for when the notification must be rebuilt rather than updated.
     *
     * A plain `notify()` with the same id lets SystemUI reuse its cached view and drawable, which
     * is exactly what leaves stale artwork behind after an app update. Cancelling first forces a
     * fresh inflate.
     */
    fun repost(context: Context, dueCount: Int, liveCount: Int, example: DueExample? = null) {
        cancel(context)
        showLocal(context, dueCount, liveCount, example)
    }

    fun showError(context: Context, message: String) {
        show(context, SyncOutcome.Failure(message))
    }

    /**
     * The answer, after the user asked for it.
     *
     * Stays revealed only until the next sync or repost re-hides it, so the notification returns
     * to being a prompt rather than a flashcard left face-up.
     */
    fun showAnswer(context: Context, dueCount: Int, hanzi: String, gloss: String) {
        val cards = if (dueCount == 1) "card" else "cards"
        post(
            context,
            title = "$hanzi — $dueCount $cards due today",
            text = gloss,
            needsAttention = false,
            reveal = null,
        )
    }

    fun cancel(context: Context) {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }

    private fun post(
        context: Context,
        title: String,
        text: String,
        needsAttention: Boolean,
        reveal: String? = null,
    ) {
        if (!canPostNotifications(context)) return
        ensureChannel(context)

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_due)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(contentIntent(context, openSettings = needsAttention))
            .setOngoing(!needsAttention)
            // Android 14+ allows swiping ongoing notifications away, so persistence is enforced by
            // re-posting from this delete intent rather than by a flag. Only for the due-count
            // notification — errors stay dismissable.
            .setDeleteIntent(if (needsAttention) null else dismissIntent(context))
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .apply {
                if (reveal != null) {
                    addAction(
                        NotificationCompat.Action.Builder(
                            R.drawable.ic_notification_due,
                            "Reveal",
                            revealIntent(context, reveal),
                        ).build(),
                    )
                }
            }
            .build()

        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
    }

    /** Taps go to Traverse to actually do the reviews; on error they go to our settings instead. */
    private fun contentIntent(context: Context, openSettings: Boolean): PendingIntent {
        val target = if (openSettings) {
            null
        } else {
            context.packageManager.getLaunchIntentForPackage(TRAVERSE_PACKAGE)
        } ?: Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        return PendingIntent.getActivity(
            context,
            0,
            target,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun revealIntent(context: Context, hanzi: String): PendingIntent {
        val intent = Intent(context, NotificationRefreshReceiver::class.java)
            .setAction(NotificationRefreshReceiver.ACTION_REVEAL)
            .putExtra(NotificationRefreshReceiver.EXTRA_HANZI, hanzi)
        return PendingIntent.getBroadcast(
            context,
            // Distinct request code per word, otherwise FLAG_UPDATE_CURRENT would leave an older
            // PendingIntent holding the previous card's extras.
            hanzi.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun dismissIntent(context: Context): PendingIntent {
        val intent = Intent(context, NotificationRefreshReceiver::class.java)
            .setAction(NotificationRefreshReceiver.ACTION_DISMISSED)
        return PendingIntent.getBroadcast(
            context,
            1,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.due_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(R.string.due_channel_description)
            setShowBadge(true)
        }
        context.getSystemService(NotificationManager::class.java)
            ?.createNotificationChannel(channel)
    }

    private fun canPostNotifications(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }
}
