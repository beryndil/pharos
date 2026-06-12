package com.beryndil.pharos.reliability.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.text.format.DateFormat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircleOutline
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.beryndil.pharos.R
import com.beryndil.pharos.alarm.AlarmMode
import com.beryndil.pharos.reliability.DashboardPermissionItem
import com.beryndil.pharos.reliability.FixAction
import com.beryndil.pharos.reliability.ItemStatus
import com.beryndil.pharos.reliability.ReliabilityDashboardUiState
import java.util.Date
import androidx.compose.material.icons.automirrored.outlined.ArrowBack

/**
 * The on-device reliability dashboard (spec §2.13, Law 6).
 *
 * Shows, in plain language, the state of every permission and system setting that affects
 * reminder delivery. Each "risky" item shows both an icon and a text description (never
 * color alone — Law 10 / Standards §8) and links to its fix (a system settings page or URL).
 *
 * This screen carries no PHI; it surfaces system-level reliability facts only (Laws 4 & 5).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReliabilityDashboardScreen(
    uiState: ReliabilityDashboardUiState,
    onBack: () -> Unit,
    onTestCriticalAlert: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.screen_reliability_dashboard)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = stringResource(R.string.cd_back_button),
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp),
        ) {
            // ── Permissions section ───────────────────────────────────────────────
            item {
                SectionHeader(stringResource(R.string.reliability_section_permissions))
            }
            item {
                PermissionRow(
                    label = stringResource(R.string.reliability_label_exact_alarm),
                    item = uiState.exactAlarm,
                    okDesc = stringResource(R.string.reliability_exact_alarm_ok),
                    riskyDesc = stringResource(R.string.reliability_exact_alarm_risky),
                    onFix = { handleFixAction(uiState.exactAlarm.fixAction, context) },
                )
            }
            item {
                PermissionRow(
                    label = stringResource(R.string.reliability_label_battery),
                    item = uiState.batteryOptimization,
                    okDesc = stringResource(R.string.reliability_battery_ok),
                    riskyDesc = stringResource(R.string.reliability_battery_risky),
                    onFix = { handleFixAction(uiState.batteryOptimization.fixAction, context) },
                )
            }
            item {
                PermissionRow(
                    label = stringResource(R.string.reliability_label_autostart),
                    item = uiState.backgroundAutoStart,
                    okDesc = stringResource(R.string.reliability_autostart_ok),
                    riskyDesc = stringResource(R.string.reliability_autostart_risky),
                    onFix = { handleFixAction(uiState.backgroundAutoStart.fixAction, context) },
                )
            }
            item {
                PermissionRow(
                    label = stringResource(R.string.reliability_label_notification),
                    item = uiState.notification,
                    okDesc = stringResource(R.string.reliability_notification_ok),
                    riskyDesc = stringResource(R.string.reliability_notification_risky),
                    onFix = { handleFixAction(uiState.notification.fixAction, context) },
                )
            }
            item {
                PermissionRow(
                    label = stringResource(R.string.reliability_label_fullscreen),
                    item = uiState.fullScreenIntent,
                    okDesc = stringResource(R.string.reliability_fullscreen_ok),
                    riskyDesc = stringResource(R.string.reliability_fullscreen_risky),
                    onFix = { handleFixAction(uiState.fullScreenIntent.fixAction, context) },
                )
            }
            item {
                PermissionRow(
                    label = stringResource(R.string.reliability_label_dnd),
                    item = uiState.dndAccess,
                    okDesc = stringResource(R.string.reliability_dnd_ok),
                    riskyDesc = stringResource(R.string.reliability_dnd_risky),
                    onFix = { handleFixAction(uiState.dndAccess.fixAction, context) },
                )
            }

            // ── Critical meds section ─────────────────────────────────────────────
            item {
                Spacer(modifier = Modifier.height(8.dp))
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                SectionHeader(stringResource(R.string.reliability_section_critical))
            }
            item {
                if (uiState.criticalMedNames.isEmpty()) {
                    InfoRow(
                        label = stringResource(R.string.reliability_critical_none_label),
                        value = stringResource(R.string.reliability_critical_none),
                    )
                } else {
                    InfoRow(
                        label = stringResource(R.string.reliability_critical_list_label),
                        value = uiState.criticalMedNames.joinToString(", "),
                    )
                }
            }
            item {
                androidx.compose.foundation.layout.Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                ) {
                    androidx.compose.material3.OutlinedButton(
                        onClick = onTestCriticalAlert,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.reliability_btn_test_critical))
                    }
                }
            }

            // ── Alarm history section ─────────────────────────────────────────────
            item {
                Spacer(modifier = Modifier.height(8.dp))
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                SectionHeader(stringResource(R.string.reliability_section_alarm_history))
            }
            item {
                InfoRow(
                    label = stringResource(R.string.reliability_last_alarm_label),
                    value = uiState.lastAlarmFiredEpochMs?.let { formatTimestamp(it, context) }
                        ?: stringResource(R.string.reliability_last_alarm_never),
                )
            }
            item {
                InfoRow(
                    label = stringResource(R.string.reliability_next_alarm_label),
                    value = uiState.nextAlarmEpochMs?.let { formatTimestamp(it, context) }
                        ?: stringResource(R.string.reliability_next_alarm_none),
                )
            }
            item {
                InfoRow(
                    label = stringResource(R.string.reliability_alarm_mode_label),
                    value = when (uiState.alarmMode) {
                        AlarmMode.EXACT -> stringResource(R.string.reliability_alarm_mode_exact)
                        AlarmMode.WINDOWED_FALLBACK -> stringResource(R.string.reliability_alarm_mode_windowed)
                        null -> stringResource(R.string.reliability_alarm_mode_unknown)
                    },
                )
            }

            // ── System integration section ────────────────────────────────────────
            item {
                Spacer(modifier = Modifier.height(8.dp))
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                SectionHeader(stringResource(R.string.reliability_section_system))
            }
            item {
                val bootValue = uiState.bootReceiverLastTrigger?.let { trigger ->
                    val timeText = uiState.bootReceiverLastTriggerEpochMs
                        ?.let { formatTimestamp(it, context) } ?: ""
                    if (timeText.isNotEmpty()) {
                        stringResource(R.string.reliability_boot_receiver_ok, trigger)
                    } else {
                        stringResource(R.string.reliability_boot_receiver_ok, trigger)
                    }
                } ?: stringResource(R.string.reliability_boot_receiver_unknown)

                InfoRow(
                    label = stringResource(R.string.reliability_boot_receiver_label),
                    value = bootValue,
                )
            }

            // ── Drug database section ─────────────────────────────────────────────
            item {
                Spacer(modifier = Modifier.height(8.dp))
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                SectionHeader(stringResource(R.string.reliability_section_drug_db))
            }
            item {
                InfoRow(
                    label = stringResource(R.string.reliability_drug_db_version_label),
                    value = uiState.drugDbVersion
                        ?: stringResource(R.string.reliability_drug_db_version_bundled),
                )
            }
            item {
                InfoRow(
                    label = stringResource(R.string.reliability_drug_db_updated_label),
                    value = uiState.drugDbUpdatedAtEpochMs?.let { formatTimestamp(it, context) }
                        ?: stringResource(R.string.reliability_drug_db_updated_never),
                )
            }
        }
    }
}

// ── Sub-composables ───────────────────────────────────────────────────────────────────────────

@Composable
private fun SectionHeader(title: String, modifier: Modifier = Modifier) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(start = 16.dp, top = 20.dp, end = 16.dp, bottom = 4.dp),
    )
}

/**
 * A permission status row: icon (OK = check / RISKY = warning) + label + description.
 * Icon + text pair — never color alone (Law 10). Fix button shown when [item.fixAction] != null.
 */
