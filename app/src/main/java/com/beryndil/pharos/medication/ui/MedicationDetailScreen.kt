package com.beryndil.pharos.medication.ui

import android.text.format.DateFormat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.beryndil.pharos.R
import com.beryndil.pharos.data.regimen.entity.DoseState
import com.beryndil.pharos.data.regimen.entity.DoseTransitionCause
import com.beryndil.pharos.data.regimen.entity.DoseTransitionEntity
import com.beryndil.pharos.data.regimen.entity.MedicationForm
import com.beryndil.pharos.data.regimen.entity.MedicationStatus
import com.beryndil.pharos.data.regimen.entity.ScheduleType
import com.beryndil.pharos.dose.ui.stateLabelRes
import com.beryndil.pharos.medication.MedicationDetailUiState
import com.beryndil.pharos.medication.RegimenSummary
import java.time.LocalTime
import java.util.Date

/**
 * Read-only Medication Detail screen (item #3). Single [LazyColumn], top-to-bottom:
 *  1. Regimen summary — the user's own data (name, strength/form, dose, schedule + alarm times,
 *     status). No edit dialogs here.
 *  2. Drug-database summary — the reused [DrugInfoCard] over a cached openFDA label, with source
 *     + freshness (Law 9). Free-text meds show a plain "no reference available" line (Law 3).
 *  3. Dose history — the append-only transition list, last.
 *
 * Edit is the top-bar pencil action ([onEdit]); always reachable regardless of scroll.
 * Law 3: reference only, never advice. Law 10: sp text, contentDescriptions, >=48dp targets.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MedicationDetailScreen(
    uiState: MedicationDetailUiState,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    com.beryndil.pharos.core.ui.SecureWindow()
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = uiState.summary?.name
                            ?: stringResource(R.string.screen_medication_detail),
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
                actions = {
                    // Edit pencil — always reachable; navigates to the Add/Edit screen.
                    // Shown only once the medication has loaded (nothing to edit otherwise).
                    if (uiState.summary != null) {
                        IconButton(onClick = onEdit) {
                            Icon(
                                imageVector = Icons.Outlined.Edit,
                                contentDescription = stringResource(R.string.cd_edit_medication),
                            )
                        }
                    }
                },
            )
        },
    ) { innerPadding ->
        if (uiState.isLoading && uiState.summary == null) {
            Box(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // ── 1. Regimen summary ────────────────────────────────────────────
            uiState.summary?.let { summary ->
                item { RegimenSummaryCard(summary) }
            }

            // ── 3. Drug-database summary (cached label) ───────────────────────
            item {
                DrugReferenceBlock(uiState)
            }

            // ── 4. Dose history (append-only) ─────────────────────────────────
            item {
                Text(
                    text = stringResource(R.string.med_detail_section_history),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            if (uiState.transitions.isEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.empty_history_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                items(uiState.transitions, key = { it.id }) { transition ->
                    TransitionRow(transition)
                }
            }

            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun RegimenSummaryCard(summary: RegimenSummary) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.med_detail_section_regimen),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = summary.name,
                style = MaterialTheme.typography.titleLarge,
            )
            val formLabel = summary.formName.toFormLabel()
            Text(
                text = stringResource(R.string.med_strength_form, summary.strength, formLabel),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (summary.doseAmount.isNotBlank()) {
                LabeledLine(
                    label = stringResource(R.string.med_detail_dose_label),
                    value = summary.doseAmount,
                )
            }

            val scheduleText = scheduleSummaryText(summary)
            if (scheduleText != null) {
                LabeledLine(
                    label = stringResource(R.string.med_detail_schedule_label),
                    value = scheduleText,
                )
            }

            val timesText = summary.scheduledTimes.takeIf { it.isNotEmpty() }?.let { times ->
                formatTimes(times)
            }
            if (timesText != null) {
                LabeledLine(
                    label = stringResource(R.string.med_detail_alarm_times_label),
                    value = timesText,
                )
            }

            LabeledLine(
                label = stringResource(R.string.med_detail_status_label),
                value = statusLabel(summary.status),
            )
        }
    }
}

/** A label + value pair (no color-only signals — text carries the meaning, Law 10). */
@Composable
private fun LabeledLine(label: String, value: String) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

/**
 * Drug-reference block. For free-text / no-rxcui meds shows the plain "no reference" notice
 * (Law 3). When a cached label exists, renders the reused [DrugInfoCard] plus the source +
 * freshness date (Law 9). When there is no cached label and the med is not free-text, shows
 * nothing extra — the detail screen never triggers a network fetch.
 */
@Composable
private fun DrugReferenceBlock(uiState: MedicationDetailUiState) {
    if (uiState.isFreeText) {
        Text(
            text = stringResource(R.string.med_detail_no_reference),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }

    val source = uiState.labelSource
    val fetchedAt = uiState.labelFetchedAtEpochMs
    if (source != null && fetchedAt != null) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            DrugInfoCard(labelPreview = uiState.labelPreview)
            DrugReferenceMetadata(source = source, fetchedAtEpochMs = fetchedAt)
        }
    }
}

