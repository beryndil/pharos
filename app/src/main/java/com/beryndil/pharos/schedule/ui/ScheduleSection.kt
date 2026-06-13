package com.beryndil.pharos.schedule.ui

import android.text.format.DateFormat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.AccessTime
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.beryndil.pharos.R
import com.beryndil.pharos.data.regimen.entity.IntervalAnchorType
import com.beryndil.pharos.data.regimen.entity.ScheduleType
import com.beryndil.pharos.schedule.model.PhaseInput
import com.beryndil.pharos.schedule.model.ScheduleInput
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

/**
 * Full schedule configuration section for the Add/Edit medication flow.
 *
 * Stateless — all state lives in [input]; changes reported via [onInputChanged].
 * [error] is true when save-time validation failed; displays a generic error message.
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ScheduleSection(
    input: ScheduleInput,
    error: Boolean,
    onInputChanged: (ScheduleInput) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = stringResource(R.string.section_schedule),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 8.dp),
        )

        // Type selector
        ScheduleTypeSelector(
            selected = input.type,
            onTypeSelected = { newType ->
                onInputChanged(input.copy(type = newType))
            },
        )

        // Per-type fields
        when (input.type) {
            ScheduleType.FIXED_DAILY -> FixedDailySection(input, onInputChanged)
            ScheduleType.DAYS_OF_WEEK -> DaysOfWeekSection(input, onInputChanged)
            ScheduleType.INTERVAL -> IntervalSection(input, onInputChanged)
            ScheduleType.DOSE_WINDOW -> DoseWindowSection(input, onInputChanged)
            ScheduleType.PRN -> PrnSection(input, onInputChanged)
            ScheduleType.TEMPORARY -> FixedDailySection(input, onInputChanged) // same fields
            ScheduleType.TAPER -> TaperSection(input, onInputChanged)
        }

        if (error) {
            Text(
                text = getScheduleErrorText(input),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

// ── Type selector ─────────────────────────────────────────────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ScheduleTypeSelector(
    selected: ScheduleType,
    onTypeSelected: (ScheduleType) -> Unit,
) {
    val types = listOf(
        ScheduleType.FIXED_DAILY to R.string.schedule_type_fixed_daily,
        ScheduleType.DAYS_OF_WEEK to R.string.schedule_type_days_of_week,
        ScheduleType.INTERVAL to R.string.schedule_type_interval,
        ScheduleType.DOSE_WINDOW to R.string.schedule_type_dose_window,
        ScheduleType.PRN to R.string.schedule_type_prn,
        ScheduleType.TEMPORARY to R.string.schedule_type_temporary,
        ScheduleType.TAPER to R.string.schedule_type_taper,
    )
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        types.forEach { (type, labelRes) ->
            val label = stringResource(labelRes)
            val isSelected = type == selected
            val cd = if (isSelected) {
                stringResource(R.string.cd_schedule_type_selected, label)
            } else {
                stringResource(R.string.cd_schedule_type_not_selected, label)
            }
            FilterChip(
                selected = isSelected,
                onClick = { onTypeSelected(type) },
                label = { Text(label) },
                modifier = Modifier.semantics { contentDescription = cd },
            )
        }
    }
}

// ── Fixed daily / Temporary ───────────────────────────────────────────────────

@Composable
private fun FixedDailySection(
    input: ScheduleInput,
    onInputChanged: (ScheduleInput) -> Unit,
) {
    TimesRow(
        times = input.times,
        onTimesChanged = { onInputChanged(input.copy(times = it)) },
    )
}

// ── Days of week ──────────────────────────────────────────────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DaysOfWeekSection(
    input: ScheduleInput,
    onInputChanged: (ScheduleInput) -> Unit,
) {
    TimesRow(
        times = input.times,
        onTimesChanged = { onInputChanged(input.copy(times = it)) },
    )

    Spacer(Modifier.height(4.dp))
    Text(
        text = stringResource(R.string.schedule_days_label),
        style = MaterialTheme.typography.bodyMedium,
    )

    val days = listOf(
        DayOfWeek.MONDAY to Pair(R.string.day_short_mon, R.string.day_full_mon),
        DayOfWeek.TUESDAY to Pair(R.string.day_short_tue, R.string.day_full_tue),
        DayOfWeek.WEDNESDAY to Pair(R.string.day_short_wed, R.string.day_full_wed),
        DayOfWeek.THURSDAY to Pair(R.string.day_short_thu, R.string.day_full_thu),
        DayOfWeek.FRIDAY to Pair(R.string.day_short_fri, R.string.day_full_fri),
        DayOfWeek.SATURDAY to Pair(R.string.day_short_sat, R.string.day_full_sat),
        DayOfWeek.SUNDAY to Pair(R.string.day_short_sun, R.string.day_full_sun),
    )
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        days.forEach { (dow, labels) ->
            val shortLabel = stringResource(labels.first)
            val fullName = stringResource(labels.second)
            val isSelected = dow in input.daysOfWeek
            val cd = if (isSelected) {
                stringResource(R.string.cd_day_selected, fullName)
            } else {
                stringResource(R.string.cd_day_not_selected, fullName)
            }
            FilterChip(
                selected = isSelected,
                onClick = {
                    val newDays = if (isSelected) {
                        input.daysOfWeek - dow
                    } else {
                        input.daysOfWeek + dow
                    }
                    onInputChanged(input.copy(daysOfWeek = newDays))
                },
                label = { Text(shortLabel) },
                modifier = Modifier.semantics { contentDescription = cd },
            )
        }
    }
}

// ── Interval ──────────────────────────────────────────────────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun IntervalSection(
    input: ScheduleInput,
    onInputChanged: (ScheduleInput) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(R.string.schedule_interval_label),
            style = MaterialTheme.typography.bodyMedium,
        )
        OutlinedTextField(
            value = if (input.intervalHours == 0) "" else input.intervalHours.toString(),
            onValueChange = { raw ->
                val h = raw.toIntOrNull() ?: 0
                onInputChanged(input.copy(intervalHours = h))
            },
            label = { Text(stringResource(R.string.schedule_interval_hours_suffix)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.weight(1f),
        )
    }

    // Anchor type
    Text(
        text = stringResource(R.string.schedule_anchor_schedule),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp),
    )
    val scheduleAnchorLabel = stringResource(R.string.schedule_anchor_schedule)
    val lastDoseAnchorLabel = stringResource(R.string.schedule_anchor_last_dose)
    val scheduleAnchorCd = if (input.intervalAnchor == IntervalAnchorType.SCHEDULE_ANCHORED)
        stringResource(R.string.cd_schedule_type_selected, scheduleAnchorLabel)
    else
        stringResource(R.string.cd_schedule_type_not_selected, scheduleAnchorLabel)
    val lastDoseAnchorCd = if (input.intervalAnchor == IntervalAnchorType.LAST_TAKEN)
        stringResource(R.string.cd_schedule_type_selected, lastDoseAnchorLabel)
    else
        stringResource(R.string.cd_schedule_type_not_selected, lastDoseAnchorLabel)
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        FilterChip(
            selected = input.intervalAnchor == IntervalAnchorType.SCHEDULE_ANCHORED,
            onClick = { onInputChanged(input.copy(intervalAnchor = IntervalAnchorType.SCHEDULE_ANCHORED)) },
            label = { Text(scheduleAnchorLabel) },
            modifier = Modifier.semantics { contentDescription = scheduleAnchorCd },
        )
        FilterChip(
            selected = input.intervalAnchor == IntervalAnchorType.LAST_TAKEN,
            onClick = { onInputChanged(input.copy(intervalAnchor = IntervalAnchorType.LAST_TAKEN)) },
            label = { Text(lastDoseAnchorLabel) },
            modifier = Modifier.semantics { contentDescription = lastDoseAnchorCd },
        )
    }

    // Anchor time field — only relevant for SCHEDULE_ANCHORED (defines the origin of the interval grid)
    if (input.intervalAnchor == IntervalAnchorType.SCHEDULE_ANCHORED) {
        TimePickerField(
            label = stringResource(R.string.schedule_interval_anchor_time),
            time = input.times.firstOrNull() ?: java.time.LocalTime.of(8, 0),
            onTimeSelected = { picked ->
                onInputChanged(input.copy(times = listOf(picked)))
            },
        )
    }

    // Daily window switch
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {},
    ) {
        Text(
            text = stringResource(R.string.schedule_daily_window),
            style = MaterialTheme.typography.bodyMedium,
        )
        Switch(
            checked = input.intervalWindowEnabled,
            onCheckedChange = { onInputChanged(input.copy(intervalWindowEnabled = it)) },
        )
    }
    if (input.intervalWindowEnabled) {
        TimePickerField(
            label = stringResource(R.string.schedule_window_start),
            time = input.intervalWindowStart,
            onTimeSelected = { onInputChanged(input.copy(intervalWindowStart = it)) },
        )
        TimePickerField(
            label = stringResource(R.string.schedule_window_end),
            time = input.intervalWindowEnd,
            onTimeSelected = { onInputChanged(input.copy(intervalWindowEnd = it)) },
        )
    }
}

// ── Dose window ───────────────────────────────────────────────────────────────

@Composable
private fun DoseWindowSection(
    input: ScheduleInput,
    onInputChanged: (ScheduleInput) -> Unit,
) {
    TimePickerField(
        label = stringResource(R.string.schedule_window_start),
        time = input.windowStart,
        onTimeSelected = { onInputChanged(input.copy(windowStart = it)) },
    )
    TimePickerField(
        label = stringResource(R.string.schedule_window_end),
        time = input.windowEnd,
        onTimeSelected = { onInputChanged(input.copy(windowEnd = it)) },
    )
}

// ── PRN ───────────────────────────────────────────────────────────────────────

@Composable
private fun PrnSection(
    input: ScheduleInput,
    onInputChanged: (ScheduleInput) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {},
    ) {
        Text(
            text = stringResource(R.string.schedule_prn_daily_max),
            style = MaterialTheme.typography.bodyMedium,
        )
        Switch(
            checked = input.dailyMaxDoses != null,
            onCheckedChange = { enabled ->
                onInputChanged(input.copy(dailyMaxDoses = if (enabled) 1 else null))
            },
        )
    }
    if (input.dailyMaxDoses != null) {
        OutlinedTextField(
            value = if (input.dailyMaxDoses == 0) "" else input.dailyMaxDoses.toString(),
            onValueChange = { raw ->
                val n = raw.toIntOrNull() ?: 0
                onInputChanged(input.copy(dailyMaxDoses = n))
            },
            label = { Text(stringResource(R.string.schedule_prn_daily_max_hint)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

// ── Taper ─────────────────────────────────────────────────────────────────────

@Composable
private fun TaperSection(
    input: ScheduleInput,
    onInputChanged: (ScheduleInput) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        input.phases.forEachIndexed { index, phase ->
            TaperPhaseCard(
                phaseNumber = index + 1,
                phase = phase,
                canRemove = input.phases.size > 1,
                onPhaseChanged = { updated ->
                    val newPhases = input.phases.toMutableList().also { it[index] = updated }
                    onInputChanged(input.copy(phases = newPhases))
                },
                onRemove = {
                    val newPhases = input.phases.toMutableList().also { it.removeAt(index) }
                    onInputChanged(input.copy(phases = newPhases))
                },
            )
        }
        TextButton(
            onClick = {
                onInputChanged(input.copy(phases = input.phases + PhaseInput(id = UUID.randomUUID().toString())))
            },
        ) {
            Text(stringResource(R.string.schedule_taper_add_phase))
        }
    }
}

@Composable
private fun TaperPhaseCard(
    phaseNumber: Int,
    phase: PhaseInput,
    canRemove: Boolean,
    onPhaseChanged: (PhaseInput) -> Unit,
    onRemove: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = stringResource(R.string.schedule_taper_phase_label, phaseNumber),
                style = MaterialTheme.typography.titleSmall,
            )
            if (canRemove) {
                IconButton(onClick = onRemove) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(R.string.schedule_taper_remove_phase),
                    )
                }
            }
        }
        OutlinedTextField(
            value = phase.doseDescription,
            onValueChange = { onPhaseChanged(phase.copy(doseDescription = it)) },
            label = { Text(stringResource(R.string.schedule_taper_phase_dose_label)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = if (phase.durationDays == 0) "" else phase.durationDays.toString(),
            onValueChange = { raw ->
                val d = raw.toIntOrNull() ?: 0
                onPhaseChanged(phase.copy(durationDays = d))
            },
            label = { Text(stringResource(R.string.schedule_taper_phase_duration_label)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
        TimesRow(
            times = phase.times,
            onTimesChanged = { onPhaseChanged(phase.copy(times = it)) },
        )
    }
}

// ── Shared components ─────────────────────────────────────────────────────────

/**
 * A row of time InputChips (tap to edit) plus a trailing AssistChip to add a new time.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TimesRow(
    times: List<LocalTime>,
    onTimesChanged: (List<LocalTime>) -> Unit,
) {
    // Index of the time chip being edited; -1 means "show add picker"
    var editingIndex by rememberSaveable { mutableStateOf<Int?>(null) }
    var showAddPicker by rememberSaveable { mutableStateOf(false) }

    Text(
        text = stringResource(R.string.schedule_times_label),
        style = MaterialTheme.typography.bodyMedium,
    )

    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        times.forEachIndexed { index, time ->
            val formatted = formatLocalTime(time)
            val chipCd = stringResource(R.string.cd_time_chip, formatted)
            val removeCd = stringResource(R.string.cd_remove_time, formatted)
            InputChip(
                selected = false,
                onClick = { editingIndex = index },
                label = { Text(formatted) },
                trailingIcon = {
                    // §8: touch target ≥48dp — do NOT apply Modifier.size to IconButton;
                    // Material3 LocalMinimumInteractiveComponentSize enforces 48dp by default.
                    // Only the inner Icon is constrained to the chip's visual icon size.
                    IconButton(
                        onClick = {
                            onTimesChanged(times.toMutableList().also { it.removeAt(index) })
                        },
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = removeCd,
                            modifier = Modifier.size(AssistChipDefaults.IconSize),
                        )
                    }
                },
                modifier = Modifier.semantics { contentDescription = chipCd },
            )
        }

        AssistChip(
            onClick = { showAddPicker = true },
            label = { Text(stringResource(R.string.schedule_add_time)) },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = stringResource(R.string.cd_schedule_add_time),
                    modifier = Modifier.size(AssistChipDefaults.IconSize),
                )
            },
        )
    }

    // Edit existing time
    editingIndex?.let { idx ->
        val currentTime = times.getOrNull(idx) ?: LocalTime.of(8, 0)
        TimePickerDialog(
            initialTime = currentTime,
            onConfirm = { picked ->
                val updated = times.toMutableList().also { it[idx] = picked }
                onTimesChanged(updated)
                editingIndex = null
            },
            onDismiss = { editingIndex = null },
        )
    }

    // Add new time
    if (showAddPicker) {
        TimePickerDialog(
            initialTime = LocalTime.of(8, 0),
            onConfirm = { picked ->
                onTimesChanged(times + picked)
                showAddPicker = false
            },
            onDismiss = { showAddPicker = false },
        )
    }
}

/**
 * An outlined text field that opens a time picker on tap.
 */
