package com.beryndil.pharos.medication.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.CalendarToday
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Switch
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import android.app.NotificationManager
import android.content.Context
import android.provider.Settings
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.beryndil.pharos.R
import com.beryndil.pharos.data.regimen.entity.MedicationForm
import com.beryndil.pharos.medication.AddEditMedEvent
import com.beryndil.pharos.medication.AddEditMedicationUiState
import com.beryndil.pharos.medication.FormStep
import com.beryndil.pharos.medication.SaveError
import com.beryndil.pharos.medication.model.DrugSearchResult
import com.beryndil.pharos.medication.model.DuplicateWarning
import com.beryndil.pharos.schedule.ui.ScheduleSection
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * Single-screen Add/Edit medication flow.
 *
 * Internally steps through SEARCH → CONFIRM → DETAILS, driven by [uiState.step].
 * Stateless — the ViewModel owns all state via [uiState] + [onEvent].
 *
 * FLAG_SECURE is applied globally in MainActivity (Standards §6).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddEditMedicationScreen(
    uiState: AddEditMedicationUiState,
    onEvent: (AddEditMedEvent) -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val errorText = when (uiState.saveError) {
        SaveError.GENERAL -> stringResource(R.string.error_save_failed)
        null -> null
    }

    LaunchedEffect(uiState.savedSuccessfully) {
        if (uiState.savedSuccessfully) onDone()
    }

    LaunchedEffect(uiState.saveError) {
        if (errorText != null) {
            snackbarHostState.showSnackbar(errorText)
            onEvent(AddEditMedEvent.ErrorDismissed)
        }
    }

    val screenTitle = when (uiState.step) {
        FormStep.SEARCH, FormStep.DETAILS -> stringResource(
            if (uiState.editMedId == null) R.string.screen_add_medication
            else R.string.screen_edit_medication,
        )
        FormStep.CONFIRM -> stringResource(R.string.step_confirm_heading)
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(screenTitle) },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            if (uiState.step == FormStep.SEARCH) onDone()
                            else onEvent(AddEditMedEvent.StepBack)
                        },
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.cd_back_button),
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        when (uiState.step) {
            FormStep.SEARCH -> SearchStep(
                uiState = uiState,
                onEvent = onEvent,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            )
            FormStep.CONFIRM -> ConfirmStep(
                drug = uiState.pendingDrug,
                onEvent = onEvent,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            )
            FormStep.DETAILS -> DetailsStep(
                uiState = uiState,
                onEvent = onEvent,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            )
        }
    }

    if (uiState.showDuplicateWarning) {
        DuplicateWarningDialog(
            newMedName = uiState.displayName,
            warnings = uiState.pendingDuplicateWarnings,
            onDismiss = { onEvent(AddEditMedEvent.DuplicateWarningDismissed) },
            onConfirm = { onEvent(AddEditMedEvent.DuplicateWarningConfirmed) },
        )
    }

    if (uiState.showDndPermissionRationale) {
        val context = LocalContext.current
        DndPermissionRationaleDialog(
            onDismiss = { onEvent(AddEditMedEvent.DndPermissionRationaleDismissed) },
            onGrant = {
                onEvent(AddEditMedEvent.DndPermissionRationaleDismissed)
                context.startActivity(
                    android.content.Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS),
                )
            },
        )
    }
}

// ── Search step ───────────────────────────────────────────────────────────

