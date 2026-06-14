package com.beryndil.pharos.medication.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.OutlinedButton
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Medication
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.beryndil.pharos.R
import com.beryndil.pharos.data.regimen.entity.MedicationEntity
import com.beryndil.pharos.data.regimen.entity.MedicationForm
import com.beryndil.pharos.data.regimen.entity.MedicationStatus
import com.beryndil.pharos.medication.MedicationListEvent
import com.beryndil.pharos.medication.MedicationListUiState

/**
 * Shows all active medications with a FAB to add a new one.
 *
 * Stateless — receives state and callbacks; the ViewModel lives in the nav graph.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MedicationListScreen(
    uiState: MedicationListUiState,
    onAddMedication: () -> Unit,
    onMedicationClicked: (String) -> Unit,
    onRefillClicked: (String) -> Unit,
    onDrugReferenceClicked: (String) -> Unit,
    /** Navigate to the Saved Contacts manage screen (V1.3-F1). */
    onOpenSavedContacts: () -> Unit = {},
    /** Navigate to backup/restore screen — used in the post-wipe empty-state card. */
    onOpenBackup: () -> Unit,
    /** Navigate to the Legal screen (spec §4.2 — Terms, Privacy, Medical Disclaimer). */
    onOpenLegal: () -> Unit = {},
    /** Navigate to the Settings screen (A5-S1 — theme, text size, about/legal). */
    onOpenSettings: () -> Unit = {},
    onEvent: (MedicationListEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    com.beryndil.pharos.core.ui.SecureWindow()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    var globalMenuExpanded by remember { mutableStateOf(false) }

    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text(stringResource(R.string.screen_medications)) },
                actions = {
                    Box {
                        IconButton(onClick = { globalMenuExpanded = true }) {
                            Icon(
                                imageVector = Icons.Default.MoreVert,
                                contentDescription = stringResource(R.string.cd_open_menu),
                            )
                        }
                        DropdownMenu(
                            expanded = globalMenuExpanded,
                            onDismissRequest = { globalMenuExpanded = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.screen_settings)) },
                                onClick = {
                                    globalMenuExpanded = false
                                    onOpenSettings()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.action_saved_contacts)) },
                                onClick = {
                                    globalMenuExpanded = false
                                    onOpenSavedContacts()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.action_legal)) },
                                onClick = {
                                    globalMenuExpanded = false
                                    onOpenLegal()
                                },
                            )
                        }
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
        floatingActionButton = {
            val addMedCd = stringResource(R.string.cd_add_medication)
            FloatingActionButton(
                onClick = onAddMedication,
                modifier = Modifier.semantics { contentDescription = addMedCd },
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = null,
                )
            }
        },
    ) { innerPadding ->
        if (uiState.medications.isEmpty()) {
            EmptyMedicationsState(
                onOpenBackup = onOpenBackup,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            ) {
                items(
                    items = uiState.medications,
                    key = { it.id },
                ) { med ->
                    MedicationListItem(
                        medication = med,
                        substituteForMedName = med.substituteForDrugName,
                        hasSubstituteName = null,
                        onClick = { onMedicationClicked(med.id) },
                        onRefillClicked = { onRefillClicked(med.id) },
                        onDrugReferenceClicked = { onDrugReferenceClicked(med.id) },
                        onEvent = onEvent,
                    )
                    HorizontalDivider(modifier = Modifier.padding(start = 16.dp))
                }
                item { Spacer(modifier = Modifier.height(80.dp)) }
            }
        }
    }

    if (uiState.pendingDeleteMedId != null) {
        AlertDialog(
            onDismissRequest = { onEvent(MedicationListEvent.CancelDelete) },
            title = { Text(stringResource(R.string.dialog_delete_med_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.dialog_delete_med_body,
                        uiState.pendingDeleteMedName ?: "",
                    ),
                )
            },
            confirmButton = {
                Button(
                    onClick = { onEvent(MedicationListEvent.ConfirmDelete) },
                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                    ),
                ) {
                    Text(stringResource(R.string.action_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { onEvent(MedicationListEvent.CancelDelete) }) {
                    Text(stringResource(R.string.btn_cancel))
                }
            },
        )
    }
}

