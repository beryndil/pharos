package com.beryndil.pharos.onboarding.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.Alarm
import androidx.compose.material.icons.outlined.Battery3Bar
import androidx.compose.material.icons.outlined.CheckCircleOutline
import androidx.compose.material.icons.outlined.Contacts
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material.icons.outlined.SettingsSuggest
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.beryndil.pharos.R
import com.beryndil.pharos.onboarding.OnboardingEvent
import com.beryndil.pharos.onboarding.OnboardingStep
import com.beryndil.pharos.onboarding.OnboardingUiState

/**
 * The first-launch permission priming flow (spec §2.14, Standards §3,§4,§8, DESIGN.md).
 *
 * Design decisions (DECISIONS.md S6-A3):
 *  - Each step shows the rationale BEFORE any system dialog or settings page is opened.
 *  - System dialogs / settings pages are opened by explicit user action, never silently.
 *  - Permission steps have a "Skip" path; nothing in onboarding is a hard wall (spec §2.14).
 *  - Completion is signalled via [onDone] when [OnboardingUiState.isComplete] is true.
 *  - Scroll is enabled per step in case the device has a very small screen / large font size.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnboardingScreen(
    uiState: OnboardingUiState,
    onEvent: (OnboardingEvent) -> Unit,
    onDone: () -> Unit,
    onOpenLegal: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    com.beryndil.pharos.core.ui.ClearWindowSecurity()
    // Navigate away as soon as completion is persisted.
    LaunchedEffect(uiState.isComplete) {
        if (uiState.isComplete) onDone()
    }

    val progressLabel = stringResource(
        R.string.onboarding_step_progress,
        uiState.currentStepIndex + 1,
        uiState.totalSteps,
    )

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = progressLabel,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.semantics {
                            contentDescription = progressLabel
                        },
                    )
                },
                navigationIcon = {
                    // Back affordance: available from step 2 onward; no-op/hidden on the first step.
                    if (uiState.currentStepIndex > 0) {
                        IconButton(onClick = { onEvent(OnboardingEvent.PreviousStep) }) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.onboarding_back),
                            )
                        }
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState()),
        ) {
            when (uiState.currentStep) {
                OnboardingStep.WELCOME -> WelcomeStep(
                    onNext = { onEvent(OnboardingEvent.NextStep) },
                    onOpenLegal = onOpenLegal,
                )
                OnboardingStep.NOTIFICATION_PERMISSION -> NotificationPermissionStep(
                    onNext = { onEvent(OnboardingEvent.NextStep) },
                )
                OnboardingStep.EXACT_ALARM_PERMISSION -> ExactAlarmPermissionStep(
                    onNext = { onEvent(OnboardingEvent.NextStep) },
                )
                OnboardingStep.BATTERY_OPTIMIZATION -> BatteryOptimizationStep(
                    onNext = { onEvent(OnboardingEvent.NextStep) },
                )
                OnboardingStep.AUTO_START -> AutoStartStep(
                    oemName = uiState.oemName,
                    onNext = { onEvent(OnboardingEvent.NextStep) },
                )
                OnboardingStep.TEST_REMINDER -> TestReminderStep(
                    testSent = uiState.testReminderSent,
                    onSendTest = { onEvent(OnboardingEvent.SendTestReminder) },
                    onNext = { onEvent(OnboardingEvent.NextStep) },
                )
                OnboardingStep.PROFILE -> ProfileStep(
                    name = uiState.profileName,
                    dob = uiState.profileDob,
                    phone = uiState.profilePhone,
                    allergies = uiState.profileAllergies,
                    onNameChanged = { onEvent(OnboardingEvent.ProfileNameChanged(it)) },
                    onDobChanged = { onEvent(OnboardingEvent.ProfileDobChanged(it)) },
                    onPhoneChanged = { onEvent(OnboardingEvent.ProfilePhoneChanged(it)) },
                    onAllergiesChanged = { onEvent(OnboardingEvent.ProfileAllergiesChanged(it)) },
                    onContinue = { onEvent(OnboardingEvent.NextStep) },
                )
                OnboardingStep.CONTACTS -> ContactsStep(
                    prescriberName = uiState.prescriberName,
                    prescriberPhone = uiState.prescriberPhone,
                    prescriberPractice = uiState.prescriberPractice,
                    pharmacyName = uiState.pharmacyName,
                    pharmacyPhone = uiState.pharmacyPhone,
                    onPrescriberNameChanged = { onEvent(OnboardingEvent.PrescriberNameChanged(it)) },
                    onPrescriberPhoneChanged = { onEvent(OnboardingEvent.PrescriberPhoneChanged(it)) },
                    onPrescriberPracticeChanged = { onEvent(OnboardingEvent.PrescriberPracticeChanged(it)) },
                    onPharmacyNameChanged = { onEvent(OnboardingEvent.PharmacyNameChanged(it)) },
                    onPharmacyPhoneChanged = { onEvent(OnboardingEvent.PharmacyPhoneChanged(it)) },
                    onComplete = { onEvent(OnboardingEvent.CompleteOnboarding) },
                )
            }
        }
    }
}

// ── Shared layout ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun StepContent(
    icon: ImageVector,
    headline: String,
    body: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(modifier = Modifier.height(32.dp))
        Icon(
            imageVector = icon,
            contentDescription = null, // decorative — headline and body carry the meaning
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = headline,
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = body,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(32.dp))
        content()
        Spacer(modifier = Modifier.height(32.dp))
    }
}

// ── Individual step composables ───────────────────────────────────────────────────────────────

@Composable
private fun WelcomeStep(onNext: () -> Unit, onOpenLegal: () -> Unit) {
    StepContent(
        icon = Icons.Outlined.CheckCircleOutline,
        headline = stringResource(R.string.onboarding_welcome_headline),
        body = stringResource(R.string.onboarding_welcome_body),
    ) {
        // Medical disclaimer + legal link shown before the user proceeds (spec §4.2, Law 3).
        Text(
            text = stringResource(R.string.onboarding_legal_disclaimer_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(8.dp))
        TextButton(
            onClick = onOpenLegal,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
        ) {
            Text(stringResource(R.string.onboarding_legal_link))
        }
        Spacer(modifier = Modifier.height(8.dp))
        Button(
            onClick = onNext,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
        ) {
            Text(stringResource(R.string.onboarding_btn_get_started))
        }
    }
}

@Composable
private fun NotificationPermissionStep(onNext: () -> Unit) {
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { _ ->
        // Advance regardless of grant outcome: onboarding is not a permission wall (spec §2.14).
        onNext()
    }

    StepContent(
        icon = Icons.Outlined.NotificationsNone,
        headline = stringResource(R.string.onboarding_notification_headline),
        body = stringResource(R.string.onboarding_notification_body),
    ) {
        Button(
            onClick = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    onNext()
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
        ) {
            Text(stringResource(R.string.onboarding_btn_allow_notifications))
        }
        Spacer(modifier = Modifier.height(8.dp))
        TextButton(
            onClick = onNext,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
        ) {
            Text(stringResource(R.string.onboarding_btn_skip))
        }
    }
}

@Composable
private fun ExactAlarmPermissionStep(onNext: () -> Unit) {
    val context = LocalContext.current

    StepContent(
        icon = Icons.Outlined.Alarm,
        headline = stringResource(R.string.onboarding_exact_alarm_headline),
        body = stringResource(R.string.onboarding_exact_alarm_body),
    ) {
        Button(
            onClick = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                        .setData(Uri.parse("package:${context.packageName}"))
                    context.startActivity(intent)
                }
                // Do not advance: user returns from settings and taps Next/Skip to proceed.
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
        ) {
            Text(stringResource(R.string.onboarding_btn_open_alarm_settings))
        }
        Spacer(modifier = Modifier.height(8.dp))
        TextButton(
            onClick = onNext,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
        ) {
            Text(stringResource(R.string.onboarding_btn_next))
        }
    }
}

@Composable
private fun BatteryOptimizationStep(onNext: () -> Unit) {
    val context = LocalContext.current

    StepContent(
        icon = Icons.Outlined.Battery3Bar,
        headline = stringResource(R.string.onboarding_battery_headline),
        body = stringResource(R.string.onboarding_battery_body),
    ) {
        Text(
            text = stringResource(R.string.onboarding_battery_destination),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(16.dp))
        Button(
            onClick = {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(Uri.parse("package:${context.packageName}"))
                context.startActivity(intent)
                // Do not advance: user returns from settings and taps Next/Skip.
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
        ) {
            Text(stringResource(R.string.onboarding_btn_open_battery_settings))
        }
        Spacer(modifier = Modifier.height(8.dp))
        TextButton(
            onClick = onNext,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
        ) {
            Text(stringResource(R.string.onboarding_btn_next))
        }
    }
}

@Composable
private fun AutoStartStep(oemName: String, onNext: () -> Unit) {
    val context = LocalContext.current
    val dkmaUrl = stringResource(R.string.onboarding_autostart_dkma_url)

    val bodyText = when (oemName.lowercase(java.util.Locale.ROOT)) {
        "xiaomi" -> stringResource(R.string.onboarding_autostart_body_xiaomi)
        "oppo" -> stringResource(R.string.onboarding_autostart_body_oppo)
        "vivo" -> stringResource(R.string.onboarding_autostart_body_vivo)
        "honor" -> stringResource(R.string.onboarding_autostart_body_honor)
        else -> stringResource(R.string.onboarding_autostart_body_generic)
    }

    StepContent(
        icon = Icons.Outlined.SettingsSuggest,
        headline = stringResource(R.string.onboarding_autostart_headline),
        body = bodyText,
    ) {
        TextButton(
            onClick = {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(dkmaUrl))
                context.startActivity(intent)
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
        ) {
            Text(stringResource(R.string.onboarding_autostart_dkma_label))
        }
        Spacer(modifier = Modifier.height(8.dp))
        Button(
            onClick = onNext,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
        ) {
            Text(stringResource(R.string.onboarding_btn_next))
        }
    }
}

@Composable
private fun TestReminderStep(
    testSent: Boolean,
    onSendTest: () -> Unit,
    onNext: () -> Unit,
) {
    StepContent(
        icon = Icons.Outlined.NotificationsNone,
        headline = stringResource(R.string.onboarding_test_headline),
        body = stringResource(R.string.onboarding_test_body),
    ) {
        if (testSent) {
            Text(
                text = stringResource(R.string.onboarding_test_sent_confirmation),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(16.dp))
        } else {
            Button(
                onClick = onSendTest,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
            ) {
                Text(stringResource(R.string.onboarding_btn_send_test))
            }
            Spacer(modifier = Modifier.height(8.dp))
        }
        TextButton(
            onClick = onNext,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
        ) {
            Text(stringResource(R.string.onboarding_btn_next))
        }
    }
}

@Composable
private fun ProfileStep(
    name: String,
    dob: String,
    phone: String,
    allergies: String,
    onNameChanged: (String) -> Unit,
    onDobChanged: (String) -> Unit,
    onPhoneChanged: (String) -> Unit,
    onAllergiesChanged: (String) -> Unit,
    onContinue: () -> Unit,
) {
    StepContent(
        icon = Icons.Outlined.AccountCircle,
        headline = stringResource(R.string.onboarding_profile_title),
        body = stringResource(R.string.onboarding_profile_body),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = name,
                onValueChange = onNameChanged,
                label = { Text(stringResource(R.string.profile_field_name)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = dob,
                onValueChange = onDobChanged,
                label = { Text(stringResource(R.string.profile_field_dob)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = phone,
                onValueChange = onPhoneChanged,
                label = { Text(stringResource(R.string.profile_field_phone)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = allergies,
                onValueChange = onAllergiesChanged,
                label = { Text(stringResource(R.string.profile_field_allergies)) },
                minLines = 2,
                maxLines = 4,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.onboarding_profile_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(16.dp))
        Button(
            onClick = onContinue,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
        ) {
            Text(stringResource(R.string.btn_continue))
        }
        Spacer(modifier = Modifier.height(8.dp))
        TextButton(
            onClick = onContinue,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
        ) {
            Text(stringResource(R.string.onboarding_btn_skip))
        }
    }
}

@Composable
private fun ContactsStep(
    prescriberName: String,
    prescriberPhone: String,
    prescriberPractice: String,
    pharmacyName: String,
    pharmacyPhone: String,
    onPrescriberNameChanged: (String) -> Unit,
    onPrescriberPhoneChanged: (String) -> Unit,
    onPrescriberPracticeChanged: (String) -> Unit,
    onPharmacyNameChanged: (String) -> Unit,
    onPharmacyPhoneChanged: (String) -> Unit,
    onComplete: () -> Unit,
) {
    StepContent(
        icon = Icons.Outlined.Contacts,
        headline = stringResource(R.string.onboarding_contacts_title),
        body = stringResource(R.string.onboarding_contacts_body),
    ) {
        // ── Prescriber ────────────────────────────────────────────────────────
        Text(
            text = stringResource(R.string.onboarding_contacts_prescriber),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(8.dp))
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = prescriberName,
                onValueChange = onPrescriberNameChanged,
                label = { Text(stringResource(R.string.saved_contacts_field_name)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = prescriberPractice,
                onValueChange = onPrescriberPracticeChanged,
                label = { Text(stringResource(R.string.saved_contacts_field_practice)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = prescriberPhone,
                onValueChange = onPrescriberPhoneChanged,
                label = { Text(stringResource(R.string.saved_contacts_field_phone)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Spacer(modifier = Modifier.height(20.dp))
        HorizontalDivider()
        Spacer(modifier = Modifier.height(20.dp))

        // ── Pharmacy ──────────────────────────────────────────────────────────
        Text(
            text = stringResource(R.string.onboarding_contacts_pharmacy),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(8.dp))
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = pharmacyName,
                onValueChange = onPharmacyNameChanged,
                label = { Text(stringResource(R.string.saved_contacts_field_name)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = pharmacyPhone,
                onValueChange = onPharmacyPhoneChanged,
                label = { Text(stringResource(R.string.saved_contacts_field_phone)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Spacer(modifier = Modifier.height(24.dp))
        Button(
            onClick = onComplete,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
        ) {
            Text(stringResource(R.string.onboarding_btn_all_set))
        }
        Spacer(modifier = Modifier.height(8.dp))
        TextButton(
            onClick = onComplete,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
        ) {
            Text(stringResource(R.string.onboarding_btn_skip))
        }
    }
}
