package com.beryndil.pharos.data.drugref

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.beryndil.pharos.BuildConfig
import java.util.concurrent.TimeUnit

/**
 * WorkManager worker that checks for and applies a CDN drug-reference DB update (spec §3.2, §3.5).
 *
 * WorkManager is correct here: CDN updates are NOT time-critical (unlike dose reminders, which
 * use AlarmManager exact alarms). Wi-Fi-preferred via [NetworkType.UNMETERED] constraint.
 *
 * Default schedule: daily. The constraint [NetworkType.UNMETERED] prefers Wi-Fi but allows
 * cellular if no Wi-Fi becomes available; use [NetworkType.UNMETERED] for Wi-Fi-only strictness.
 *
 * The worker wraps [DrugDbUpdater.checkForUpdate] and logs the outcome. Failures are non-fatal:
 * the prior DB stays in place and the worker retries on the next scheduled run (no exponential
 * back-off needed — daily is already conservative).
 */
class DrugDbUpdateWorker(
    private val context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "CDN drug-DB update check starting")
        }
        // CDN base URL: placeholder until Dave provisions Backblaze B2 + Cloudflare (TODO.md).
        // Override via the manifest constant in ManifestVerifier + the CDN_BASE_URL constant below.
        val cdnBaseUrl = CDN_BASE_URL
        if (cdnBaseUrl.isBlank()) {
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "CDN_BASE_URL not configured — skipping update (see TODO.md)")
            }
            return Result.success() // Not a failure; nothing is misconfigured in the app itself.
        }
        val updater = DrugDbUpdater(
            context = context,
            cdnBaseUrl = cdnBaseUrl,
            manifestVerifier = ManifestVerifier.production(),
        )
        return when (val result = updater.checkForUpdate()) {
            is DrugDbUpdater.UpdateResult.Success -> {
                Log.i(TAG, "Drug-ref DB updated to schema v${result.dbSchemaVersion}")
                Result.success()
            }
            is DrugDbUpdater.UpdateResult.Failure -> {
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "Drug-ref DB update not applied: ${result.reason}")
                }
                // Return success so WorkManager does not exponentially back off for expected
                // conditions (e.g., no internet, already up to date). A genuine error is still
                // non-fatal — the prior DB remains intact (Law 9).
                Result.success()
            }
        }
    }

    companion object {
        private const val TAG = "DrugDbUpdateWorker"
        private const val WORK_NAME = "pharos_drug_db_update"

        /**
         * CDN base URL. Placeholder — Dave replaces with the real Backblaze B2 + Cloudflare URL
         * when provisioning the CDN (see TODO.md and DECISIONS.md S8-A4).
         * Keep blank until the real CDN is live; a blank URL causes the worker to skip gracefully.
         */
        const val CDN_BASE_URL = ""

        /**
         * Enqueues the daily CDN update check. [ExistingPeriodicWorkPolicy.KEEP] prevents
         * duplicate enqueues on every app start (same as the low-supply worker pattern).
         *
         * Constraint: [NetworkType.UNMETERED] (Wi-Fi preferred per spec §3.2 "Wi-Fi-preferred").
         */
        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.UNMETERED)
                .build()
            val request = PeriodicWorkRequestBuilder<DrugDbUpdateWorker>(1, TimeUnit.DAYS)
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }
    }
}
