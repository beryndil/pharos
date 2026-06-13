package com.beryndil.pharos.contacts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.beryndil.pharos.R
import com.beryndil.pharos.data.regimen.entity.PharmacyEntity
import com.beryndil.pharos.data.regimen.entity.PrescriberEntity

/**
 * Manage screen for saved prescribers and pharmacies (spec V1.3-F1).
 *
 * Users can edit name/phone or delete entries. Deleting a saved contact does NOT alter
 * medications that already reference it — those retain their stored name/phone strings.
 *
 * Reachable from the medication list overflow menu → Settings.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SavedContactsScreen(
    uiState: SavedContactsUiState,
    onEvent: (ContactsEvent) -> Unit,
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.saved_contacts_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.btn_back),
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        LazyColumn(
            contentPadding = PaddingValues(
                top = innerPadding.calculateTopPadding(),
                bottom = innerPadding.calculateBottomPadding() + 16.dp,
            ),
        ) {
            // ── Prescribers ───────────────────────────────────────────────
            item {
                SectionHeader(stringResource(R.string.saved_contacts_section_prescribers))
            }
            if (uiState.prescribers.isEmpty()) {
                item {
                    EmptyContactsHint(stringResource(R.string.saved_contacts_empty_prescribers))
                }
            } else {
                items(uiState.prescribers, key = { it.id }) { prescriber ->
                    ContactRow(
                        name = prescriber.name,
                        phone = prescriber.phone,
                        onEdit = { onEvent(ContactsEvent.EditPrescriberRequested(prescriber)) },
                        onDelete = { onEvent(ContactsEvent.DeleteRequested(prescriber.id, isPharmacy = false)) },
                    )
                }
            }

            item { HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp)) }

            // ── Pharmacies ────────────────────────────────────────────────
            item {
                SectionHeader(stringResource(R.string.saved_contacts_section_pharmacies))
            }
            if (uiState.pharmacies.isEmpty()) {
                item {
                    EmptyContactsHint(stringResource(R.string.saved_contacts_empty_pharmacies))
                }
            } else {
                items(uiState.pharmacies, key = { it.id }) { pharmacy ->
                    ContactRow(
                        name = pharmacy.name,
                        phone = pharmacy.phone,
                        onEdit = { onEvent(ContactsEvent.EditPharmacyRequested(pharmacy)) },
                        onDelete = { onEvent(ContactsEvent.DeleteRequested(pharmacy.id, isPharmacy = true)) },
                    )
                }
            }
        }
    }

    // ── Dialogs ───────────────────────────────────────────────────────────
    when (val dialog = uiState.dialog) {
        is ContactEditDialog.EditPrescriber -> EditContactDialog(
            title = stringResource(R.string.saved_contacts_edit_prescriber),
            name = dialog.currentName,
            phone = dialog.currentPhone,
            onNameChange = { onEvent(ContactsEvent.EditNameChanged(it)) },
            onPhoneChange = { onEvent(ContactsEvent.EditPhoneChanged(it)) },
            onConfirm = { onEvent(ContactsEvent.SaveEditConfirmed) },
            onDismiss = { onEvent(ContactsEvent.DialogDismissed) },
        )
        is ContactEditDialog.EditPharmacy -> EditContactDialog(
            title = stringResource(R.string.saved_contacts_edit_pharmacy),
            name = dialog.currentName,
            phone = dialog.currentPhone,
            onNameChange = { onEvent(ContactsEvent.EditNameChanged(it)) },
            onPhoneChange = { onEvent(ContactsEvent.EditPhoneChanged(it)) },
            onConfirm = { onEvent(ContactsEvent.SaveEditConfirmed) },
            onDismiss = { onEvent(ContactsEvent.DialogDismissed) },
        )
        is ContactEditDialog.ConfirmDelete -> DeleteConfirmDialog(
            onConfirm = { onEvent(ContactsEvent.DeleteConfirmed) },
            onDismiss = { onEvent(ContactsEvent.DialogDismissed) },
        )
        ContactEditDialog.None -> Unit
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun EmptyContactsHint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

@Composable
private fun ContactRow(
    name: String,
    phone: String?,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(name) },
        supportingContent = if (!phone.isNullOrBlank()) {
            { Text(phone, style = MaterialTheme.typography.bodySmall) }
        } else {
            null
        },
        trailingContent = {
            androidx.compose.foundation.layout.Row {
                val editCd = stringResource(R.string.saved_contacts_edit_cd, name)
                val deleteCd = stringResource(R.string.saved_contacts_delete_cd, name)
                IconButton(
                    onClick = onEdit,
                    modifier = Modifier.semantics { contentDescription = editCd },
                ) {
                    Icon(Icons.Outlined.Edit, contentDescription = null)
                }
                IconButton(
                    onClick = onDelete,
                    modifier = Modifier.semantics { contentDescription = deleteCd },
                ) {
                    Icon(Icons.Outlined.Delete, contentDescription = null)
                }
            }
        },
    )
}

@Composable
private fun EditContactDialog(
    title: String,
    name: String,
    phone: String,
    onNameChange: (String) -> Unit,
    onPhoneChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = onNameChange,
                    label = { Text(stringResource(R.string.saved_contacts_field_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = phone,
                    onValueChange = onPhoneChange,
                    label = { Text(stringResource(R.string.saved_contacts_field_phone)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.btn_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.btn_cancel)) }
        },
    )
}

@Composable
private fun DeleteConfirmDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.saved_contacts_delete_title)) },
        text = { Text(stringResource(R.string.saved_contacts_delete_body)) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.btn_delete)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.btn_cancel)) }
        },
    )
}
