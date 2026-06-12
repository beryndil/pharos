package com.beryndil.pharos.dose.ui

import android.text.format.DateFormat
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ListAlt
import androidx.compose.material.icons.outlined.CheckCircleOutline
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.beryndil.pharos.R
import com.beryndil.pharos.data.dose.DoseRow
import com.beryndil.pharos.data.regimen.entity.DoseState
import java.util.Date

/**
 * Today's doses: the actionable surface. DUE and SNOOZED doses carry the three dose actions
 * (Take / Snooze / Skip); SCHEDULED doses appear as upcoming with no action. Calm, content-first
 * (DESIGN.md): the medication name and time are the data; one primary action (Take) per row.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodayScreen(
    uiState: TodayUiState,
    onEvent: (TodayEvent) -> Unit,
    onOpenMedications: () -> Unit,
    onOpenHistory: (String) -> Unit,
    onOpenReliability: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())

    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text(stringResource(R.string.screen_today)) },
                actions = {
                    IconButton(onClick = onOpenReliability) {
                        Icon(
                            imageVector = Icons.Outlined.CheckCircleOutline,
                            contentDescription = stringResource(R.string.cd_open_reliability),
                        )
                    }
                    IconButton(onClick = onOpenMedications) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.ListAlt,
                            contentDescription = stringResource(R.string.cd_open_medications),
                        )
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { innerPadding ->
        if (uiState.doses.isEmpty()) {
            EmptyToday(modifier = Modifier.padding(innerPadding))
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    horizontal = 16.dp,
                    vertical = 8.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(uiState.doses, key = { it.doseId }) { dose ->
                    DoseCard(
                        dose = dose,
                        onTake = { onEvent(TodayEvent.Take(dose.doseId)) },
                        onSnooze = { onEvent(TodayEvent.Snooze(dose.doseId)) },
                        onSkip = { onEvent(TodayEvent.Skip(dose.doseId)) },
                        onHistory = { onOpenHistory(dose.medicationId) },
                    )
                }
            }
        }
    }
}

@Composable
private fun DoseCard(
    dose: DoseRow,
    onTake: () -> Unit,
    onSnooze: () -> Unit,
    onSkip: () -> Unit,
    onHistory: () -> Unit,
) {
    val context = LocalContext.current
    val timeText = DateFormat.getTimeFormat(context).format(Date(dose.dueEpochMs))
    val actionable = dose.state == DoseState.DUE || dose.state == DoseState.SNOOZED

    val takenCd = stringResource(R.string.cd_dose_taken_action, dose.medName)
    val snoozeCd = stringResource(R.string.cd_dose_snooze_action, dose.medName)
    val skipCd  = stringResource(R.string.cd_dose_skip_action,  dose.medName)

    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        Text(
            text = dose.medName,
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clickable(
                    onClickLabel = stringResource(R.string.cd_dose_history_action, dose.medName),
                    onClick = onHistory,
                ),
        )
        Text(
            text = stringResource(R.string.today_dose_summary, dose.strength, timeText),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp),
        )
        Text(
            text = stringResource(stateLabelRes(dose.state)),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp),
        )

        if (actionable) {
            Button(
                onClick = onTake,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp)
                    .semantics { contentDescription = takenCd },
            ) {
                Text(stringResource(R.string.dose_action_taken))
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = onSnooze,
                    modifier = Modifier.weight(1f).semantics { contentDescription = snoozeCd },
                ) {
                    Text(stringResource(R.string.dose_action_snooze))
                }
                TextButton(
                    onClick = onSkip,
                    modifier = Modifier.weight(1f).semantics { contentDescription = skipCd },
                ) {
                    Text(stringResource(R.string.dose_action_skip))
                }
            }
        }
    }
}

@Composable
private fun EmptyToday(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.Schedule,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(R.string.empty_today_body),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
    }
}

internal fun stateLabelRes(state: DoseState): Int = when (state) {
    DoseState.DUE -> R.string.dose_state_due
    DoseState.SNOOZED -> R.string.dose_state_snoozed
    DoseState.SCHEDULED -> R.string.dose_state_scheduled
    DoseState.TAKEN -> R.string.dose_state_taken
    DoseState.SKIPPED -> R.string.dose_state_skipped
    DoseState.MISSED -> R.string.dose_state_missed
}
