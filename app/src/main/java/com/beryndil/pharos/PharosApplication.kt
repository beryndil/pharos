package com.beryndil.pharos

import android.app.Application
import android.os.StrictMode
import android.util.Log
import com.beryndil.pharos.core.db.AppContainer
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

        appContainer = AppContainer(applicationContext)

        rearmAlarmsOnStartup()

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
     * Re-arm the full alarm set on cold start (Standards §10: the active-alarm list must rebuild
     * from Room on cold start). Runs off the main thread; failures are isolated and logged so a
     * transient DB/keystore error at boot can't crash app launch — the re-registration receivers
     * provide a second chance on the next system trigger.
     */
    private fun rearmAlarmsOnStartup() {
        appScope.launch {
            runCatching {
                appContainer.doseNotifier.ensureChannels()
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
