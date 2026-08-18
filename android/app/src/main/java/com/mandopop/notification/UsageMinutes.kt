package com.mandopop.notification

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import android.os.Process
import android.util.Log
import java.time.LocalDate
import java.time.ZoneId

/**
 * Foreground minutes spent in study apps today, straight from UsageStats — OS-held, queried at
 * read time, stored nowhere (the purity rule). Needs the PACKAGE_USAGE_STATS special-access
 * grant; ungranted degrades to null and the stats line simply omits the minutes.
 *
 * Summed from foreground/background *events* clipped to the local day, never from
 * `queryUsageStats` buckets: daily buckets don't align to local midnight, so a bucket query
 * minutes after midnight happily reports yesterday's whole total as "today".
 *
 * Caveats of record (spec.md §4.3): foreground ≠ engagement, and browser-based study is
 * invisible here.
 */
object UsageMinutes {

    private const val TAG = "MandopopStats"

    /** How far past midnight a study session is allowed to straddle and still be clipped in. */
    private const val STRADDLE_LOOKBACK_MS = 6 * 60 * 60 * 1000L

    /**
     * The study allowlist. Traverse is certain; the other two are the apps' published ids —
     * verify against `adb shell pm list packages` if their minutes ever read as zero while
     * plainly in use.
     */
    private val STUDY_PACKAGES = setOf(
        "com.traverse.android",
        "com.hellochinese",
        "com.kajabi.kajabiapp",
    )

    fun isGranted(context: Context): Boolean {
        val appOps = context.getSystemService(AppOpsManager::class.java) ?: return false
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName,
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName,
            )
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /** Minutes in study apps since local midnight, or null when the grant is missing. */
    fun today(context: Context): Int? {
        if (!isGranted(context)) return null
        val manager = context.getSystemService(UsageStatsManager::class.java) ?: return null
        val zone = ZoneId.systemDefault()
        val startOfDay = LocalDate.now(zone).atStartOfDay(zone).toInstant().toEpochMilli()
        val now = System.currentTimeMillis()
        return try {
            // The window reaches back past midnight so a session straddling it (resumed 23:50,
            // paused 00:20) is seen whole and *clipped* to the day, rather than its lone PAUSED
            // event being dropped for lack of a matching RESUMED.
            val events = manager.queryEvents(startOfDay - STRADDLE_LOOKBACK_MS, now) ?: return null
            var totalMs = 0L
            val resumedAt = HashMap<String, Long>()
            val event = UsageEvents.Event()
            fun credit(fromMs: Long, toMs: Long) {
                val clippedFrom = fromMs.coerceAtLeast(startOfDay)
                if (toMs > clippedFrom) totalMs += toMs - clippedFrom
            }
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                if (event.packageName !in STUDY_PACKAGES) continue
                // ACTIVITY_RESUMED/PAUSED share values with the pre-Q constants.
                @Suppress("DEPRECATION")
                when (event.eventType) {
                    UsageEvents.Event.MOVE_TO_FOREGROUND ->
                        resumedAt.putIfAbsent(event.packageName, event.timeStamp)
                    UsageEvents.Event.MOVE_TO_BACKGROUND ->
                        resumedAt.remove(event.packageName)?.let { credit(it, event.timeStamp) }
                }
            }
            // Anything still foreground counts up to the query moment.
            for (since in resumedAt.values) credit(since, now)
            (totalMs / 60_000L).toInt()
        } catch (error: Exception) {
            Log.w(TAG, "usage events query failed", error)
            null
        }
    }
}