@Composable
private fun SearchStep(
    uiState: AddEditMedicationUiState,
    onEvent: (AddEditMedEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.step_search_heading),
                style = MaterialTheme.typography.headlineSmall,
            )
            OutlinedTextField(
                value = uiState.nameQuery,
                onValueChange = { onEvent(AddEditMedEvent.NameQueryChanged(it)) },
                label = { Text(stringResource(R.string.medication_name_label)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Words,
                    imeAction = ImeAction.Search,
                ),
                trailingIcon = if (uiState.isSearching) {
                    {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                        )
                    }
                } else null,
            )

            if (uiState.nameQuery.length >= 2) {
                if (uiState.searchResults.isNotEmpty()) {
                    Text(
                        text = stringResource(R.string.search_results_hint),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Column {
                        uiState.searchResults.forEach { result ->
                            SearchResultItem(
                                result = result,
                                onClick = { onEvent(AddEditMedEvent.DrugSelected(result)) },
                            )
                            HorizontalDivider()
                        }
                    }
                } else if (!uiState.isSearching) {
                    NoMatchSection(onAddAsCustom = { onEvent(AddEditMedEvent.ContinueAsCustom) })
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun SearchResultItem(result: DrugSearchResult, onClick: () -> Unit) {
    val desc = stringResource(
        R.string.cd_search_result_item,
        result.name,
        result.strength,
        result.rxNormForm,
    )
    ListItem(
        headlineContent = { Text(result.name, style = MaterialTheme.typography.bodyLarge) },
        supportingContent = {
            Text(
                text = stringResource(
                    R.string.search_result_subtitle,
                    result.strength,
                    result.rxNormForm,
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = result.name, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = desc },
    )
}

@Composable
private fun NoMatchSection(onAddAsCustom: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = stringResource(R.string.no_drug_db_match),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(R.string.add_as_custom_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = onAddAsCustom) {
            Text(stringResource(R.string.add_as_custom))
        }
    }
}

// ── Confirm step ──────────────────────────────────────────────────────────

@Composable
private fun ConfirmStep(
    drug: DrugSearchResult?,
    onEvent: (AddEditMedEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (drug == null) return

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.confirm_question),
            style = MaterialTheme.typography.headlineSmall,
        )

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = drug.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                )
                if (drug.ingredientNames.isNotEmpty()) {
                    LabeledValue(
                        label = stringResource(
                            if (drug.ingredientNames.size == 1) R.string.label_ingredient
                            else R.string.label_ingredients,
                        ),
                        value = drug.ingredientNames.joinToString(", "),
                    )
                }
                LabeledValue(
                    label = stringResource(R.string.label_strength),
                    value = drug.strength,
                )
                LabeledValue(
                    label = stringResource(R.string.label_form),
                    value = drug.rxNormForm,
                )
            }
        }

        Button(
            onClick = { onEvent(AddEditMedEvent.ConfirmDrug) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.btn_confirm))
        }

        TextButton(
            onClick = { onEvent(AddEditMedEvent.StepBack) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.btn_not_right))
        }

        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun LabeledValue(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(text = value, style = MaterialTheme.typography.bodyMedium)
    }
}

