package com.beryndil.pharos.refill.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.beryndil.pharos.R
import com.beryndil.pharos.data.regimen.entity.RefillEventType
import com.beryndil.pharos.data.regimen.entity.RefillRecordEntity
import com.beryndil.pharos.refill.RefillDialogState
import com.beryndil.pharos.refill.RefillEvent
import com.beryndil.pharos.refill.RefillSummary
import com.beryndil.pharos.refill.RefillUiState
import java.text.DateFormat
import java.util.Date

/**
 * Refill tracking screen for a single medication (spec §2.9).
 *
 * Shows supply status, days until empty, refill-by date, pharmacy phone, a request-refill
 * checklist (UI-only, not persisted), and append-only refill history.
 *
 * Design (DESIGN.md): calm, content-first, Apple-grade. One primary action (set count or
 * picked up refill). Warnings pair icon + text (never color alone — Law 10).
 *
 * Zero-supply (spec §2.9): when supply is zero or unrecorded, the UI shows a plain notice
 * ("Dose reminders continue as scheduled") and keeps all reminder-independent controls
 * accessible. The RefillRepository never touches the dose state machine.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RefillDetailScreen(
    uiState: RefillUiState,
    onEvent: (RefillEvent) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())

    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = {
                    Text(
                        text = uiState.summary?.medicationName
                            ?: stringResource(R.string.screen_refill),
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = stringResource(R.string.cd_back_button),
                        )
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { innerPadding ->
        if (uiState.loading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }

        val summary = uiState.summary
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 32.dp),
        ) {
            // ── Supply status ────────────────────────────────────────────
            item {
                SectionHeader(stringResource(R.string.refill_section_supply))
            }
            item {
                SupplyStatusCard(summary = summary, onEvent = onEvent)
            }

            // ── PRN notice ───────────────────────────────────────────────
            if (summary?.isPrn == true) {
                item {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.refill_prn_no_runout),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
            }

            // ── Request refill checklist ─────────────────────────────────
            item { Spacer(Modifier.height(24.dp)) }
            item {
                SectionHeader(stringResource(R.string.refill_section_request_checklist))
            }
            item {
                RequestRefillChecklist()
            }

            // ── Actions ──────────────────────────────────────────────────
            item { Spacer(Modifier.height(24.dp)) }
            item {
                RefillActions(summary = summary, onEvent = onEvent)
            }

            // ── Refill history ────────────────────────────────────────────
            if (uiState.history.isNotEmpty()) {
                item { Spacer(Modifier.height(24.dp)) }
                item {
                    SectionHeader(stringResource(R.string.refill_section_history))
                }
                items(uiState.history, key = { it.id }) { record ->
                    RefillHistoryItem(record = record)
                    HorizontalDivider(modifier = Modifier.padding(start = 16.dp))
                }
            }
        }
    }

    // ── Dialogs ──────────────────────────────────────────────────────────────
    when (uiState.dialogState) {
        RefillDialogState.SetInitialCount -> SetCountDialog(
            title = stringResource(R.string.refill_dialog_set_count_title),
            currentUnit = uiState.summary?.quantityUnit,
            onDismiss = { onEvent(RefillEvent.DismissDialog) },
            onConfirm = { qty, unit, phone ->
                onEvent(RefillEvent.ConfirmSetInitialCount(qty, unit, phone))
            },
        )

        RefillDialogState.PickupRefill -> PickupDialog(
            currentUnit = uiState.summary?.quantityUnit,
            onDismiss = { onEvent(RefillEvent.DismissDialog) },
            onConfirm = { qty, unit, phone, notes ->
                onEvent(RefillEvent.ConfirmPickup(qty, unit, phone, notes, refillByEpochMs = null))
            },
        )

        RefillDialogState.PartialFill -> PartialFillDialog(
            currentUnit = uiState.summary?.quantityUnit,
            onDismiss = { onEvent(RefillEvent.DismissDialog) },
            onConfirm = { addQty, unit, notes ->
                onEvent(RefillEvent.ConfirmPartialFill(addQty, unit, notes))
            },
        )

        RefillDialogState.ConfirmStopBeforeEmpty -> StopBeforeEmptyDialog(
            onDismiss = { onEvent(RefillEvent.DismissDialog) },
            onConfirm = { onEvent(RefillEvent.ConfirmStopBeforeEmpty) },
        )

        RefillDialogState.None,
        RefillDialogState.SetRefillByDate,
        -> Unit
    }
}

// ── Sub-composables ───────────────────────────────────────────────────────────

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp),
    )
}

@Composable
private fun SupplyStatusCard(
    summary: RefillSummary?,
    onEvent: (RefillEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        tonalElevation = 1.dp,
        shape = MaterialTheme.shapes.medium,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (summary == null || summary.noSupplyOnRecord) {
                // No refill records yet
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Inventory2,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                    Text(
                        text = stringResource(R.string.refill_no_supply_on_record),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                // On-hand count
                LabelValueRow(
                    label = stringResource(R.string.refill_quantity_label),
                    value = stringResource(
                        R.string.refill_quantity_value,
                        summary.quantityOnHand ?: 0,
                        summary.quantityUnit.orEmpty(),
                    ),
                )

                // Days until empty (not for PRN)
                if (!summary.isPrn) {
                    val daysText = when {
                        summary.daysUntilEmpty == null -> "—"
                        summary.daysUntilEmpty == 0 -> stringResource(R.string.refill_days_empty)
                        else -> pluralStringResource(
                            R.plurals.refill_days_count,
                            summary.daysUntilEmpty,
                            summary.daysUntilEmpty,
                        )
                    }
                    LabelValueRow(
                        label = stringResource(R.string.refill_days_until_empty_label),
                        value = daysText,
                    )
                }

                // Refill-by date
                val refillByText = if (summary.refillByEpochMs != null) {
                    DateFormat.getDateInstance(DateFormat.MEDIUM)
                        .format(Date(summary.refillByEpochMs))
                } else {
                    stringResource(R.string.refill_refill_by_not_set)
                }
                LabelValueRow(
                    label = stringResource(R.string.refill_refill_by_label),
                    value = refillByText,
                )

                // Pharmacy phone
                if (!summary.pharmacyPhone.isNullOrBlank()) {
                    LabelValueRow(
                        label = stringResource(R.string.refill_pharmacy_label),
                        value = summary.pharmacyPhone,
                    )
                }

                // Low-supply warning (icon + text, never color alone — Law 10)
                if (summary.isLowSupply) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.Top,
                        modifier = Modifier.semantics(mergeDescendants = true) {},
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.WarningAmber,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(20.dp),
                        )
                        Text(
                            text = if (summary.supplyIsZero) {
                                stringResource(R.string.refill_zero_supply_body)
                            } else {
                                stringResource(
                                    R.string.refill_low_supply_body,
                                    summary.daysUntilEmpty ?: 0,
                                )
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LabelValueRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun RequestRefillChecklist(modifier: Modifier = Modifier) {
    val items = listOf(
        stringResource(R.string.refill_checklist_prescription),
        stringResource(R.string.refill_checklist_insurance),
        stringResource(R.string.refill_checklist_pharmacy_phone),
    )
    val checked = remember { mutableStateListOf(false, false, false) }

    Column(modifier = modifier.padding(horizontal = 16.dp)) {
        items.forEachIndexed { index, item ->
            val itemCd = stringResource(R.string.refill_checklist_item_cd, item)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics(mergeDescendants = true) {
                        contentDescription = itemCd
                    },
            ) {
                Checkbox(
                    checked = checked[index],
                    onCheckedChange = { checked[index] = it },
                )
                Text(
                    text = item,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(start = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun RefillActions(
    summary: RefillSummary?,
    onEvent: (RefillEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (summary == null || summary.noSupplyOnRecord) {
            // No records yet: primary action is to set the initial count
            Button(
                onClick = { onEvent(RefillEvent.ShowSetInitialCountDialog) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.refill_action_set_count))
            }
        } else {
            // Primary: picked up refill
            Button(
                onClick = { onEvent(RefillEvent.ShowPickupDialog) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.refill_action_pickup))
            }
            // Secondary actions
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                FilledTonalButton(
                    onClick = { onEvent(RefillEvent.ShowPartialFillDialog) },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.refill_action_partial))
                }
                OutlinedButton(
                    onClick = { onEvent(RefillEvent.ShowStopBeforeEmptyDialog) },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.refill_action_stopped))
                }
            }
        }
    }
}

@Composable
private fun RefillHistoryItem(record: RefillRecordEntity) {
    val typeLabel = when (
        runCatching { RefillEventType.valueOf(record.type) }.getOrNull()
    ) {
        RefillEventType.INITIAL -> stringResource(R.string.refill_event_initial)
        RefillEventType.REFILL_PICKUP -> stringResource(R.string.refill_event_pickup)
        RefillEventType.ADJUSTMENT, null -> stringResource(R.string.refill_event_adjustment)
    }
    val dateText = DateFormat.getDateInstance(DateFormat.MEDIUM)
        .format(Date(record.createdAtEpochMs))
    val quantityText = stringResource(
        R.string.refill_history_quantity,
        record.quantityOnHand,
        record.quantityUnit,
    )

    ListItem(
        headlineContent = { Text(typeLabel) },
        supportingContent = {
            Text(
                text = stringResource(R.string.refill_history_item_summary, quantityText, dateText),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        trailingContent = if (!record.notes.isNullOrBlank()) {
            { Text(record.notes, style = MaterialTheme.typography.bodySmall) }
        } else null,
    )
}

// ── Dialogs ───────────────────────────────────────────────────────────────────

@Composable
private fun SetCountDialog(
    title: String,
    currentUnit: String?,
    onDismiss: () -> Unit,
    onConfirm: (quantity: Int, unit: String, pharmacyPhone: String?) -> Unit,
) {
    var quantityText by rememberSaveable { mutableStateOf("") }
    var unitText by rememberSaveable { mutableStateOf(currentUnit.orEmpty()) }
    var phoneText by rememberSaveable { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = quantityText,
                    onValueChange = { quantityText = it },
                    label = { Text(stringResource(R.string.refill_dialog_quantity_label)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = unitText,
                    onValueChange = { unitText = it },
                    label = { Text(stringResource(R.string.refill_dialog_unit_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = phoneText,
                    onValueChange = { phoneText = it },
                    label = { Text(stringResource(R.string.refill_dialog_pharmacy_phone_label)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val qty = quantityText.trim().toIntOrNull() ?: return@TextButton
                    val unit = unitText.trim().ifBlank { "tablets" }
                    val phone = phoneText.trim().ifBlank { null }
                    onConfirm(qty, unit, phone)
                },
            ) {
                Text(stringResource(R.string.refill_dialog_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.btn_cancel)) }
        },
    )
}

@Composable
private fun PickupDialog(
    currentUnit: String?,
    onDismiss: () -> Unit,
    onConfirm: (newQuantity: Int, unit: String, pharmacyPhone: String?, notes: String?) -> Unit,
) {
    var quantityText by rememberSaveable { mutableStateOf("") }
    var unitText by rememberSaveable { mutableStateOf(currentUnit.orEmpty()) }
    var phoneText by rememberSaveable { mutableStateOf("") }
    var notesText by rememberSaveable { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.refill_dialog_pickup_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = quantityText,
                    onValueChange = { quantityText = it },
                    label = { Text(stringResource(R.string.refill_dialog_quantity_label)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = unitText,
                    onValueChange = { unitText = it },
                    label = { Text(stringResource(R.string.refill_dialog_unit_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = phoneText,
                    onValueChange = { phoneText = it },
                    label = { Text(stringResource(R.string.refill_dialog_pharmacy_phone_label)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = notesText,
                    onValueChange = { notesText = it },
                    label = { Text(stringResource(R.string.refill_dialog_notes_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val qty = quantityText.trim().toIntOrNull() ?: return@TextButton
                    val unit = unitText.trim().ifBlank { "tablets" }
                    val phone = phoneText.trim().ifBlank { null }
                    val notes = notesText.trim().ifBlank { null }
                    onConfirm(qty, unit, phone, notes)
                },
            ) {
                Text(stringResource(R.string.refill_dialog_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.btn_cancel)) }
        },
    )
}

@Composable
private fun PartialFillDialog(
    currentUnit: String?,
    onDismiss: () -> Unit,
    onConfirm: (additionalQuantity: Int, unit: String, notes: String?) -> Unit,
) {
    var quantityText by rememberSaveable { mutableStateOf("") }
    var unitText by rememberSaveable { mutableStateOf(currentUnit.orEmpty()) }
    var notesText by rememberSaveable { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.refill_dialog_partial_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = quantityText,
                    onValueChange = { quantityText = it },
                    label = { Text(stringResource(R.string.refill_dialog_additional_quantity_label)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = unitText,
                    onValueChange = { unitText = it },
                    label = { Text(stringResource(R.string.refill_dialog_unit_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = notesText,
                    onValueChange = { notesText = it },
                    label = { Text(stringResource(R.string.refill_dialog_notes_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val qty = quantityText.trim().toIntOrNull() ?: return@TextButton
                    val unit = unitText.trim().ifBlank { "tablets" }
                    val notes = notesText.trim().ifBlank { null }
                    onConfirm(qty, unit, notes)
                },
            ) {
                Text(stringResource(R.string.refill_dialog_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.btn_cancel)) }
        },
    )
}

@Composable
private fun StopBeforeEmptyDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.refill_dialog_stopped_title)) },
        text = { Text(stringResource(R.string.refill_dialog_stopped_body)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.refill_dialog_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.btn_cancel)) }
        },
    )
}
