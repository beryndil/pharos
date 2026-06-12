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
import androidx.compose.material.icons.outlined.MedicalServices
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import com.beryndil.pharos.data.dose.PrnMedRow
import com.beryndil.pharos.data.regimen.entity.DoseState
import java.util.Date

/**
 * Today's doses: the actionable surface. DUE and SNOOZED doses carry the three dose actions
 * (Take / Snooze / Skip); SCHEDULED doses appear as upcoming with no action; PRN (as-needed)
 * medications show a "Log dose" affordance at all times (spec §2.7). Calm, content-first
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
        val hasDoses = uiState.doses.isNotEmpty()
        val hasPrn = uiState.prnMeds.isNotEmpty()

        if (!hasDoses && !hasPrn) {
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
                // ── Scheduled / DUE / SNOOZED dose rows ──────────────────────────────
                items(uiState.doses, key = { it.doseId }) { dose ->
                    DoseCard(
                        dose = dose,
                        onTake = { onEvent(TodayEvent.Take(dose.doseId)) },
                        onSnooze = { onEvent(TodayEvent.Snooze(dose.doseId)) },
                        onSkip = { onEvent(TodayEvent.Skip(dose.doseId)) },
                        onHistory = { onOpenHistory(dose.medicationId) },
                    )
                }

                // ── PRN (as-needed) section ───────────────────────────────────────────
                if (hasPrn) {
                    if (hasDoses) {
                        item { HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp)) }
                    }
                    item {
                        Text(
                            text = stringResource(R.string.today_prn_section_header),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                        )
                    }
                    items(uiState.prnMeds, key = { "prn_${it.medicationId}" }) { prn ->
                        PrnMedCard(
                            prn = prn,
                            onLogDose = { onEvent(TodayEvent.LogPrn(prn.medicationId, prn.scheduleId)) },
                            onHistory = { onOpenHistory(prn.medicationId) },
                        )
                    }
                }
            }
        }
    }

    // ── PRN daily-max advisory dialog (non-blocking, Law 3 — dose already logged) ────────────
    if (uiState.prnWarningDoseNumber != null) {
        AlertDialog(
            onDismissRequest = { onEvent(TodayEvent.DismissPrnWarning) },
            title = { Text(stringResource(R.string.prn_daily_max_warning_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.prn_daily_max_warning,
                        uiState.prnWarningDoseNumber,
                        uiState.prnWarningDailyMax,
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = { onEvent(TodayEvent.DismissPrnWarning) }) {
                    Text(stringResource(R.string.btn_ok))
                }
            },
        )
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

/**
 * A row for a PRN (as-needed) medication on the Today screen (spec §2.7).
 *
 * PRN doses have no SCHEDULED or MISSED states — the "Log dose" button is the only action.
 * If [PrnMedRow.dosesToday] > 0, shows a "Logged today: N" count so the user can track intake
 * without the app forbidding or advising further doses (Law 3).
 * Tapping the medication name opens dose history (consistent with [DoseCard]).
 */
@Composable
private fun PrnMedCard(
    prn: PrnMedRow,
    onLogDose: () -> Unit,
    onHistory: () -> Unit,
) {
    val logDoseCd = stringResource(R.string.cd_prn_log_dose, prn.medName)

    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        Text(
            text = prn.medName,
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clickable(
                    onClickLabel = stringResource(R.string.cd_dose_history_action, prn.medName),
                    onClick = onHistory,
                ),
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.padding(top = 2.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.MedicalServices,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(R.string.schedule_type_prn),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = prn.strength,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp),
        )
        if (prn.dosesToday > 0) {
            Text(
                text = stringResource(R.string.today_prn_doses_today, prn.dosesToday),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Button(
            onClick = onLogDose,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp)
                .semantics { contentDescription = logDoseCd },
        ) {
            Text(stringResource(R.string.today_prn_log_dose))
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
