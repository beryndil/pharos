package com.beryndil.pharos.backup

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.beryndil.pharos.appContainer
import java.util.concurrent.TimeUnit

/**
 * WorkManager job that writes an automatic encrypted backup once per day.
 *
 * The backup lands in Downloads/Pharos/pharos-auto-backup.pbk (see [AutoBackupManager]).
 * On failure the job retries with WorkManager's exponential backoff; a missed day is not
 * critical since the previous day's file is still intact.
 *
 * WorkManager is correct here (not AlarmManager) — this is background bookkeeping, not a
 * time-sensitive dose reminder (CLAUDE.md: "Never WorkManager for reminders").
 */
class AutoBackupWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result =
        try {
            when ((applicationContext as Application).appContainer.autoBackupManager.writeAutoBackup()) {
                is BackupResult.Success -> Result.success()
                is BackupResult.Error -> Result.retry()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Auto-backup failed: ${e.javaClass.simpleName}")
            Result.retry()
        }

    companion object {
        private const val TAG = "AutoBackupWorker"
        private const val WORK_NAME = "pharos_auto_backup_daily"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<AutoBackupWorker>(1, TimeUnit.DAYS)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }
    }
}
