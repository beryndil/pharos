package com.beryndil.pharos.supply.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.beryndil.pharos.R
import com.beryndil.pharos.data.regimen.entity.SupplyEventType
import com.beryndil.pharos.data.regimen.entity.SupplyRecordEntity
import com.beryndil.pharos.supply.SupplyDetailEvent
import com.beryndil.pharos.supply.SupplyDetailUiState
import com.beryndil.pharos.supply.SupplyDialogState
import com.beryndil.pharos.supply.SupplySummary
import com.beryndil.pharos.ui.util.formatPhoneDisplay
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SupplyDetailScreen(
    uiState: SupplyDetailUiState,
    onEvent: (SupplyDetailEvent) -> Unit,
    onEdit: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    com.beryndil.pharos.core.ui.SecureWindow()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    val backCd = stringResource(R.string.cd_back_button)
    var menuExpanded by remember { mutableStateOf(false) }

    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text(uiState.summary?.supplyName ?: "") },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.semantics { contentDescription = backCd },
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = null,
                        )
                    }
                },
                actions = {
                    Box {
                        IconButton(onClick = { menuExpanded = true }) {
                            Icon(
                                imageVector = Icons.Default.MoreVert,
                                contentDescription = stringResource(R.string.cd_open_menu),
                            )
                        }
                        DropdownMenu(
                            expanded = menuExpanded,
                            onDismissRequest = { menuExpanded = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.action_edit)) },
                                onClick = { menuExpanded = false; onEdit() },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.supply_action_adjust)) },
                                onClick = {
                                    menuExpanded = false
                                    onEvent(SupplyDetailEvent.ShowAdjustDialog)
                                },
                            )
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        stringResource(R.string.supply_action_end),
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                },
                                onClick = {
                                    menuExpanded = false
                                    onEvent(SupplyDetailEvent.ShowEndDialog)
                                },
                            )
                        }
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

        val summary = uiState.summary ?: return@Scaffold

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 80.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item(key = "status_card") {
                SupplyStatusCard(summary = summary)
            }

            item(key = "actions") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    FilledTonalButton(
                        onClick = { onEvent(SupplyDetailEvent.ShowLogUsageDialog) },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(stringResource(R.string.supply_action_log_usage))
                    }
                    Button(
                        onClick = { onEvent(SupplyDetailEvent.ShowLogRestockDialog) },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(stringResource(R.string.supply_action_log_restock))
                    }
                }
            }

            if (summary.prescriberName != null || summary.pharmacyName != null) {
                item(key = "contacts") {
                    ContactsCard(summary = summary)
                }
            }

            if (uiState.history.isNotEmpty()) {
                item(key = "history_header") {
                    Text(
                        text = stringResource(R.string.supply_section_history).uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                items(uiState.history, key = { it.id }) { record ->
                    SupplyRecordItem(record = record, unit = summary.unit)
                    HorizontalDivider()
                }
            }
        }
    }

    // ── Dialogs ───────────────────────────────────────────────────────────────
    when (uiState.dialogState) {
        SupplyDialogState.LogUsage -> QuantityDialog(
            title = stringResource(R.string.supply_dialog_log_usage_title),
            confirmLabel = stringResource(R.string.supply_dialog_log_usage_confirm),
            onConfirm = { qty, notes ->
                onEvent(SupplyDetailEvent.ConfirmLogUsage(qty, notes))
            },
            onDismiss = { onEvent(SupplyDetailEvent.DismissDialog) },
        )
        SupplyDialogState.LogRestock -> QuantityDialog(
            title = stringResource(R.string.supply_dialog_log_restock_title),
            confirmLabel = stringResource(R.string.supply_dialog_log_restock_confirm),
            onConfirm = { qty, notes ->
                onEvent(SupplyDetailEvent.ConfirmLogRestock(qty, notes))
            },
            onDismiss = { onEvent(SupplyDetailEvent.DismissDialog) },
        )
        SupplyDialogState.Adjust -> {
            val currentQty = uiState.summary?.quantityOnHand ?: 0
            AdjustDialog(
                currentQuantity = currentQty,
                onConfirm = { qty, notes ->
                    onEvent(SupplyDetailEvent.ConfirmAdjust(qty, notes))
                },
                onDismiss = { onEvent(SupplyDetailEvent.DismissDialog) },
            )
        }
        SupplyDialogState.ConfirmEnd -> AlertDialog(
            onDismissRequest = { onEvent(SupplyDetailEvent.DismissDialog) },
            title = { Text(stringResource(R.string.supply_dialog_end_title)) },
            text = { Text(stringResource(R.string.supply_dialog_end_body)) },
            confirmButton = {
                Button(
                    onClick = { onEvent(SupplyDetailEvent.ConfirmEnd) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                    ),
                ) {
                    Text(stringResource(R.string.supply_action_end))
                }
            },
            dismissButton = {
                TextButton(onClick = { onEvent(SupplyDetailEvent.DismissDialog) }) {
                    Text(stringResource(R.string.btn_cancel))
                }
            },
        )
        SupplyDialogState.None -> Unit
    }
}

