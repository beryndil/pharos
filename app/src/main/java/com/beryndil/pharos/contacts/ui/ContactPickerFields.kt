package com.beryndil.pharos.contacts.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.beryndil.pharos.data.regimen.entity.PharmacyEntity
import com.beryndil.pharos.data.regimen.entity.PrescriberEntity
import com.beryndil.pharos.ui.util.PhoneVisualTransformation
import com.beryndil.pharos.ui.util.formatPhoneDisplay

/**
 * Autocomplete name + optional practice + phone fields for a prescriber contact.
 *
 * Dropdown opens when [suggestions] is non-empty and closes on pick or user dismiss.
 * Picking a suggestion auto-fills name, practice, and phone from the saved entity.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactAutocompleteField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    suggestions: List<PrescriberEntity>,
    onSuggestionPicked: (PrescriberEntity) -> Unit,
    phoneValue: String,
    onPhoneChange: (String) -> Unit,
    phoneLabel: String,
    practiceValue: String = "",
    onPracticeChange: (String) -> Unit = {},
    practiceLabel: String = "",
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        var dismissed by remember(suggestions.size, suggestions.firstOrNull()?.name) {
            mutableStateOf(false)
        }
        val dropExpanded = suggestions.isNotEmpty() && !dismissed

        ExposedDropdownMenuBox(
            expanded = dropExpanded,
            onExpandedChange = { if (!it) dismissed = true },
        ) {
            OutlinedTextField(
                value = value,
                onValueChange = {
                    dismissed = false
                    onValueChange(it)
                },
                label = { Text(label) },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(MenuAnchorType.PrimaryEditable)
                    .semantics { contentDescription = label },
            )
            ExposedDropdownMenu(
                expanded = dropExpanded,
                onDismissRequest = { dismissed = true },
            ) {
                suggestions.forEach { suggestion ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(suggestion.name, style = MaterialTheme.typography.bodyMedium)
                                if (!suggestion.practice.isNullOrBlank()) {
                                    Text(
                                        suggestion.practice,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                if (!suggestion.phone.isNullOrBlank()) {
                                    Text(
                                        formatPhoneDisplay(suggestion.phone),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        },
                        onClick = { onSuggestionPicked(suggestion); dismissed = true },
                        contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                    )
                }
            }
        }
        if (practiceLabel.isNotEmpty()) {
            OutlinedTextField(
                value = practiceValue,
                onValueChange = onPracticeChange,
                label = { Text(practiceLabel) },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = practiceLabel },
            )
        }
        OutlinedTextField(
            value = phoneValue,
            onValueChange = { onPhoneChange(it.filter { c -> c.isDigit() }.take(10)) },
            label = { Text(phoneLabel) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
            visualTransformation = PhoneVisualTransformation(),
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = phoneLabel },
        )
    }
}

/**
 * Autocomplete name + phone fields for a pharmacy contact.
 *
 * Typed separately from [ContactAutocompleteField] to avoid JVM signature erasure
 * when both overloads exist in the same compilation unit.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PharmacyAutocompleteField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    suggestions: List<PharmacyEntity>,
    onSuggestionPicked: (PharmacyEntity) -> Unit,
    phoneValue: String,
    onPhoneChange: (String) -> Unit,
    phoneLabel: String,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        var dismissed by remember(suggestions.size, suggestions.firstOrNull()?.name) {
            mutableStateOf(false)
        }
        val dropExpanded = suggestions.isNotEmpty() && !dismissed

        ExposedDropdownMenuBox(
            expanded = dropExpanded,
            onExpandedChange = { if (!it) dismissed = true },
        ) {
            OutlinedTextField(
                value = value,
                onValueChange = {
                    dismissed = false
                    onValueChange(it)
                },
                label = { Text(label) },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(MenuAnchorType.PrimaryEditable)
                    .semantics { contentDescription = label },
            )
            ExposedDropdownMenu(
                expanded = dropExpanded,
                onDismissRequest = { dismissed = true },
            ) {
                suggestions.forEach { suggestion ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(suggestion.name, style = MaterialTheme.typography.bodyMedium)
                                if (!suggestion.phone.isNullOrBlank()) {
                                    Text(
                                        formatPhoneDisplay(suggestion.phone),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        },
                        onClick = { onSuggestionPicked(suggestion); dismissed = true },
                        contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                    )
                }
            }
        }
        OutlinedTextField(
            value = phoneValue,
            onValueChange = { onPhoneChange(it.filter { c -> c.isDigit() }.take(10)) },
            label = { Text(phoneLabel) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
            visualTransformation = PhoneVisualTransformation(),
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = phoneLabel },
        )
    }
}
