package com.beryndil.pharos

import android.app.Application
import android.os.StrictMode
import com.beryndil.pharos.core.db.AppContainer

/** Application entry point. Enables StrictMode in debug to catch main-thread I/O early. */
class PharosApplication : Application() {

    /** Singleton DI container. Access from Activities/ViewModels via [appContainer]. */
    lateinit var appContainer: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()

        appContainer = AppContainer(applicationContext)

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
}

/** Convenience extension to get [AppContainer] from any [android.content.Context]. */
val Application.appContainer: AppContainer
    get() = (this as PharosApplication).appContainer