@Composable
private fun TimePickerField(
    label: String,
    time: LocalTime,
    onTimeSelected: (LocalTime) -> Unit,
) {
    var showPicker by rememberSaveable { mutableStateOf(false) }

    OutlinedTextField(
        value = formatLocalTime(time),
        onValueChange = {},
        label = { Text(label) },
        readOnly = true,
        modifier = Modifier.fillMaxWidth(),
        trailingIcon = {
            IconButton(onClick = { showPicker = true }) {
                Icon(
                    imageVector = Icons.Outlined.AccessTime,
                    contentDescription = label,
                )
            }
        },
    )

    if (showPicker) {
        TimePickerDialog(
            initialTime = time,
            onConfirm = {
                onTimeSelected(it)
                showPicker = false
            },
            onDismiss = { showPicker = false },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimePickerDialog(
    initialTime: LocalTime,
    onConfirm: (LocalTime) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val state = rememberTimePickerState(
        initialHour = initialTime.hour,
        initialMinute = initialTime.minute,
        is24Hour = DateFormat.is24HourFormat(context),
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.time_picker_title)) },
        text = { TimePicker(state = state) },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm(LocalTime.of(state.hour, state.minute))
                },
            ) {
                Text(stringResource(R.string.btn_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.btn_cancel))
            }
        },
    )
}