@Composable
private fun MedicationListItem(
    medication: MedicationEntity,
    /** Name of the med this medication substitutes for, or null if no link (V1.3-F2). */
    substituteForMedName: String?,
    /** Name of a med that has declared this one as its substitute — for back-reference (V1.3-F2). */
    hasSubstituteName: String?,
    onClick: () -> Unit,
    onRefillClicked: () -> Unit,
    onDrugReferenceClicked: () -> Unit,
    onEvent: (MedicationListEvent) -> Unit,
) {
    val formLabel = medication.form.toFormLabel()
    val itemDesc = stringResource(R.string.cd_med_item, medication.name, medication.strength, formLabel)
    var menuExpanded by remember { mutableStateOf(false) }
    val optionsCd = stringResource(R.string.cd_med_options, medication.name)

    val status = runCatching { MedicationStatus.valueOf(medication.status) }
        .getOrElse { MedicationStatus.ACTIVE }

    val dimmed = status == MedicationStatus.ENDED
    val contentAlpha = if (dimmed) 0.5f else 1f
    val dimmedColor = MaterialTheme.colorScheme.onSurface.copy(alpha = contentAlpha)
    val dimmedVariantColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = contentAlpha)

    ListItem(
        headlineContent = {
            Column {
                Text(
                    text = medication.name,
                    style = MaterialTheme.typography.bodyLarge,
                    color = dimmedColor,
                )
                if (dimmed) {
                    Text(
                        text = stringResource(R.string.med_status_ended),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    )
                }
            }
        },
        supportingContent = {
            Column {
                Text(
                    text = stringResource(R.string.med_strength_form, medication.strength, formLabel),
                    style = MaterialTheme.typography.bodyMedium,
                    color = dimmedVariantColor,
                )
                if (substituteForMedName != null) {
                    Text(
                        text = stringResource(R.string.med_substitute_for, substituteForMedName),
                        style = MaterialTheme.typography.bodySmall,
                        color = dimmedVariantColor,
                    )
                }
                if (hasSubstituteName != null) {
                    Text(
                        text = stringResource(R.string.med_has_substitute, hasSubstituteName),
                        style = MaterialTheme.typography.bodySmall,
                        color = dimmedVariantColor,
                    )
                }
            }
        },
        trailingContent = {
            Box {
                IconButton(
                    onClick = { menuExpanded = true },
                    modifier = Modifier.semantics { contentDescription = optionsCd },
                ) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = null,
                    )
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                ) {
                    // Drug reference — available for all non-free-text medications regardless of status.
                    if (!medication.isFreeText) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.action_drug_reference)) },
                            onClick = {
                                menuExpanded = false
                                onDrugReferenceClicked()
                            },
                        )
                    }
                    // Track refill — available for all non-ended medications
                    if (status != MedicationStatus.ENDED) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.action_track_refill)) },
                            onClick = {
                                menuExpanded = false
                                onRefillClicked()
                            },
                        )
                    }
                    if (status == MedicationStatus.ACTIVE) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.action_pause)) },
                            onClick = {
                                menuExpanded = false
                                onEvent(MedicationListEvent.PauseMedication(medication.id))
                            },
                        )
                    }
                    if (status == MedicationStatus.PAUSED) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.action_resume)) },
                            onClick = {
                                menuExpanded = false
                                onEvent(MedicationListEvent.ResumeMedication(medication.id))
                            },
                        )
                    }
                    if (status != MedicationStatus.ENDED) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.action_end)) },
                            onClick = {
                                menuExpanded = false
                                onEvent(MedicationListEvent.EndMedication(medication.id))
                            },
                        )
                    }
                    if (status == MedicationStatus.ENDED) {
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = stringResource(R.string.action_delete),
                                    color = MaterialTheme.colorScheme.error,
                                )
                            },
                            onClick = {
                                menuExpanded = false
                                onEvent(MedicationListEvent.RequestDelete(medication.id, medication.name))
                            },
                        )
                    }
                }
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                onClickLabel = medication.name,
                onClick = onClick,
            )
            .semantics { contentDescription = itemDesc },
    )
}

@Composable
private fun EmptyMedicationsState(
    onOpenBackup: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(horizontal = 32.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.Medication,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(R.string.empty_medications_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(R.string.empty_medications_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // Post-wipe / first-install restore offer (spec §2.12).
            // Shown only on the empty state so returning users aren't distracted by it.
            Spacer(Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.backup_restore_offer_title),
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = stringResource(R.string.backup_restore_offer_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = onOpenBackup) {
                Text(stringResource(R.string.backup_restore_offer_button))
            }
        }
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
