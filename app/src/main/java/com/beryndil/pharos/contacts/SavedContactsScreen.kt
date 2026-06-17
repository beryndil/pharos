package com.beryndil.pharos.contacts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
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
import androidx.compose.ui.Alignment
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
                            contentDescription = stringResource(R.string.cd_back_button),
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
                SectionHeader(
                    title = stringResource(R.string.saved_contacts_section_prescribers),
                    onAdd = { onEvent(ContactsEvent.AddPrescriberRequested) },
                    addCd = stringResource(R.string.saved_contacts_add_prescriber_cd),
                )
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
                        practice = prescriber.practice,
                        isDefault = prescriber.id == uiState.defaultPrescriberId,
                        onDefaultToggled = { onEvent(ContactsEvent.SetDefaultPrescriber(prescriber.id)) },
                        onEdit = { onEvent(ContactsEvent.EditPrescriberRequested(prescriber)) },
                        onDelete = { onEvent(ContactsEvent.DeleteRequested(prescriber.id, isPharmacy = false)) },
                    )
                }
            }

            item { HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp)) }

            // ── Pharmacies ────────────────────────────────────────────────
            item {
                SectionHeader(
                    title = stringResource(R.string.saved_contacts_section_pharmacies),
                    onAdd = { onEvent(ContactsEvent.AddPharmacyRequested) },
                    addCd = stringResource(R.string.saved_contacts_add_pharmacy_cd),
                )
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
                        isDefault = pharmacy.id == uiState.defaultPharmacyId,
                        onDefaultToggled = { onEvent(ContactsEvent.SetDefaultPharmacy(pharmacy.id)) },
                        onEdit = { onEvent(ContactsEvent.EditPharmacyRequested(pharmacy)) },
                        onDelete = { onEvent(ContactsEvent.DeleteRequested(pharmacy.id, isPharmacy = true)) },
                    )
                }
            }
        }
    }

    // ── Dialogs ───────────────────────────────────────────────────────────
    when (val dialog = uiState.dialog) {
        is ContactEditDialog.AddPrescriber -> ContactDialog(
            title = stringResource(R.string.saved_contacts_add_prescriber),
            name = dialog.currentName,
            phone = dialog.currentPhone,
            practice = dialog.currentPractice,
            confirmLabel = stringResource(R.string.btn_add),
            onNameChange = { onEvent(ContactsEvent.EditNameChanged(it)) },
            onPhoneChange = { onEvent(ContactsEvent.EditPhoneChanged(it)) },
            onPracticeChange = { onEvent(ContactsEvent.EditPracticeChanged(it)) },
            onConfirm = { onEvent(ContactsEvent.SaveConfirmed) },
            onDismiss = { onEvent(ContactsEvent.DialogDismissed) },
        )
        is ContactEditDialog.AddPharmacy -> ContactDialog(
            title = stringResource(R.string.saved_contacts_add_pharmacy),
            name = dialog.currentName,
            phone = dialog.currentPhone,
            confirmLabel = stringResource(R.string.btn_add),
            onNameChange = { onEvent(ContactsEvent.EditNameChanged(it)) },
            onPhoneChange = { onEvent(ContactsEvent.EditPhoneChanged(it)) },
            onConfirm = { onEvent(ContactsEvent.SaveConfirmed) },
            onDismiss = { onEvent(ContactsEvent.DialogDismissed) },
        )
        is ContactEditDialog.EditPrescriber -> ContactDialog(
            title = stringResource(R.string.saved_contacts_edit_prescriber),
            name = dialog.currentName,
            phone = dialog.currentPhone,
            practice = dialog.currentPractice,
            confirmLabel = stringResource(R.string.btn_save),
            onNameChange = { onEvent(ContactsEvent.EditNameChanged(it)) },
            onPhoneChange = { onEvent(ContactsEvent.EditPhoneChanged(it)) },
            onPracticeChange = { onEvent(ContactsEvent.EditPracticeChanged(it)) },
            onConfirm = { onEvent(ContactsEvent.SaveConfirmed) },
            onDismiss = { onEvent(ContactsEvent.DialogDismissed) },
        )
        is ContactEditDialog.EditPharmacy -> ContactDialog(
            title = stringResource(R.string.saved_contacts_edit_pharmacy),
            name = dialog.currentName,
            phone = dialog.currentPhone,
            confirmLabel = stringResource(R.string.btn_save),
            onNameChange = { onEvent(ContactsEvent.EditNameChanged(it)) },
            onPhoneChange = { onEvent(ContactsEvent.EditPhoneChanged(it)) },
            onConfirm = { onEvent(ContactsEvent.SaveConfirmed) },
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
private fun SectionHeader(title: String, onAdd: () -> Unit, addCd: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .weight(1f)
                .padding(vertical = 8.dp),
        )
        IconButton(
            onClick = onAdd,
            modifier = Modifier
                .heightIn(min = 48.dp)
                .semantics { contentDescription = addCd },
        ) {
            Icon(
                imageVector = Icons.Outlined.Add,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
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
    practice: String? = null,
    isDefault: Boolean,
    onDefaultToggled: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val supportingParts = listOfNotNull(practice?.takeIf { it.isNotBlank() }, phone?.takeIf { it.isNotBlank() })
    val checkboxCd = if (isDefault) {
        stringResource(R.string.saved_contacts_unset_default_cd, name)
    } else {
        stringResource(R.string.saved_contacts_set_default_cd, name)
    }
    ListItem(
        headlineContent = { Text(name) },
        supportingContent = if (supportingParts.isNotEmpty()) {
            { Text(supportingParts.joinToString(" · "), style = MaterialTheme.typography.bodySmall) }
        } else {
            null
        },
        leadingContent = {
            Checkbox(
                checked = isDefault,
                onCheckedChange = { onDefaultToggled() },
                modifier = Modifier.semantics { contentDescription = checkboxCd },
            )
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
private fun ContactDialog(
    title: String,
    name: String,
    phone: String,
    confirmLabel: String,
    onNameChange: (String) -> Unit,
    onPhoneChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    practice: String? = null,
    onPracticeChange: ((String) -> Unit)? = null,
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
                if (practice != null && onPracticeChange != null) {
                    OutlinedTextField(
                        value = practice,
                        onValueChange = onPracticeChange,
                        label = { Text(stringResource(R.string.saved_contacts_field_practice)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
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
            Button(onClick = onConfirm, enabled = name.isNotBlank()) {
                Text(confirmLabel)
            }
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
