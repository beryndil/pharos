package com.beryndil.pharos.medication.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.CalendarToday
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.outlined.SettingsSuggest
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Switch
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.beryndil.pharos.R
import com.beryndil.pharos.data.regimen.entity.MedicationEntity
import com.beryndil.pharos.data.regimen.entity.MedicationForm
import com.beryndil.pharos.data.regimen.entity.MedicationStatus
import com.beryndil.pharos.data.regimen.entity.PharmacyEntity
import com.beryndil.pharos.data.regimen.entity.PrescriberEntity
import com.beryndil.pharos.medication.AddEditMedEvent
import com.beryndil.pharos.medication.AddEditMedicationUiState
import androidx.compose.ui.text.style.TextOverflow
import com.beryndil.pharos.medication.FormStep
import com.beryndil.pharos.medication.LabelPreviewState
import com.beryndil.pharos.medication.SaveError
import com.beryndil.pharos.medication.model.DrugSearchResult
import com.beryndil.pharos.medication.model.DuplicateWarning
import com.beryndil.pharos.contacts.ui.ContactAutocompleteField
import com.beryndil.pharos.contacts.ui.PharmacyAutocompleteField
import com.beryndil.pharos.schedule.ui.ScheduleSection
import com.beryndil.pharos.ui.util.PhoneVisualTransformation
import com.beryndil.pharos.ui.util.formatPhoneDisplay
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
    com.beryndil.pharos.core.ui.SecureWindow()

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
                labelPreview = uiState.labelPreview,
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
        result.tty,
    )
    ListItem(
        headlineContent = { Text(result.name, style = MaterialTheme.typography.bodyLarge) },
        supportingContent = {
            Text(
                text = stringResource(R.string.search_result_subtitle, result.tty),
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
    labelPreview: LabelPreviewState,
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
                    label = stringResource(R.string.label_type),
                    value = drug.tty,
                )
            }
        }

        when (val preview = labelPreview) {
            is LabelPreviewState.Loading -> DrugInfoLoadingRow()
            is LabelPreviewState.Available -> DrugInfoPreviewSections(preview)
            else -> {}
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

            OutlinedTextField(
                value = uiState.displayName,
                onValueChange = { onEvent(AddEditMedEvent.DisplayNameChanged(it)) },
                label = { Text(stringResource(R.string.medication_name_label)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Words,
                    imeAction = ImeAction.Next,
                ),
            )

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

            ContactAutocompleteField(
                value = uiState.prescriber,
                onValueChange = { onEvent(AddEditMedEvent.PrescriberChanged(it)) },
                label = stringResource(R.string.label_prescriber),
                suggestions = uiState.prescriberSuggestions,
                onSuggestionPicked = { onEvent(AddEditMedEvent.PrescriberSuggestionPicked(it)) },
                practiceValue = uiState.prescriberPractice,
                onPracticeChange = { onEvent(AddEditMedEvent.PrescriberPracticeChanged(it)) },
                practiceLabel = stringResource(R.string.label_prescriber_practice),
                phoneValue = uiState.prescriberPhone,
                onPhoneChange = { onEvent(AddEditMedEvent.PrescriberPhoneChanged(it)) },
                phoneLabel = stringResource(R.string.label_prescriber_phone),
                modifier = Modifier.fillMaxWidth(),
            )

            PharmacyAutocompleteField(
                value = uiState.pharmacy,
                onValueChange = { onEvent(AddEditMedEvent.PharmacyChanged(it)) },
                label = stringResource(R.string.label_pharmacy),
                suggestions = uiState.pharmacySuggestions,
                onSuggestionPicked = { onEvent(AddEditMedEvent.PharmacySuggestionPicked(it)) },
                phoneValue = uiState.pharmacyPhone,
                onPhoneChange = { onEvent(AddEditMedEvent.PharmacyPhoneChanged(it)) },
                phoneLabel = stringResource(R.string.label_pharmacy_phone),
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = uiState.purpose,
                onValueChange = { onEvent(AddEditMedEvent.PurposeChanged(it)) },
                label = { Text(stringResource(R.string.label_purpose)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            val notesCd = stringResource(R.string.cd_notes_field)
            OutlinedTextField(
                value = uiState.notes,
                onValueChange = { onEvent(AddEditMedEvent.NotesChanged(it)) },
                label = { Text(stringResource(R.string.label_notes)) },
                placeholder = { Text(stringResource(R.string.label_notes_hint)) },
                singleLine = false,
                maxLines = 3,
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = notesCd },
            )

            SubstituteSection(
                search = uiState.substituteSearch,
                committedName = uiState.substituteForDrugName,
                searchResults = uiState.substituteSearchResults,
                brandSuggestionsAvailable = uiState.brandSuggestionsAvailable,
                onSearchChanged = { onEvent(AddEditMedEvent.SubstituteSearchChanged(it)) },
                onSelected = { onEvent(AddEditMedEvent.SubstituteSelected(it)) },
            )

            CombinedPrescriptionSection(
                availableMeds = uiState.allActiveMeds,
                selectedMedId = uiState.combinedWithMedId,
                combinedDisplayStrength = uiState.combinedDisplayStrength,
                onMedSelected = { onEvent(AddEditMedEvent.CombinedWithMedChanged(it)) },
                onStrengthChanged = { onEvent(AddEditMedEvent.CombinedDisplayStrengthChanged(it)) },
            )

            SectionHeader(stringResource(R.string.section_critical_alerts))
            CriticalToggleRow(
                isCritical = uiState.isCritical,
                onToggle = { onEvent(AddEditMedEvent.IsCriticalToggled(it)) },
            )

            MissWindowField(
                value = uiState.missWindowMinutesText,
                isError = uiState.missWindowMinutesError,
                onValueChange = { onEvent(AddEditMedEvent.MissWindowMinutesChanged(it)) },
            )

            ScheduleSection(
                input = uiState.scheduleInput,
                error = uiState.scheduleValidationError,
                onInputChanged = { onEvent(AddEditMedEvent.ScheduleInputChanged(it)) },
                modifier = Modifier.fillMaxWidth(),
            )

            AutoManagedToggleRow(
                isAutoManaged = uiState.isAutoManaged,
                onToggle = { onEvent(AddEditMedEvent.IsAutoManagedToggled(it)) },
            )

            if (uiState.editMedId != null && !uiState.isFreeText) {
                DrugInfoCard(labelPreview = uiState.labelPreview)
            }

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

// ── Drug info preview (shown in CONFIRM step and edit mode) ───────────────

@Composable
private fun DrugInfoLoadingRow(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
        Text(
            text = stringResource(R.string.drug_info_loading),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun DrugInfoPreviewSections(preview: LabelPreviewState.Available, modifier: Modifier = Modifier) {
    val hasAnyData = preview.boxedWarningText != null || preview.sideEffectsText != null ||
        preview.interactionsText != null || preview.warningsText != null ||
        preview.precautionsText != null || preview.foodEffectText != null
    if (!hasAnyData) return

    SelectionContainer {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionHeader(stringResource(R.string.section_drug_info))
        preview.boxedWarningText?.let { DrugInfoBoxedWarning(it) }
        preview.sideEffectsText?.let {
            DrugInfoSection(stringResource(R.string.drug_reference_section_side_effects), it)
        }
        preview.interactionsText?.let {
            DrugInfoSection(stringResource(R.string.drug_reference_section_interactions), it)
        }
        preview.warningsText?.let {
            DrugInfoSection(stringResource(R.string.drug_reference_section_warnings), it)
        }
        preview.precautionsText?.let {
            DrugInfoSection(stringResource(R.string.drug_reference_section_precautions), it)
        }
        preview.foodEffectText?.let {
            DrugInfoSection(stringResource(R.string.drug_reference_section_food_effect), it)
        }
        Text(
            text = stringResource(R.string.drug_reference_disclaimer),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    }
}

@Composable
private fun DrugInfoBoxedWarning(body: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = MaterialTheme.colorScheme.errorContainer,
                shape = RoundedCornerShape(8.dp),
            )
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = stringResource(R.string.drug_reference_section_boxed_warning),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onErrorContainer,
        )
        Text(
            text = body,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onErrorContainer,
        )
    }
}

@Composable
private fun DrugInfoSection(title: String, body: String) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val truncated = body.length > DRUG_INFO_PREVIEW_CHARS
    val displayed = if (truncated && !expanded) body.take(DRUG_INFO_PREVIEW_CHARS).trimEnd() + "…" else body
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(text = title, style = MaterialTheme.typography.titleSmall)
        Text(
            text = displayed,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (truncated) {
            Text(
                text = if (expanded) stringResource(R.string.drug_info_show_less)
                       else stringResource(R.string.drug_info_read_more),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clickable { expanded = !expanded }
                    .padding(vertical = 4.dp),
            )
        }
    }
}

private const val DRUG_INFO_PREVIEW_CHARS = 400

@Composable
private fun DrugInfoCard(labelPreview: LabelPreviewState, modifier: Modifier = Modifier) {
    var expanded by rememberSaveable { mutableStateOf(false) }

    when (labelPreview) {
        is LabelPreviewState.Loading -> DrugInfoLoadingRow(modifier)
        is LabelPreviewState.Available -> {
            val hasAnyData = labelPreview.boxedWarningText != null ||
                labelPreview.sideEffectsText != null ||
                labelPreview.interactionsText != null ||
                labelPreview.warningsText != null ||
                labelPreview.precautionsText != null ||
                labelPreview.foodEffectText != null
            if (!hasAnyData) return
            val expandLabel = if (expanded)
                stringResource(R.string.cd_drug_info_collapse)
            else
                stringResource(R.string.cd_drug_info_expand)
            OutlinedCard(modifier = modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(onClickLabel = expandLabel) { expanded = !expanded },
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(R.string.section_drug_info),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Icon(
                            imageVector = if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (expanded) {
                        Spacer(Modifier.height(8.dp))
                        DrugInfoPreviewSections(preview = labelPreview)
                    }
                }
            }
        }
        else -> {}
    }
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
        MedicationForm.CAPLET to R.string.form_caplet,
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

// ── Auto-managed toggle ───────────────────────────────────────────────────

@Composable
private fun AutoManagedToggleRow(
    isAutoManaged: Boolean,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val cd = if (isAutoManaged) {
        stringResource(R.string.cd_auto_managed_toggle_on)
    } else {
        stringResource(R.string.cd_auto_managed_toggle_off)
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
                imageVector = Icons.Outlined.SettingsSuggest,
                contentDescription = null,
                tint = if (isAutoManaged) MaterialTheme.colorScheme.primary
                       else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .size(20.dp)
                    .padding(top = 2.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.auto_managed_toggle_label),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = stringResource(R.string.auto_managed_toggle_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            Switch(
                checked = isAutoManaged,
                onCheckedChange = onToggle,
            )
        }
    }
}

// ── Miss window field ─────────────────────────────────────────────────────

/**
 * Numeric input for per-medication miss-window grace period (spec §2.6, G1).
 * Valid range: 5–360 minutes. Default 60. TalkBack-labelled; ≥48dp hit target via
 * OutlinedTextField's default height.
 */
@Composable
private fun MissWindowField(
    value: String,
    isError: Boolean,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val cd = stringResource(R.string.cd_miss_window_field)
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(stringResource(R.string.miss_window_label)) },
        supportingText = {
            Text(
                text = if (isError) stringResource(R.string.miss_window_error)
                       else stringResource(R.string.miss_window_helper),
                color = if (isError) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        isError = isError,
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Number,
            imeAction = ImeAction.Next,
        ),
        modifier = modifier
            .fillMaxWidth()
            .semantics { contentDescription = cd },
    )
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


// ContactAutocompleteField and PharmacyAutocompleteField live in
// com.beryndil.pharos.contacts.ui.ContactPickerFields — imported above.

// ── Substitution link section (V1.3-F2) ──────────────────────────────────

/**
 * "Substitute for" search field + optional note (Law 3 reference framing).
 *
 * The user types a drug name (e.g. "Flomax") and gets live results from the local drug DB.
 * Selecting a result commits it; typing away without selecting commits the typed text as-is.
 * This lets the user record "tamsulosin substituted for Flomax" even when Flomax is not in
 * their regimen.
 *
 * Accessibility (§8): both fields meet ≥48dp via OutlinedTextField defaults.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SubstituteSection(
    search: String,
    committedName: String?,
    searchResults: List<DrugSearchResult>,
    brandSuggestionsAvailable: Boolean,
    onSearchChanged: (String) -> Unit,
    onSelected: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Local expanded state — the user opens the dropdown by tapping the field.
    var menuExpanded by remember { mutableStateOf(false) }
    val showMenu = menuExpanded && searchResults.isNotEmpty()
    // Show a dropdown arrow when there are pre-populated brand options ready to pick.
    // brandSuggestionsAvailable stays true even when the field is pre-filled (openFDA race),
    // so the user can still open the dropdown to pick a different brand.
    val hasBrandSuggestions = searchResults.isNotEmpty() && (search.isEmpty() || brandSuggestionsAvailable)

    fun commitAndClose(name: String?) {
        menuExpanded = false
        onSelected(name)
    }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ExposedDropdownMenuBox(
            expanded = showMenu,
            onExpandedChange = { newExpanded ->
                menuExpanded = newExpanded
                // Auto-commit typed free-text when user collapses the menu (e.g. back press).
                if (!newExpanded && search.isNotBlank()) onSelected(search.trim())
            },
        ) {
            OutlinedTextField(
                value = search,
                onValueChange = { newValue ->
                    menuExpanded = true
                    onSearchChanged(newValue)
                },
                label = { Text(stringResource(R.string.label_substitute_for)) },
                placeholder = { Text(stringResource(R.string.substitute_for_placeholder)) },
                supportingText = {
                    Text(
                        text = stringResource(R.string.substitute_for_helper),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        overflow = TextOverflow.Clip,
                    )
                },
                trailingIcon = when {
                    search.isNotEmpty() -> {
                        { IconButton(onClick = { commitAndClose(null) }) { Icon(Icons.Outlined.Close, null) } }
                    }
                    hasBrandSuggestions -> {
                        { ExposedDropdownMenuDefaults.TrailingIcon(expanded = menuExpanded) }
                    }
                    else -> null
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Words,
                    imeAction = ImeAction.Next,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(MenuAnchorType.PrimaryEditable),
            )
            ExposedDropdownMenu(
                expanded = showMenu,
                onDismissRequest = {
                    menuExpanded = false
                    // Auto-commit typed free-text on outside-tap dismiss.
                    if (search.isNotBlank()) onSelected(search.trim())
                },
            ) {
                // Header shown only for pre-populated brand suggestions (search field is blank).
                if (hasBrandSuggestions) {
                    DropdownMenuItem(
                        text = {
                            Text(
                                stringResource(R.string.label_brand_suggestions),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                        onClick = {},
                        enabled = false,
                        contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                    )
                    HorizontalDivider()
                }
                searchResults.forEach { drug ->
                    DropdownMenuItem(
                        text = {
                            if (hasBrandSuggestions) {
                                Text(drug.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            } else {
                                Column {
                                    Text(drug.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        text = drug.tty,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        },
                        onClick = { commitAndClose(drug.name) },
                        contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                    )
                }
            }
        }

    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CombinedPrescriptionSection(
    availableMeds: List<MedicationEntity>,
    selectedMedId: String?,
    combinedDisplayStrength: String,
    onMedSelected: (String?) -> Unit,
    onStrengthChanged: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (availableMeds.isEmpty()) return

    val selectedName = availableMeds.firstOrNull { it.id == selectedMedId }?.name
    var expanded by remember { mutableStateOf(false) }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionHeader(stringResource(R.string.section_combined_prescription))

        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = it },
        ) {
            OutlinedTextField(
                value = selectedName ?: "",
                onValueChange = {},
                readOnly = true,
                label = { Text(stringResource(R.string.label_combined_partner)) },
                placeholder = { Text(stringResource(R.string.combined_partner_placeholder)) },
                supportingText = {
                    Text(
                        text = stringResource(R.string.combined_partner_helper),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                trailingIcon = if (selectedMedId != null) {
                    {
                        IconButton(onClick = { onMedSelected(null) }) {
                            Icon(Icons.Outlined.Close, contentDescription = null)
                        }
                    }
                } else {
                    { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) }
                },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(MenuAnchorType.PrimaryNotEditable),
            )
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
            ) {
                availableMeds.forEach { med ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(med.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    text = med.strength,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        },
                        onClick = {
                            onMedSelected(med.id)
                            expanded = false
                        },
                        contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                    )
                }
            }
        }

        if (selectedMedId != null) {
            OutlinedTextField(
                value = combinedDisplayStrength,
                onValueChange = onStrengthChanged,
                label = { Text(stringResource(R.string.label_combined_strength)) },
                placeholder = { Text(stringResource(R.string.combined_strength_placeholder)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    imeAction = ImeAction.Next,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
