package com.beryndil.pharos.settings.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CalendarToday
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import com.beryndil.pharos.R
import com.beryndil.pharos.settings.UserProfileEvent
import com.beryndil.pharos.settings.UserProfileUiState
import com.beryndil.pharos.ui.util.PhoneVisualTransformation
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UserProfileScreen(
    uiState: UserProfileUiState,
    onEvent: (UserProfileEvent) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    com.beryndil.pharos.core.ui.ClearWindowSecurity()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    val backCd = stringResource(R.string.cd_back_button)
    val snackbarHostState = remember { SnackbarHostState() }
    val savedMsg = stringResource(R.string.profile_saved)

    var showDobPicker by rememberSaveable { mutableStateOf(false) }
    val dobFormatter = remember {
        DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(Locale.getDefault())
    }

    LaunchedEffect(uiState.saved) {
        if (uiState.saved) {
            snackbarHostState.showSnackbar(savedMsg)
            onEvent(UserProfileEvent.DismissSaved)
        }
    }

    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            LargeTopAppBar(
                title = { Text(stringResource(R.string.screen_profile)) },
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
                scrollBehavior = scrollBehavior,
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.profile_description),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(4.dp))

            OutlinedTextField(
                value = uiState.name,
                onValueChange = { onEvent(UserProfileEvent.NameChanged(it)) },
                label = { Text(stringResource(R.string.profile_label_name)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )

            OutlinedTextField(
                value = uiState.dateOfBirthDate?.format(dobFormatter) ?: "",
                onValueChange = {},
                label = { Text(stringResource(R.string.profile_label_dob)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                readOnly = true,
                trailingIcon = {
                    IconButton(onClick = { showDobPicker = true }) {
                        Icon(Icons.Outlined.CalendarToday, contentDescription = stringResource(R.string.profile_label_dob))
                    }
                },
            )

            OutlinedTextField(
                value = uiState.phone,
                onValueChange = { onEvent(UserProfileEvent.PhoneChanged(it.filter { c -> c.isDigit() }.take(10))) },
                label = { Text(stringResource(R.string.profile_label_phone)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                visualTransformation = PhoneVisualTransformation(),
            )

            OutlinedTextField(
                value = uiState.address,
                onValueChange = { onEvent(UserProfileEvent.AddressChanged(it)) },
                label = { Text(stringResource(R.string.profile_label_address)) },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
                maxLines = 4,
            )

            OutlinedTextField(
                value = uiState.allergies,
                onValueChange = { onEvent(UserProfileEvent.AllergiesChanged(it)) },
                label = { Text(stringResource(R.string.profile_label_allergies)) },
                placeholder = { Text(stringResource(R.string.profile_placeholder_allergies)) },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
                maxLines = 4,
            )

            Text(
                text = stringResource(R.string.profile_section_insurance),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 4.dp),
            )

            OutlinedTextField(
                value = uiState.insuranceProvider,
                onValueChange = { onEvent(UserProfileEvent.InsuranceProviderChanged(it)) },
                label = { Text(stringResource(R.string.profile_label_insurance_provider)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )

            OutlinedTextField(
                value = uiState.insuranceMemberId,
                onValueChange = { onEvent(UserProfileEvent.InsuranceMemberIdChanged(it)) },
                label = { Text(stringResource(R.string.profile_label_insurance_member_id)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )

            Text(
                text = stringResource(R.string.profile_section_emergency_contact),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 4.dp),
            )

            OutlinedTextField(
                value = uiState.emergencyContactName,
                onValueChange = { onEvent(UserProfileEvent.EmergencyContactNameChanged(it)) },
                label = { Text(stringResource(R.string.profile_label_emergency_contact_name)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )

            OutlinedTextField(
                value = uiState.emergencyContactPhone,
                onValueChange = { onEvent(UserProfileEvent.EmergencyContactPhoneChanged(it.filter { c -> c.isDigit() }.take(10))) },
                label = { Text(stringResource(R.string.profile_label_emergency_contact_phone)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                visualTransformation = PhoneVisualTransformation(),
            )

            Spacer(Modifier.height(8.dp))

            Button(
                onClick = { onEvent(UserProfileEvent.Save) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.action_save))
            }

            Spacer(Modifier.height(16.dp))
        }
    }

    if (showDobPicker) {
        val initialMs = uiState.dateOfBirthDate
            ?.atStartOfDay(ZoneId.of("UTC"))?.toInstant()?.toEpochMilli()
        val pickerState = rememberDatePickerState(initialSelectedDateMillis = initialMs)
        DatePickerDialog(
            onDismissRequest = { showDobPicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        val ms = pickerState.selectedDateMillis
                        if (ms != null) {
                            val date = Instant.ofEpochMilli(ms).atZone(ZoneId.of("UTC")).toLocalDate()
                            onEvent(UserProfileEvent.DobDateChanged(date))
                        }
                        showDobPicker = false
                    },
                    enabled = pickerState.selectedDateMillis != null,
                ) { Text(stringResource(R.string.btn_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { showDobPicker = false }) { Text(stringResource(R.string.btn_cancel)) }
            },
        ) {
            DatePicker(state = pickerState)
        }
    }
}
