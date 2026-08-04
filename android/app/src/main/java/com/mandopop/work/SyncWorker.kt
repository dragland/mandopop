package com.mandopop.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.mandopop.notification.DueNotifier
import com.mandopop.traverse.SyncOutcome
import com.mandopop.traverse.TraverseSync
import java.util.concurrent.TimeUnit

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val sync = TraverseSync(applicationContext)
        val outcome = sync.sync()
        DueNotifier.show(applicationContext, outcome)

        return when {
            outcome !is SyncOutcome.Failure -> Result.success()
            // Only retry things that might succeed on their own. A 4xx (rules change, expired
            // session, schema drift) will fail identically next time, and retry backoff on a
            // periodic worker fires *more* often than the period — turning a permanent fault into
            // a battery drain. The error is already on screen either way.
            isTransient(outcome.statusCode) -> Result.retry()
            else -> Result.success()
        }
    }

    private fun isTransient(statusCode: Int?): Boolean =
        statusCode == null || statusCode >= 500 || statusCode == 429

    companion object {
        private const val PERIODIC_NAME = "traverse-sync-periodic"
        private const val ONE_SHOT_NAME = "traverse-sync-now"

        /**
         * 15 minutes is WorkManager's periodic floor. Doze will stretch it, which is fine — the
         * steady-state cost is one Firestore document read per run, and the due count is
         * recomputed locally anyway.
         */
        fun ensureScheduled(context: Context) {
            val request = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }

        /**
         * One-off sync, used when we have a reason to believe something just changed (currently:
         * the user left the Traverse app). Cheap — the events heartbeat means this is usually a
         * single Firestore document read.
         */
        fun syncNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                ONE_SHOT_NAME,
                ExistingWorkPolicy.REPLACE,
                request,
            )
        }

        fun cancelAll(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(PERIODIC_NAME)
            WorkManager.getInstance(context).cancelUniqueWork(ONE_SHOT_NAME)
        }
    }
}