@Composable
private fun PermissionRow(
    label: String,
    item: DashboardPermissionItem,
    okDesc: String,
    riskyDesc: String,
    onFix: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isRisky = item.status == ItemStatus.RISKY
    val desc = if (isRisky) riskyDesc else okDesc
    val rowCd = stringResource(R.string.cd_reliability_item_desc, label, desc)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .semantics { contentDescription = rowCd },
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = if (isRisky) Icons.Outlined.WarningAmber else Icons.Outlined.CheckCircleOutline,
            contentDescription = null, // row-level semantics carry the meaning
            modifier = Modifier
                .size(24.dp)
                .padding(top = 2.dp),
            tint = if (isRisky) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.primary
            },
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = desc,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        if (isRisky && item.fixAction != null) {
            val fixCd = stringResource(R.string.cd_reliability_fix, label)
            TextButton(
                onClick = onFix,
                modifier = Modifier.semantics { contentDescription = fixCd },
            ) {
                Text(stringResource(R.string.reliability_btn_fix))
            }
        }
    }
}

@Composable
private fun InfoRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.45f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier
                .weight(0.55f)
                .padding(start = 8.dp),
        )
    }
}

// ── Fix-action dispatch ───────────────────────────────────────────────────────────────────────

private fun handleFixAction(action: FixAction?, context: android.content.Context) {
    if (action == null) return
    when (action) {
        FixAction.ExactAlarmSettings -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                    .setData(Uri.parse("package:${context.packageName}"))
                context.startActivity(intent)
            }
        }
        FixAction.BatterySettings -> {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                .setData(Uri.parse("package:${context.packageName}"))
            context.startActivity(intent)
        }
        FixAction.NotificationSettings -> {
            val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            context.startActivity(intent)
        }
        FixAction.FullScreenIntentSettings -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val intent = Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT)
                    .setData(Uri.parse("package:${context.packageName}"))
                context.startActivity(intent)
            }
        }
        FixAction.DndPolicySettings -> {
            context.startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
        }
        is FixAction.OpenUrl -> {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(action.url))
            context.startActivity(intent)
        }
    }
}

// ── Locale-aware timestamp formatting ─────────────────────────────────────────────────────────

/**
 * Formats an epoch-ms timestamp as "HH:MM · date" using the device's locale-aware time and date
 * formats (Standards §7: no hardcoded date/time format strings).
 */
private fun formatTimestamp(epochMs: Long, context: android.content.Context): String {
    val date = Date(epochMs)
    val timeStr = DateFormat.getTimeFormat(context).format(date)
    val dateStr = DateFormat.getDateFormat(context).format(date)
    return "$timeStr · $dateStr"
}
