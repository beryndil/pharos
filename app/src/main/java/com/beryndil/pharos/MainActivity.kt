package com.beryndil.pharos

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.navigation.compose.rememberNavController
import com.beryndil.pharos.ui.navigation.PharosNavGraph
import com.beryndil.pharos.ui.theme.PharosTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Standards §6: FLAG_SECURE on all screens that render health data.
        // Currently applied globally (all screens in this release render PHI or lead to PHI).
        // TODO(security-hardening): refine to per-screen once home/onboarding screens are added.
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)

        setContent {
            PharosTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    val navController = rememberNavController()
                    PharosNavGraph(navController = navController)
                }
            }
        }
    }
}