// ── Details step ──────────────────────────────────────────────────────────

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun DetailsStep(
    uiState: AddEditMedicationUiState,
    onEvent: (AddEditMedEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showStartDatePicker by rememberSaveable { mutableStateOf(false) }
    var showEndDatePicker by rememberSaveable { mutableStateOf(false) }

    Column(modifier = modifier) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Spacer(Modifier.height(8.dp))

            if (uiState.isFreeText) FreeTextNotice()

            SectionHeader(stringResource(R.string.section_required))

            OutlinedTextField(
                value = uiState.strength,
                onValueChange = { onEvent(AddEditMedEvent.StrengthChanged(it)) },
                label = { Text(stringResource(R.string.label_strength_hint)) },
                isError = uiState.strengthError,
                supportingText = if (uiState.strengthError) {
                    { Text(stringResource(R.string.error_strength_required)) }
                } else null,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            FormSelector(
                selectedForm = uiState.selectedForm,
                isError = uiState.formError,
                onFormSelected = { onEvent(AddEditMedEvent.FormSelected(it)) },
            )

            OutlinedTextField(
                value = uiState.doseAmount,
                onValueChange = { onEvent(AddEditMedEvent.DoseAmountChanged(it)) },
                label = { Text(stringResource(R.string.label_dose_amount)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            DateField(
                label = stringResource(R.string.label_start_date),
                date = uiState.startDate,
                isError = uiState.startDateError,
                errorText = stringResource(R.string.error_start_date_required),
                onPickerRequested = { showStartDatePicker = true },
                onClear = null,
            )

            SectionHeader(stringResource(R.string.section_optional))

            DateField(
                label = stringResource(R.string.label_end_date),
                date = uiState.endDate,
                isError = false,
                errorText = null,
                onPickerRequested = { showEndDatePicker = true },
                onClear = { onEvent(AddEditMedEvent.EndDateSelected(null)) },
            )

            OutlinedTextField(
                value = uiState.prescriber,
                onValueChange = { onEvent(AddEditMedEvent.PrescriberChanged(it)) },
                label = { Text(stringResource(R.string.label_prescriber)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = uiState.pharmacy,
                onValueChange = { onEvent(AddEditMedEvent.PharmacyChanged(it)) },
                label = { Text(stringResource(R.string.label_pharmacy)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = uiState.purpose,
                onValueChange = { onEvent(AddEditMedEvent.PurposeChanged(it)) },
                label = { Text(stringResource(R.string.label_purpose)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            SectionHeader(stringResource(R.string.section_critical_alerts))
            CriticalToggleRow(
                isCritical = uiState.isCritical,
                onToggle = { onEvent(AddEditMedEvent.IsCriticalToggled(it)) },
            )

            ScheduleSection(
                input = uiState.scheduleInput,
                error = uiState.scheduleValidationError,
                onInputChanged = { onEvent(AddEditMedEvent.ScheduleInputChanged(it)) },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(16.dp))
        }

        HorizontalDivider()
        Button(
            onClick = { onEvent(AddEditMedEvent.SaveRequested) },
            enabled = !uiState.isSaving,
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
        ) {
            if (uiState.isSaving) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            } else {
                Text(stringResource(R.string.btn_save))
            }
        }
    }

    if (showStartDatePicker) {
        LocalDatePickerDialog(
            initialDate = uiState.startDate,
            onDateSelected = { date ->
                onEvent(AddEditMedEvent.StartDateSelected(date))
                showStartDatePicker = false
            },
            onDismiss = { showStartDatePicker = false },
        )
    }
    if (showEndDatePicker) {
        LocalDatePickerDialog(
            initialDate = uiState.endDate,
            onDateSelected = { date ->
                onEvent(AddEditMedEvent.EndDateSelected(date))
                showEndDatePicker = false
            },
            onDismiss = { showEndDatePicker = false },
        )
    }
}

@Composable
private fun FreeTextNotice() {
    val desc = stringResource(R.string.cd_free_text_notice)
    OutlinedCard(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = desc },
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                imageVector = Icons.Outlined.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
            Text(
                text = stringResource(R.string.free_text_notice),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp),
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FormSelector(
    selectedForm: MedicationForm?,
    isError: Boolean,
    onFormSelected: (MedicationForm) -> Unit,
) {
    val forms = listOf(
        MedicationForm.TABLET to R.string.form_tablet,
        MedicationForm.CAPSULE to R.string.form_capsule,
        MedicationForm.LIQUID to R.string.form_liquid,
        MedicationForm.INJECTION to R.string.form_injection,
        MedicationForm.INHALER to R.string.form_inhaler,
        MedicationForm.PATCH to R.string.form_patch,
        MedicationForm.DROPS to R.string.form_drops,
        MedicationForm.CREAM to R.string.form_cream,
        MedicationForm.OTHER to R.string.form_other,
    )
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = stringResource(R.string.label_form_heading),
            style = MaterialTheme.typography.bodyMedium,
            color = if (isError) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurface,
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            forms.forEach { (form, labelRes) ->
                val label = stringResource(labelRes)
                val isSelected = form == selectedForm
                // Precompute in composable scope — semantics {} is not a @Composable lambda.
                val chipCd = if (isSelected) {
                    stringResource(R.string.cd_form_chip_selected, label)
                } else {
                    stringResource(R.string.cd_form_chip_unselected, label)
                }
                FilterChip(
                    selected = isSelected,
                    onClick = { onFormSelected(form) },
                    label = { Text(label) },
                    modifier = Modifier.semantics { contentDescription = chipCd },
                )
            }
        }
        if (isError) {
            Text(
                text = stringResource(R.string.error_form_required),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun DateField(
    label: String,
    date: LocalDate?,
    isError: Boolean,
    errorText: String?,
    onPickerRequested: () -> Unit,
    onClear: (() -> Unit)?,
) {
    val formatter = remember {
        DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(Locale.getDefault())
    }
    OutlinedTextField(
        value = date?.format(formatter) ?: "",
        onValueChange = {},
        label = { Text(label) },
        readOnly = true,
        isError = isError,
        supportingText = if (isError && errorText != null) {
            { Text(errorText) }
        } else null,
        leadingIcon = {
            IconButton(onClick = onPickerRequested) {
                Icon(
                    imageVector = Icons.Outlined.CalendarToday,
                    contentDescription = label,
                )
            }
        },
        trailingIcon = if (onClear != null && date != null) {
            {
                IconButton(onClick = onClear) {
                    Icon(
                        imageVector = Icons.Outlined.Close,
                        contentDescription = stringResource(R.string.cd_clear_end_date),
                    )
                }
            }
        } else null,
        modifier = Modifier.fillMaxWidth(),
    )
}

// ── Critical reminder toggle ──────────────────────────────────────────────

/**
 * Row with a Switch and plain-language label for the isCritical flag (spec §3.1).
 * Icon + text pair — never color alone (Law 10).
 */
@Composable
private fun CriticalToggleRow(
    isCritical: Boolean,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val cd = if (isCritical) {
        stringResource(R.string.cd_critical_toggle_on)
    } else {
        stringResource(R.string.cd_critical_toggle_off)
    }
    OutlinedCard(
        modifier = modifier
            .fillMaxWidth()
            .semantics { contentDescription = cd },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                imageVector = Icons.Outlined.NotificationsOff,
                contentDescription = null,
                tint = if (isCritical) MaterialTheme.colorScheme.primary
                       else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .size(20.dp)
                    .padding(top = 2.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.critical_toggle_label),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = stringResource(R.string.critical_toggle_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            Switch(
                checked = isCritical,
                onCheckedChange = onToggle,
            )
        }
    }
}

// ── DND permission rationale dialog ──────────────────────────────────────

@Composable
private fun DndPermissionRationaleDialog(
    onDismiss: () -> Unit,
    onGrant: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(R.string.dnd_rationale_title),
                style = MaterialTheme.typography.titleLarge,
            )
        },
        text = {
            Text(
                text = stringResource(R.string.dnd_rationale_body),
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.btn_cancel))
            }
        },
        confirmButton = {
            TextButton(onClick = onGrant) {
                Text(stringResource(R.string.dnd_rationale_grant))
            }
        },
    )
}

// ── Duplicate-ingredient warning dialog ───────────────────────────────────

@Composable
private fun DuplicateWarningDialog(
    newMedName: String,
    warnings: List<DuplicateWarning>,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(R.string.duplicate_warning_title),
                style = MaterialTheme.typography.titleLarge,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                warnings.forEach { warning ->
                    Text(
                        text = stringResource(
                            R.string.duplicate_warning_message,
                            newMedName,
                            warning.existingMedName,
                            warning.ingredientName,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.btn_cancel))
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.btn_save_anyway))
            }
        },
    )
}

// ── Date picker ───────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LocalDatePickerDialog(
    initialDate: LocalDate?,
    onDateSelected: (LocalDate) -> Unit,
    onDismiss: () -> Unit,
) {
    val initialMs = initialDate
        ?.atStartOfDay(ZoneId.of("UTC"))?.toInstant()?.toEpochMilli()
    val state = rememberDatePickerState(initialSelectedDateMillis = initialMs)

    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    val ms = state.selectedDateMillis ?: return@TextButton
                    val date = Instant.ofEpochMilli(ms).atZone(ZoneId.of("UTC")).toLocalDate()
                    onDateSelected(date)
                },
                enabled = state.selectedDateMillis != null,
            ) {
                Text(stringResource(R.string.btn_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.btn_cancel))
            }
        },
    ) {
        DatePicker(state = state)
    }
}