@Composable
private fun SupplyStatusCard(summary: SupplySummary) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        val qty = summary.quantityOnHand
        val quantityText = when {
            summary.noRecordYet -> stringResource(R.string.supply_no_count_yet)
            qty != null -> "$qty ${summary.unit}"
            else -> ""
        }
        Text(
            text = quantityText,
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.Bold,
            color = if (summary.isLowSupply) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurface
            },
        )
        if (summary.isLowSupply) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.WarningAmber,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.error,
                )
                Text(
                    text = stringResource(R.string.supply_low_warning),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        if (summary.notes != null) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = summary.notes,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ContactsCard(summary: SupplySummary) {
    val context = LocalContext.current
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (summary.prescriberName != null) {
            Text(
                text = summary.prescriberName,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            if (summary.prescriberPhone != null) {
                Text(
                    text = formatPhoneDisplay(summary.prescriberPhone),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable {
                        val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:${summary.prescriberPhone}"))
                        context.startActivity(intent)
                    },
                )
            }
        }
        if (summary.pharmacyName != null) {
            if (summary.prescriberName != null) HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
            Text(
                text = summary.pharmacyName,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            if (summary.pharmacyPhone != null) {
                Text(
                    text = formatPhoneDisplay(summary.pharmacyPhone),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable {
                        val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:${summary.pharmacyPhone}"))
                        context.startActivity(intent)
                    },
                )
            }
        }
    }
}

@Composable
private fun SupplyRecordItem(record: SupplyRecordEntity, unit: String) {
    val typeLabel = when (
        runCatching { SupplyEventType.valueOf(record.eventType) }.getOrNull()
    ) {
        SupplyEventType.INITIAL -> stringResource(R.string.supply_event_initial)
        SupplyEventType.RESTOCK -> stringResource(R.string.supply_event_restock)
        SupplyEventType.USAGE -> stringResource(R.string.supply_event_usage)
        SupplyEventType.ADJUSTMENT -> stringResource(R.string.supply_event_adjustment)
        null -> record.eventType
    }
    val deltaText = when {
        record.quantityDelta > 0 -> "+${record.quantityDelta} $unit"
        else -> "${record.quantityDelta} $unit"
    }
    val dateStr = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
        .format(Date(record.createdAtEpochMs))

    ListItem(
        headlineContent = { Text(typeLabel) },
        supportingContent = {
            Column {
                Text("$deltaText → ${record.quantityAfter} $unit")
                if (record.notes != null) Text(record.notes)
            }
        },
        trailingContent = {
            Text(
                text = dateStr,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
    )
}

@Composable
private fun QuantityDialog(
    title: String,
    confirmLabel: String,
    onConfirm: (Int, String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var quantityText by rememberSaveable { mutableStateOf("") }
    var notes by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = quantityText,
                    onValueChange = { quantityText = it.filter { c -> c.isDigit() } },
                    label = { Text(stringResource(R.string.supply_dialog_quantity_label)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text(stringResource(R.string.label_notes)) },
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val qty = quantityText.toIntOrNull()
                    if (qty != null && qty > 0) {
                        onConfirm(qty, notes.trim().ifBlank { null })
                    }
                },
            ) { Text(confirmLabel) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.btn_cancel)) }
        },
    )
}

@Composable
private fun AdjustDialog(
    currentQuantity: Int,
    onConfirm: (Int, String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var quantityText by rememberSaveable { mutableStateOf(currentQuantity.toString()) }
    var notes by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.supply_dialog_adjust_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.supply_dialog_adjust_body))
                OutlinedTextField(
                    value = quantityText,
                    onValueChange = { quantityText = it.filter { c -> c.isDigit() } },
                    label = { Text(stringResource(R.string.supply_dialog_quantity_label)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text(stringResource(R.string.label_notes)) },
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val qty = quantityText.toIntOrNull()
                    if (qty != null && qty >= 0) {
                        onConfirm(qty, notes.trim().ifBlank { null })
                    }
                },
            ) { Text(stringResource(R.string.supply_action_adjust)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.btn_cancel)) }
        },
    )
}
