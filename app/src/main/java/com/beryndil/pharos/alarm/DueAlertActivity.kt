package com.beryndil.pharos.alarm

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.beryndil.pharos.MainActivity
import com.beryndil.pharos.R
import com.beryndil.pharos.ui.theme.PharosTheme
import java.util.Date

/**
 * The full-screen dose-due alert (spec §2.8, §3.4; DESIGN.md). It is launched by the dose
 * notification's full-screen intent and takes over the screen — including over the lock screen
 * and turning the screen on — so a sleeping user is alerted (Standards §3).
 *
 * Visual bar (DESIGN.md, Apple-grade): calm and content-first. One dominant element (the
 * medication name), the scheduled time set as first-class data, and exactly one primary action.
 * The Taken / Snooze / Skip actions and the sacred-channel escalation are Slice 5 — this slice
 * ships the alert surface and a single "open the app" action with a clean seam
 * ([DoseActionHandler]) for those transitions.
 *
 * FLAG_SECURE is applied because the screen renders a medication name (PHI, Standards §6).
 */
class DueAlertActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showWhenLockedAndTurnScreenOn()
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        enableEdgeToEdge()

        val medName = intent.getStringExtra(AlarmContract.EXTRA_MED_NAME).orEmpty()
        val dueEpochMs = intent.getLongExtra(AlarmContract.EXTRA_DUE_EPOCH_MS, 0L)

        setContent {
            PharosTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    DueAlertContent(
                        medName = medName,
                        dueTimeText = formatTime(dueEpochMs),
                        onOpen = {
                            startActivity(
                                Intent(this, MainActivity::class.java)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                            )
                            finish()
                        },
                    )
                }
            }
        }
    }

    /** Locale-aware time (honors the device 12/24h toggle) per Standards §7 — never hand-built. */
    private fun formatTime(epochMs: Long): String =
        if (epochMs <= 0L) {
            ""
        } else {
            android.text.format.DateFormat.getTimeFormat(this).format(Date(epochMs))
        }

    private fun showWhenLockedAndTurnScreenOn() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            )
        }
    }
}

@Composable
private fun DueAlertContent(
    medName: String,
    dueTimeText: String,
    onOpen: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(horizontal = 32.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(R.string.dose_due_heading),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            textAlign = TextAlign.Center,
        )
        Text(
            text = medName,
            style = MaterialTheme.typography.displaySmall,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
        )
        if (dueTimeText.isNotEmpty()) {
            Text(
                text = dueTimeText,
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        Button(
            onClick = onOpen,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 32.dp),
        ) {
            Text(text = stringResource(R.string.dose_due_open))
        }
    }
}
