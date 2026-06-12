package com.beryndil.pharos

import android.os.Bundle
import android.view.WindowManager
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
import com.beryndil.pharos.ui.theme.PharosTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Standards §6: FLAG_SECURE on all screens that render health data.
        // Applied globally: all screens currently lead to PHI. Onboarding and the reliability
        // dashboard carry no PHI themselves but keeping the flag is the conservative stance.
        // Decision logged in DECISIONS.md (S6-A1).
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)

        setContent {
            PharosTheme {
                val app = application as PharosApplication

                // Resolve start destination asynchronously (DB read) to avoid StrictMode main-
                // thread I/O. produceState suspends on a coroutine launched in the composition
                // scope; the surface renders nothing until the value is available (one DB query
                // round-trip — typically sub-millisecond on a warm database).
                val startDestination by produceState<String?>(initialValue = null) {
                    value = withContext(Dispatchers.IO) {
                        if (app.appContainer.onboardingRepository.isComplete()) {
                            NavRoute.Today.route
                        } else {
                            NavRoute.Onboarding.route
                        }
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
