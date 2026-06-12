package com.beryndil.pharos.refill

import android.app.Application
import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.beryndil.pharos.appContainer
import java.util.concurrent.TimeUnit

/**
 * Periodic WorkManager job that checks supply levels for all active medications and posts
 * low-supply alerts on the REFILL channel (spec §2.9, §2.8, Law 1).
 *
 * Scheduling rationale (DECISIONS.md S7-A4):
 * - WorkManager is acceptable for this task because refill alerts are NOT time-critical
 *   (unlike dose reminders, which must use AlarmManager exact alarms).
 * - CLAUDE.md: "Never WorkManager for reminders." This worker posts only on the refill
 *   channel; it never schedules, fires, or cancels a dose alarm.
 *
 * Zero-supply invariant: this worker posts a low-supply notification but NEVER touches the
 * dose state machine, alarm engine, or dose instance table. A zero supply count has NO
 * effect on dose reminder delivery.
 */
class LowSupplyCheckWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        return runCatching {
            val container = (applicationContext as Application).appContainer
            val refillRepository = container.refillRepository
            val refillNotifier = container.refillNotifier

            refillNotifier.ensureRefillChannel()

            val lowSupplySummaries = refillRepository.getLowSupplySummaries()
            for (summary in lowSupplySummaries) {
                refillNotifier.postLowSupplyAlert(
                    medicationId = summary.medicationId,
                    medName = summary.medicationName,
                    daysLeft = summary.daysUntilEmpty ?: 0,
                )
            }
            Result.success()
        }.getOrElse {
            // Transient failure (DB locked, etc.) — retry once; then give up until next period.
            Result.retry()
        }
    }

    companion object {

        /** Unique work tag — cancels and replaces any prior enqueue. */
        const val WORK_NAME = "pharos_low_supply_check"

        /**
         * Enqueue the daily low-supply check. Safe to call on every app start — if the
         * work already exists [ExistingPeriodicWorkPolicy.KEEP] leaves it undisturbed.
         */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<LowSupplyCheckWorker>(
                repeatInterval = 1,
                repeatIntervalTimeUnit = TimeUnit.DAYS,
            ).build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }

        /**
         * Cancel any queued low-supply job and immediately re-enqueue, replacing the old one.
         *
         * Called after a backup restore ([com.beryndil.pharos.backup.BackupRepository]). A restore
         * replaces the entire regimen DB, so stale supply counts from the pre-restore state
         * should not drive the next periodic check. REPLACE cancels the prior job and
         * schedules a fresh one; the new run will read the restored regimen directly.
         */
        fun scheduleAfterRestore(context: Context) {
            val request = PeriodicWorkRequestBuilder<LowSupplyCheckWorker>(
                repeatInterval = 1,
                repeatIntervalTimeUnit = TimeUnit.DAYS,
            ).build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.REPLACE,
                request,
            )
        }
    }
}