// ── Formatting ────────────────────────────────────────────────────────────────

@Composable
private fun formatLocalTime(time: LocalTime): String {
    val context = LocalContext.current
    val pattern = remember(context) {
        DateFormat.getBestDateTimePattern(Locale.getDefault(), "jm")
    }
    val formatter = remember(pattern) {
        DateTimeFormatter.ofPattern(pattern, Locale.getDefault())
    }
    return time.format(formatter)
}

// ── Error text helper ─────────────────────────────────────────────────────────

@Composable
private fun getScheduleErrorText(input: ScheduleInput): String = when (input.type) {
    ScheduleType.FIXED_DAILY, ScheduleType.TEMPORARY ->
        if (input.times.isEmpty()) stringResource(R.string.schedule_error_no_times)
        else ""
    ScheduleType.DAYS_OF_WEEK ->
        when {
            input.times.isEmpty() -> stringResource(R.string.schedule_error_no_times)
            input.daysOfWeek.isEmpty() -> stringResource(R.string.schedule_error_no_days)
            else -> ""
        }
    ScheduleType.INTERVAL ->
        if (input.intervalHours !in 1..168) stringResource(R.string.schedule_error_invalid_interval)
        else ""
    ScheduleType.DOSE_WINDOW ->
        if (!input.windowEnd.isAfter(input.windowStart)) stringResource(R.string.schedule_error_invalid_window)
        else ""
    ScheduleType.PRN -> ""
    ScheduleType.TAPER ->
        when {
            input.phases.isEmpty() -> stringResource(R.string.schedule_error_taper_empty)
            input.phases.any { it.doseDescription.isBlank() || it.durationDays <= 0 || it.times.isEmpty() } ->
                stringResource(R.string.schedule_error_taper_phase)
            else -> ""
        }
}
