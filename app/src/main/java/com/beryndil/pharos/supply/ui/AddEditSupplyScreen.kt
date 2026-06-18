package com.beryndil.pharos.supply.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.beryndil.pharos.R
import com.beryndil.pharos.contacts.ui.ContactAutocompleteField
import com.beryndil.pharos.contacts.ui.PharmacyAutocompleteField
import com.beryndil.pharos.supply.AddEditSupplyEvent
import com.beryndil.pharos.supply.AddEditSupplyUiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddEditSupplyScreen(
    uiState: AddEditSupplyUiState,
    onEvent: (AddEditSupplyEvent) -> Unit,
    onDone: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    com.beryndil.pharos.core.ui.SecureWindow()

    LaunchedEffect(uiState.saved) {
        if (uiState.saved) onDone()
    }

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(uiState.error) {
        uiState.error?.let { snackbarHostState.showSnackbar(it) }
    }

    val backCd = stringResource(R.string.cd_back_button)
    val title = if (uiState.isEditing) {
        stringResource(R.string.screen_edit_supply)
    } else {
        stringResource(R.string.screen_add_supply)
    }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(title) },
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
            )
        },
    ) { innerPadding ->
        if (uiState.loading) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                OutlinedTextField(
                    value = uiState.name,
                    onValueChange = { onEvent(AddEditSupplyEvent.NameChanged(it)) },
                    label = { Text(stringResource(R.string.label_supply_name)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Words,
                        imeAction = ImeAction.Next,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                OutlinedTextField(
                    value = uiState.unit,
                    onValueChange = { onEvent(AddEditSupplyEvent.UnitChanged(it)) },
                    label = { Text(stringResource(R.string.label_supply_unit)) },
                    placeholder = { Text(stringResource(R.string.label_supply_unit_hint)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                        imeAction = ImeAction.Next,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (!uiState.isEditing) {
                item {
                    OutlinedTextField(
                        value = uiState.initialCount,
                        onValueChange = { onEvent(AddEditSupplyEvent.InitialCountChanged(it)) },
                        label = { Text(stringResource(R.string.label_supply_initial_count)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Number,
                            imeAction = ImeAction.Next,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            item {
                OutlinedTextField(
                    value = uiState.lowThreshold,
                    onValueChange = { onEvent(AddEditSupplyEvent.LowThresholdChanged(it)) },
                    label = { Text(stringResource(R.string.label_supply_low_threshold)) },
                    placeholder = { Text(stringResource(R.string.label_supply_low_threshold_hint)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Next,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                Text(
                    text = stringResource(R.string.supply_section_prescriber),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            item {
                ContactAutocompleteField(
                    value = uiState.prescriberName,
                    onValueChange = { onEvent(AddEditSupplyEvent.PrescriberNameChanged(it)) },
                    label = stringResource(R.string.label_prescriber),
                    suggestions = uiState.prescriberSuggestions,
                    onSuggestionPicked = { onEvent(AddEditSupplyEvent.PrescriberSuggestionPicked(it)) },
                    practiceValue = uiState.prescriberPractice,
                    onPracticeChange = { onEvent(AddEditSupplyEvent.PrescriberPracticeChanged(it)) },
                    practiceLabel = stringResource(R.string.label_prescriber_practice),
                    phoneValue = uiState.prescriberPhone,
                    onPhoneChange = { onEvent(AddEditSupplyEvent.PrescriberPhoneChanged(it)) },
                    phoneLabel = stringResource(R.string.label_prescriber_phone),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                Text(
                    text = stringResource(R.string.supply_section_pharmacy),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            item {
                PharmacyAutocompleteField(
                    value = uiState.pharmacyName,
                    onValueChange = { onEvent(AddEditSupplyEvent.PharmacyNameChanged(it)) },
                    label = stringResource(R.string.label_pharmacy),
                    suggestions = uiState.pharmacySuggestions,
                    onSuggestionPicked = { onEvent(AddEditSupplyEvent.PharmacySuggestionPicked(it)) },
                    phoneValue = uiState.pharmacyPhone,
                    onPhoneChange = { onEvent(AddEditSupplyEvent.PharmacyPhoneChanged(it)) },
                    phoneLabel = stringResource(R.string.label_pharmacy_phone),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                OutlinedTextField(
                    value = uiState.notes,
                    onValueChange = { onEvent(AddEditSupplyEvent.NotesChanged(it)) },
                    label = { Text(stringResource(R.string.label_notes)) },
                    singleLine = false,
                    minLines = 2,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Sentences,
                        imeAction = ImeAction.Default,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { onEvent(AddEditSupplyEvent.Save) },
                    enabled = !uiState.isSaving,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (uiState.isSaving) {
                        CircularProgressIndicator(
                            modifier = Modifier
                                .padding(horizontal = 8.dp)
                                .size(18.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Text(stringResource(R.string.action_save))
                    }
                }
            }
        }
    }
}
