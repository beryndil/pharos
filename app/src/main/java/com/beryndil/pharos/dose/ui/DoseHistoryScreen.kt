package com.beryndil.pharos.dose.ui

import android.text.format.DateFormat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.beryndil.pharos.R
import com.beryndil.pharos.data.regimen.entity.DoseState
import com.beryndil.pharos.data.regimen.entity.DoseTransitionCause
import com.beryndil.pharos.data.regimen.entity.DoseTransitionEntity
import java.util.Date

/**
 * Per-med append-only dose history (Law 9). Read-only: every transition the state machine recorded
 * appears as its own immutable row, newest first. No advice anywhere (Law 3) — it states only what
 * happened and when.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DoseHistoryScreen(
    uiState: DoseHistoryUiState,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    com.beryndil.pharos.core.ui.SecureWindow()
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = uiState.medicationName.ifEmpty {
                            stringResource(R.string.screen_dose_history)
                        },
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.cd_back_button),
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        if (uiState.transitions.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.empty_history_body),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    horizontal = 16.dp,
                    vertical = 8.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(uiState.transitions, key = { it.id }) { transition ->
                    TransitionRow(transition)
                }
            }
        }
    }
}

@Composable
private fun TransitionRow(transition: DoseTransitionEntity) {
    val context = LocalContext.current
    val timeText = DateFormat.getMediumDateFormat(context).format(Date(transition.atEpochMs)) +
        " " + DateFormat.getTimeFormat(context).format(Date(transition.atEpochMs))
    val causeText = runCatching { DoseTransitionCause.valueOf(transition.cause) }
        .getOrNull()
        ?.let { stringResource(causeLabelRes(it)) }

    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(
            text = stringResource(stateLabelRes(DoseState.valueOf(transition.toState))),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = timeText,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp),
        )
        if (causeText != null) {
            Text(
                text = causeText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 1.dp),
            )
        }
    }
}

/** Maps [DoseTransitionCause] to a factual, human-readable string resource (Law 3 — no advice). */
private fun causeLabelRes(cause: DoseTransitionCause): Int = when (cause) {
    DoseTransitionCause.ALARM_FIRED -> R.string.history_cause_alarm_fired
    DoseTransitionCause.USER_TAKEN -> R.string.history_cause_user_taken
    DoseTransitionCause.USER_SNOOZED -> R.string.history_cause_user_snoozed
    DoseTransitionCause.USER_SKIPPED -> R.string.history_cause_user_skipped
    DoseTransitionCause.SNOOZE_ELAPSED -> R.string.history_cause_snooze_elapsed
    DoseTransitionCause.MISS_WINDOW_CLOSED -> R.string.history_cause_miss_window_closed
}
