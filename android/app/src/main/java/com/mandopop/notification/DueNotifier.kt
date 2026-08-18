package com.mandopop.notification

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.mandopop.MainActivity
import com.mandopop.R
import com.mandopop.briefing.BriefingEngine
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
    private const val TAG = "MandopopNotif"
    private const val NOTIFICATION_ID = 1001
    private const val TRAVERSE_PACKAGE = "com.traverse.android"

    fun show(context: Context, outcome: SyncOutcome) {
        when (outcome) {
            // Zero due switches to ambient mode rather than cancelling (spec.md §4): the daily
            // briefing is worth a line whether or not cards are waiting. With no briefing either,
            // the old rule holds — clearing the queue clears the notification, and reaching zero
            // feels like finishing rather than earning a "well done" that sits in the shade.
            is SyncOutcome.Success -> {
                if (outcome.dueCount == 0) {
                    // Dismissal is remembered per generation in BriefingEngine, because reposts
                    // arrive through many doors (worker, resume, shade-pull) and none of them may
                    // resurrect a line the user swiped away. A newly generated briefing shows.
                    val briefing = BriefingEngine.ambientBriefing()
                    if (briefing == null) {
                        cancel(context)
                    } else {
                        post(
                            context,
                            title = "复习完了 ✓",
                            text = briefing.sentence,
                            needsAttention = false,
                            reveal = null,
                            expandedText = expanded(null, briefing),
                            // Dismissable: at zero due there is nothing to nag about. The delete
                            // intent only records the dismissal — it never re-posts.
                            sticky = false,
                            deleteAction = NotificationRefreshReceiver.ACTION_AMBIENT_DISMISSED,
                            speak = briefing.sentence,
                        )
                    }
                } else {
                    val example = outcome.example
                    // The cloze upgrade: a studied all-known sentence containing the due word
                    // beats the bare word as a retrieval prompt. Falls back to the word.
                    val body = example?.sentence ?: example?.hanzi
                        ?: "${outcome.liveCount} cards in rotation"
                    // A briefing that happens to contain the due word would put the recall target
                    // inside a disambiguating sentence right under the prompt — a soft reveal.
                    // Drop it from this one view rather than trying to steer the composer.
                    val briefing = BriefingEngine.current
                        ?.takeIf { example == null || !it.sentence.contains(example.hanzi) }
                    post(
                        context,
                        // Compact per the spec sketch — the collapsed line's budget belongs to
                        // the cloze sentence, not to the word "cards".
                        title = "今天 · ${outcome.dueCount} 到期",
                        // Characters only. Printing the reading and meaning alongside would turn a
                        // retrieval prompt into passive exposure, which is the opposite of what
                        // spaced repetition is for — the answer lives behind the Reveal action.
                        // The briefing sentence rides in the expanded view; its one glossed word
                        // is un-learned, so there is no recall for the gloss to defeat.
                        text = body,
                        needsAttention = false,
                        reveal = example?.hanzi,
                        expandedText = expanded(body, briefing),
                        speak = example?.let { body },
                    )
                }
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
        // The answer is face-up here, so the briefing needs no due-word filter — but it stays in
        // the expanded view so revealing doesn't make the notification visibly lose a limb.
        val briefing = BriefingEngine.current
        post(
            context,
            title = "$hanzi — 今天 · $dueCount 到期",
            text = gloss,
            needsAttention = false,
            reveal = null,
            expandedText = expanded(gloss, briefing),
            speak = hanzi,
        )
    }

    fun cancel(context: Context) {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }

    /**
     * Expanded view: prompt (when due), briefing block, then the score/stats tail — one primary
     * line plus quiet secondaries, never a dashboard. Absent pieces simply don't print.
     */
    private fun expanded(body: String?, briefing: BriefingEngine.Briefing?): String? {
        val tail = listOfNotNull(BriefingEngine.screenScoreLine, StatsTail.line)
        val blocks = listOfNotNull(
            body,
            briefing?.sentence,
            tail.takeIf { it.isNotEmpty() }?.joinToString("\n"),
        )
        return blocks.takeIf { it.size > 1 || body == null }?.joinToString("\n\n")
    }

    private fun post(
        context: Context,
        title: String,
        text: String,
        needsAttention: Boolean,
        reveal: String? = null,
        expandedText: String? = null,
        sticky: Boolean = !needsAttention,
        deleteAction: String? = if (needsAttention) null else NotificationRefreshReceiver.ACTION_DISMISSED,
        speak: String? = null,
    ) {
        // Inline rather than extracted: lint cannot follow a permission check through a helper, and
        // a suppression here would go on lying the day the check is removed.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "POST_NOTIFICATIONS not granted; dropping notification")
            return
        }
        ensureChannel(context)

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_due)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(expandedText ?: text))
            .setContentIntent(contentIntent(context, openSettings = needsAttention))
            .setOngoing(sticky)
            // Android 14+ allows swiping ongoing notifications away, so persistence is enforced by
            // re-posting from the ACTION_DISMISSED delete intent rather than by a flag. The
            // ambient line's delete intent only records the dismissal; errors carry none.
            .setDeleteIntent(deleteAction?.let { dismissIntent(context, it) })
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
                if (speak != null) {
                    addAction(
                        NotificationCompat.Action.Builder(
                            R.drawable.ic_notification_due,
                            "Speak",
                            speakIntent(context, speak),
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

    private fun speakIntent(context: Context, text: String): PendingIntent {
        val intent = Intent(context, NotificationRefreshReceiver::class.java)
            .setAction(NotificationRefreshReceiver.ACTION_SPEAK)
            .putExtra(NotificationRefreshReceiver.EXTRA_SPEAK_TEXT, text)
        return PendingIntent.getBroadcast(
            context,
            // Same per-payload trick as revealIntent, offset so a word's Speak and Reveal
            // request codes can never collide.
            31 * text.hashCode() + 17,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun dismissIntent(context: Context, action: String): PendingIntent {
        val intent = Intent(context, NotificationRefreshReceiver::class.java)
            .setAction(action)
        return PendingIntent.getBroadcast(
            context,
            // Distinct request code per action — the due-count and ambient delete intents must
            // not overwrite each other through FLAG_UPDATE_CURRENT.
            action.hashCode(),
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

}