/** Source + freshness date for the cached label (Law 9). */
@Composable
private fun DrugReferenceMetadata(source: String, fetchedAtEpochMs: Long) {
    val context = LocalContext.current
    val dateStr = DateFormat.getMediumDateFormat(context).format(Date(fetchedAtEpochMs))
    val metaCd = stringResource(R.string.cd_drug_reference_metadata, source, dateStr)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) { contentDescription = metaCd },
    ) {
        Text(
            text = stringResource(R.string.drug_reference_source_label) + ": " + source,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(R.string.drug_reference_fetched_label) + ": " + dateStr,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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

    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
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
        HorizontalDivider(modifier = Modifier.padding(top = 8.dp))
    }
}

/** Maps [DoseTransitionCause] to a factual string resource (Law 3 — no advice). */
private fun causeLabelRes(cause: DoseTransitionCause): Int = when (cause) {
    DoseTransitionCause.ALARM_FIRED -> R.string.history_cause_alarm_fired
    DoseTransitionCause.USER_TAKEN -> R.string.history_cause_user_taken
    DoseTransitionCause.USER_SNOOZED -> R.string.history_cause_user_snoozed
    DoseTransitionCause.USER_SKIPPED -> R.string.history_cause_user_skipped
    DoseTransitionCause.SNOOZE_ELAPSED -> R.string.history_cause_snooze_elapsed
    DoseTransitionCause.MISS_WINDOW_CLOSED -> R.string.history_cause_miss_window_closed
}

/** Factual schedule-type summary, or null if there's no schedule to describe. */
@Composable
private fun scheduleSummaryText(summary: RegimenSummary): String? {
    val type = summary.scheduleType?.let {
        runCatching { ScheduleType.valueOf(it) }.getOrNull()
    } ?: return null
    return when (type) {
        ScheduleType.FIXED_DAILY -> stringResource(R.string.schedule_type_fixed_daily)
        ScheduleType.DAYS_OF_WEEK -> stringResource(R.string.schedule_type_days_of_week)
        ScheduleType.INTERVAL -> summary.intervalHours
            ?.let { stringResource(R.string.med_detail_schedule_interval, it) }
            ?: stringResource(R.string.schedule_type_interval)
        ScheduleType.DOSE_WINDOW -> {
            val start = summary.windowStartTime
            val end = summary.windowEndTime
            if (start != null && end != null) {
                stringResource(R.string.med_detail_schedule_window, start, end)
            } else {
                stringResource(R.string.schedule_type_dose_window)
            }
        }
        ScheduleType.PRN -> stringResource(R.string.schedule_type_prn)
        ScheduleType.TEMPORARY -> stringResource(R.string.schedule_type_temporary)
        ScheduleType.TAPER -> stringResource(R.string.schedule_type_taper)
    }
}

@Composable
private fun statusLabel(status: String): String {
    val parsed = runCatching { MedicationStatus.valueOf(status) }.getOrElse { MedicationStatus.ACTIVE }
    return when (parsed) {
        MedicationStatus.ACTIVE -> stringResource(R.string.med_status_active)
        MedicationStatus.PAUSED -> stringResource(R.string.med_status_paused)
        MedicationStatus.ENDED -> stringResource(R.string.med_status_ended)
    }
}

/** Formats wall-clock times with the device locale (e.g. "8:00 AM, 8:00 PM"). */
@Composable
private fun formatTimes(times: List<LocalTime>): String {
    val context = LocalContext.current
    val formatter = DateFormat.getTimeFormat(context)
    return times.joinToString(", ") { time ->
        val cal = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, time.hour)
            set(java.util.Calendar.MINUTE, time.minute)
            set(java.util.Calendar.SECOND, 0)
        }
        formatter.format(cal.time)
    }
}

/** Map the stored form name to a display string. */
@Composable
private fun String.toFormLabel(): String {
    val form = runCatching { MedicationForm.valueOf(this) }.getOrNull()
    return when (form) {
        MedicationForm.TABLET -> stringResource(R.string.form_tablet)
        MedicationForm.CAPLET -> stringResource(R.string.form_caplet)
        MedicationForm.CAPSULE -> stringResource(R.string.form_capsule)
        MedicationForm.LIQUID -> stringResource(R.string.form_liquid)
        MedicationForm.INJECTION -> stringResource(R.string.form_injection)
        MedicationForm.INHALER -> stringResource(R.string.form_inhaler)
        MedicationForm.PATCH -> stringResource(R.string.form_patch)
        MedicationForm.DROPS -> stringResource(R.string.form_drops)
        MedicationForm.CREAM -> stringResource(R.string.form_cream)
        MedicationForm.OTHER, null -> stringResource(R.string.form_other)
    }
}
