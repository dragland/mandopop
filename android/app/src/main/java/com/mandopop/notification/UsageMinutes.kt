package com.mandopop.notification

import android.app.AppOpsManager
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
 * Caveats of record (spec.md §4.3): foreground ≠ engagement, and browser-based study is
 * invisible here.
 */
object UsageMinutes {

    private const val TAG = "MandopopStats"

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
        return try {
            val byPackage = manager.queryAndAggregateUsageStats(startOfDay, System.currentTimeMillis())
            val totalMs = STUDY_PACKAGES.sumOf { byPackage[it]?.totalTimeInForeground ?: 0L }
            (totalMs / 60_000L).toInt()
        } catch (error: Exception) {
            Log.w(TAG, "usage stats query failed", error)
            null
        }
    }
}
