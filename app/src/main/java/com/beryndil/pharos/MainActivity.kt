package com.beryndil.pharos

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.navigation.compose.rememberNavController
import com.beryndil.pharos.ui.navigation.NavRoute
import com.beryndil.pharos.ui.navigation.PharosNavGraph
import com.beryndil.pharos.ui.navigation.resolveStartDestination
import com.beryndil.pharos.ui.theme.PharosTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // FLAG_SECURE is now applied per-screen (A3-5).
        // PHI-bearing screens call SecureWindow(); non-PHI screens (Onboarding, Legal) call
        // ClearWindowSecurity(). The global flag is no longer set here — see DECISIONS.md A3-5.

        setContent {
            PharosTheme {
                val app = application as PharosApplication

                // Resolve start destination asynchronously (DB read) to avoid StrictMode main-
                // thread I/O. produceState suspends on a coroutine launched in the composition
                // scope; the surface renders nothing until the value is available (one DB query
                // round-trip — typically sub-millisecond on a warm database).
                val startDestination by produceState<String?>(initialValue = null) {
                    value = withContext(Dispatchers.IO) {
                        resolveStartDestination(
                            isOnboardingComplete = {
                                app.appContainer.onboardingRepository.isComplete()
                            },
                            countNonEndedMedications = {
                                app.appContainer.regimenDatabase.medicationDao().countNonEnded()
                            },
                        )
                    }
                }

                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    startDestination?.let { dest ->
                        val navController = rememberNavController()
                        PharosNavGraph(navController = navController, startDestination = dest)
                    }
                }
            }
        }
    }
}
