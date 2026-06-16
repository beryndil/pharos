package com.beryndil.pharos

import android.app.Application
import android.os.StrictMode
import android.util.Log
import com.beryndil.pharos.backup.AutoBackupWorker
import com.beryndil.pharos.core.db.AppContainer
import com.beryndil.pharos.core.debug.DebugLogger
import com.beryndil.pharos.data.drugref.DrugDbUpdateWorker
import com.beryndil.pharos.refill.LowSupplyCheckWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Application entry point. Enables StrictMode in debug to catch main-thread I/O early. */
class PharosApplication : Application() {

    /** Singleton DI container. Access from Activities/ViewModels via [appContainer]. */
    lateinit var appContainer: AppContainer
        private set

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()

        installCrashLogger()
        DebugLogger.init(applicationContext)

        appContainer = AppContainer(applicationContext)

        rearmAlarmsOnStartup()
        scheduleLowSupplyCheck()
        scheduleDrugDbUpdate()
        scheduleAutoBackup()

        if (BuildConfig.DEBUG) {
            StrictMode.setThreadPolicy(
                StrictMode.ThreadPolicy.Builder()
                    .detectAll()
                    .penaltyLog()
                    .build(),
            )
            StrictMode.setVmPolicy(
                StrictMode.VmPolicy.Builder()
                    .detectLeakedClosableObjects()
                    .penaltyLog()
                    .build(),
            )
        }
    }

    /**
     * Last-resort crash backstop (Standards §6). Expected errors are handled at their boundaries
     * (DB access, network, parsing) so they never reach here; this only catches the truly
     * unforeseen. It writes a PHI-free record (exception class + stack frames, NO field values or
     * messages) to a local file, then delegates to the previous handler so the OS still shows its
     * dialog and restarts the process. It never swallows the crash to keep a corrupted process
     * running — that would be worse than a clean restart for a safety-critical app.
     */
    private fun installCrashLogger() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                val frames = throwable.stackTrace.take(20).joinToString("\n") {
                    "  at ${it.className}.${it.methodName}(${it.fileName}:${it.lineNumber})"
                }
                // Class names + a cause chain of classes only — never throwable.message (may carry
                // a SQL statement or other values). No PHI.
                val causes = generateSequence(throwable.cause) { it.cause }
                    .take(5).joinToString(" <- ") { it.javaClass.name }
                val record = buildString {
                    append("thread=").append(thread.name).append('\n')
                    append("exception=").append(throwable.javaClass.name).append('\n')
                    if (causes.isNotEmpty()) append("causes=").append(causes).append('\n')
                    append(frames)
                }
                java.io.File(filesDir, "last_crash.log").writeText(record)
                Log.e("PharosApplication", "Uncaught ${throwable.javaClass.name}")
            }
            // Always delegate so the OS handles the crash normally (dialog + restart).
            previous?.uncaughtException(thread, throwable)
        }
    }

    /**
     * Enqueue the daily low-supply check via WorkManager (spec §2.9, DECISIONS.md S7-A4).
     * WorkManager is acceptable here because refill alerts are not time-critical (unlike dose
     * reminders, which use AlarmManager exact alarms — CLAUDE.md: "Never WorkManager for reminders").
     * Also ensures the refill notification channel exists before the first check fires.
     */
    private fun scheduleLowSupplyCheck() {
        runCatching {
            appContainer.refillNotifier.ensureRefillChannel()
            LowSupplyCheckWorker.schedule(applicationContext)
        }.onFailure {
            if (BuildConfig.DEBUG) {
                Log.w("PharosApplication", "low-supply check schedule failed: ${it.javaClass.simpleName}")
            }
        }
    }

    /**
     * Enqueue the daily CDN drug-DB update check (spec §3.2, §3.5, DECISIONS.md S8-A4).
     * WorkManager is correct here: CDN updates are not time-critical (unlike dose reminders).
     * Wi-Fi-preferred via [NetworkType.UNMETERED] constraint in [DrugDbUpdateWorker].
     * CDN_BASE_URL is a placeholder until Dave provisions Backblaze B2 + Cloudflare (TODO.md).
     */
    private fun scheduleDrugDbUpdate() {
        runCatching {
            DrugDbUpdateWorker.schedule(applicationContext)
        }.onFailure {
            if (BuildConfig.DEBUG) {
                Log.w("PharosApplication", "drug-DB update schedule failed: ${it.javaClass.simpleName}")
            }
        }
    }

    /**
     * Schedule the daily auto-backup job (Downloads/Pharos/pharos-auto-backup.pbk).
     * WorkManager deduplicates via KEEP policy — safe to call every launch.
     */
    private fun scheduleAutoBackup() {
        runCatching {
            AutoBackupWorker.schedule(applicationContext)
        }.onFailure {
            if (BuildConfig.DEBUG) {
                Log.w("PharosApplication", "auto-backup schedule failed: ${it.javaClass.simpleName}")
            }
        }
    }

    /**
     * Re-arm the full alarm set on cold start (Standards §10: the active-alarm list must rebuild
     * from Room on cold start). Runs off the main thread; failures are isolated and logged so a
     * transient DB/keystore error at boot can't crash app launch — the re-registration receivers
     * provide a second chance on the next system trigger.
     */
    private fun rearmAlarmsOnStartup() {
        appScope.launch {
            runCatching {
                appContainer.doseNotifier.ensureChannels()
                appContainer.alarmCoordinator.sweepStaleDoses()
                appContainer.alarmCoordinator.rearmNextDoseAlarm()
                appContainer.alarmCoordinator.scheduleDailyRollover()
            }.onFailure {
                if (BuildConfig.DEBUG) {
                    Log.w("PharosApplication", "startup alarm rearm failed: ${it.javaClass.simpleName}")
                }
            }
        }
    }
}

/** Convenience extension to get [AppContainer] from any [android.content.Context]. */
val Application.appContainer: AppContainer
    get() = (this as PharosApplication).appContainer
