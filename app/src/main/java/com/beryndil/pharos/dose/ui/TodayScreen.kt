package com.beryndil.pharos.dose.ui

import android.content.Intent
import android.text.format.DateFormat
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ListAlt
import androidx.compose.material.icons.outlined.AddCircleOutline
import androidx.compose.material.icons.outlined.CheckCircleOutline
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.MedicalServices
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.beryndil.pharos.R
import com.beryndil.pharos.data.dose.DoseRow
import com.beryndil.pharos.data.dose.PrnMedRow
import com.beryndil.pharos.data.regimen.entity.DoseState
import java.util.Date

/**
 * Today's doses: the actionable home surface (F3 — Enriched Today).
 *
 * Layout:
 *  1. "Next up" summary (top N upcoming SCHEDULED doses across all meds).
 *  2. Quick-actions row (Email meds list / Test reminder / Reliability / Settings).
 *  3. DUE and SNOOZED dose action cards (Take / Snooze / Skip).
 *  4. SCHEDULED dose rows (no action — they appear in the "next up" summary too).
 *  5. PRN "As needed" section.
 *
 * Law 1: all quick-actions are in-app UI, never notification channels.
 * Law 4: "Email meds list" shows a confirm dialog before any data leaves the device.
 * §8 (TalkBack): all buttons have content descriptions; minimum 48dp tap targets.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodayScreen(
    uiState: TodayUiState,
    onEvent: (TodayEvent) -> Unit,
    onOpenMedications: () -> Unit,
    onOpenHistory: (String) -> Unit,
    onOpenReliability: () -> Unit,
    onOpenSettings: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    com.beryndil.pharos.core.ui.SecureWindow()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    val context = LocalContext.current

    // When the ViewModel has a ready PDF file, build the share intent and launch the chooser.
    // The FileProvider URI grants read access to the receiving email app.
    LaunchedEffect(uiState.pendingEmailFile) {
        val file = uiState.pendingEmailFile ?: return@LaunchedEffect
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file,
        )
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.email_med_list_subject))
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(
            Intent.createChooser(shareIntent, context.getString(R.string.email_med_list_chooser_title)),
        )
        onEvent(TodayEvent.EmailMedListIntentConsumed)
    }

    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text(stringResource(R.string.screen_today)) },
                actions = {
                    IconButton(onClick = onOpenMedications) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.ListAlt,
                            contentDescription = stringResource(R.string.cd_open_medications),
                        )
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(
                            imageVector = Icons.Outlined.Settings,
                            contentDescription = stringResource(R.string.cd_open_settings),
                        )
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { innerPadding ->
        val hasDoses = uiState.doses.isNotEmpty()
        val hasPrn   = uiState.prnMeds.isNotEmpty()

        if (!hasDoses && !hasPrn) {
            EmptyToday(
                onOpenMedications = onOpenMedications,
                modifier = Modifier.padding(innerPadding),
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    horizontal = 16.dp,
                    vertical = 8.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // ── Next-up summary (F3) ──────────────────────────────────────
                if (uiState.nextUp.isNotEmpty()) {
                    item {
                        NextUpSection(nextUp = uiState.nextUp)
                    }
                }

                // ── Quick-actions row (F3) ────────────────────────────────────
                item {
                    QuickActionsRow(
                        onEmailMedList = { onEvent(TodayEvent.EmailMedListRequest) },
                        onTestReminder = onOpenReliability,
                    )
                }

                // Split doses by category. SCHEDULED doses are already shown in Next Up;
                // repeating them here as non-actionable cards caused confusing duplication.
                val actionableDoses = uiState.doses.filter {
                    it.state == DoseState.DUE || it.state == DoseState.SNOOZED
                }
                val completedDoses = uiState.doses.filter {
                    it.state == DoseState.TAKEN ||
                    it.state == DoseState.SKIPPED ||
                    it.state == DoseState.MISSED
                }
                val hasActionable = actionableDoses.isNotEmpty()
                val hasCompleted  = completedDoses.isNotEmpty()

                if (hasActionable || hasCompleted) {
                    item { HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp)) }
                }

                // ── Doses needing attention (DUE / SNOOZED) ──────────────────
                if (hasActionable) {
                    item {
                        Text(
                            text = stringResource(R.string.today_section_needs_attention),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                        )
                    }
                    items(actionableDoses, key = { it.doseId }) { dose ->
                        DoseCard(
                            dose = dose,
                            onTake    = { onEvent(TodayEvent.Take(dose.doseId)) },
                            onSnooze  = { onEvent(TodayEvent.Snooze(dose.doseId)) },
                            onSkip    = { onEvent(TodayEvent.Skip(dose.doseId)) },
                            onHistory = { onOpenHistory(dose.medicationId) },
                        )
                    }
                }

                // ── Doses completed today (TAKEN / SKIPPED / MISSED) ─────────
                if (hasCompleted) {
                    if (hasActionable) {
                        item { HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp)) }
                    }
                    item {
                        Text(
                            text = stringResource(R.string.today_section_completed_today),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                        )
                    }
                    items(completedDoses, key = { it.doseId }) { dose ->
                        DoseCard(
                            dose = dose,
                            onTake    = { onEvent(TodayEvent.Take(dose.doseId)) },
                            onSnooze  = { onEvent(TodayEvent.Snooze(dose.doseId)) },
                            onSkip    = { onEvent(TodayEvent.Skip(dose.doseId)) },
                            onHistory = { onOpenHistory(dose.medicationId) },
                        )
                    }
                }

                // ── PRN (as-needed) section ───────────────────────────────────
                if (hasPrn) {
                    // Only add a divider if dose cards were actually rendered above.
                    // SCHEDULED-only days produce no cards (Next Up covers them), so no divider.
                    if (hasActionable || hasCompleted) {
                        item { HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp)) }
                    }
                    item {
                        Text(
                            text = stringResource(R.string.today_prn_section_header),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                        )
                    }
                    items(uiState.prnMeds, key = { "prn_${it.medicationId}" }) { prn ->
                        PrnMedCard(
                            prn       = prn,
                            onLogDose = { onEvent(TodayEvent.LogPrn(prn.medicationId, prn.scheduleId)) },
                            onHistory = { onOpenHistory(prn.medicationId) },
                        )
                    }
                }
            }
        }
    }

    // ── PRN daily-max advisory dialog (non-blocking, Law 3 — dose already logged) ────────────
    if (uiState.prnWarningDoseNumber != null) {
        AlertDialog(
            onDismissRequest = { onEvent(TodayEvent.DismissPrnWarning) },
            title = { Text(stringResource(R.string.prn_daily_max_warning_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.prn_daily_max_warning,
                        uiState.prnWarningDoseNumber,
                        uiState.prnWarningDailyMax,
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = { onEvent(TodayEvent.DismissPrnWarning) }) {
                    Text(stringResource(R.string.btn_ok))
                }
            },
        )
    }

    // ── F4: Law-4 confirm dialog before sharing the medication PDF ────────────────────────────
    // Shown before any health data leaves the device (Law 4 — user-initiated export only).
    if (uiState.showEmailConfirmDialog) {
        AlertDialog(
            onDismissRequest = { onEvent(TodayEvent.EmailMedListDismiss) },
            title   = { Text(stringResource(R.string.email_med_list_confirm_title)) },
            text    = { Text(stringResource(R.string.email_med_list_confirm_body)) },
            confirmButton = {
                Button(onClick = { onEvent(TodayEvent.EmailMedListConfirm) }) {
                    Text(stringResource(R.string.btn_send))
                }
            },
            dismissButton = {
                TextButton(onClick = { onEvent(TodayEvent.EmailMedListDismiss) }) {
                    Text(stringResource(R.string.btn_cancel))
                }
            },
        )
    }

    // ── F4: error dialog on PDF generation failure ────────────────────────────────────────────
    if (uiState.emailError != null) {
        AlertDialog(
            onDismissRequest = { onEvent(TodayEvent.EmailMedListErrorDismissed) },
            title   = { Text(stringResource(R.string.email_med_list_error_title)) },
            text    = { Text(uiState.emailError) },
            confirmButton = {
                TextButton(onClick = { onEvent(TodayEvent.EmailMedListErrorDismissed) }) {
                    Text(stringResource(R.string.btn_ok))
                }
            },
        )
    }
}

/**
 * "Next up" summary section (F3).
 *
 * Shows the next [MAX_NEXT_UP] upcoming SCHEDULED doses in a compact list at the top of Today
 * so the user can see what's coming without scrolling. DUE/SNOOZED are already above the fold
 * in the action cards.
 */
@Composable
private fun NextUpSection(nextUp: List<NextUpItem>) {
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    ) {
        Text(
            text  = stringResource(R.string.today_section_next_up),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        nextUp.forEach { item ->
            val timeText = DateFormat.getTimeFormat(context).format(Date(item.dueEpochMs))
            val itemCd   = stringResource(R.string.cd_next_up_item, item.medName, timeText)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .semantics(mergeDescendants = true) { contentDescription = itemCd }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Schedule,
                    contentDescription = null, // decorative — row CD above carries the meaning
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text  = item.medName,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                    )
                    Text(
                        text  = "${item.strength} · $timeText",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * Quick-actions row (F3 — in-app UI only; Law 1: never notification-channel actions).
 *
 * §8 launch-blocker: every button has a contentDescription, minimum 48dp tap target.
 */
@Composable
private fun QuickActionsRow(
    onEmailMedList: () -> Unit,
    onTestReminder: () -> Unit,
) {
    // Label for the whole row (screen-reader context)
    val rowCd = stringResource(R.string.today_quick_actions_label)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = rowCd }
            .padding(vertical = 4.dp),
    ) {
        Text(
            text  = stringResource(R.string.today_quick_actions_label),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // "Email meds list" — primary CTA; law-4 confirm dialog shown by ViewModel
            QuickActionButton(
                icon  = Icons.Outlined.Email,
                label = stringResource(R.string.today_action_email_med_list),
                cd    = stringResource(R.string.cd_email_med_list),
                onClick = onEmailMedList,
                modifier = Modifier.weight(1f),
            )
            // "Test reminder" — navigates to Reliability dashboard (existing test path)
            QuickActionButton(
                icon  = Icons.Outlined.CheckCircleOutline,
                label = stringResource(R.string.today_action_test_reminder),
                cd    = stringResource(R.string.cd_test_reminder),
                onClick = onTestReminder,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/**
 * A single icon+text button in the quick-actions row.
 * §8: [cd] is the TalkBack label; height >= 48dp is enforced by [heightIn].
 */
@Composable
private fun QuickActionButton(
    icon: ImageVector,
    label: String,
    cd: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FilledTonalButton(
        onClick = onClick,
        modifier = modifier
            .heightIn(min = 48.dp)
            .semantics { contentDescription = cd },
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null, // cd on the button carries the meaning
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.size(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 2,
        )
    }
}

@Composable
private fun DoseCard(
    dose: DoseRow,
    onTake: () -> Unit,
    onSnooze: () -> Unit,
    onSkip: () -> Unit,
    onHistory: () -> Unit,
) {
    val context = LocalContext.current
    val timeText = DateFormat.getTimeFormat(context).format(Date(dose.dueEpochMs))
    val actionable = dose.state == DoseState.DUE || dose.state == DoseState.SNOOZED

    val takenCd = stringResource(R.string.cd_dose_taken_action, dose.medName)
    val snoozeCd = stringResource(R.string.cd_dose_snooze_action, dose.medName)
    val skipCd  = stringResource(R.string.cd_dose_skip_action,  dose.medName)

    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        // Info header — merged into one TalkBack node; double-tap opens dose history.
        // §8: mergeDescendants = true collapses med name + dose summary + state label
        // into a single focus node so TalkBack reads a coherent sentence rather than
        // three separate fragments.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .semantics(mergeDescendants = true) {}
                .clickable(
                    onClickLabel = stringResource(R.string.cd_dose_history_action, dose.medName),
                    onClick = onHistory,
                ),
        ) {
            Text(
                text = dose.medName,
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = stringResource(R.string.today_dose_summary, dose.strength, timeText),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
            // §8: icon + text for every dose state — never color-only (Law 10).
            DoseStateLabel(state = dose.state)
        }

        if (actionable) {
            Button(
                onClick = onTake,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp)
                    .semantics { contentDescription = takenCd },
            ) {
                Text(stringResource(R.string.dose_action_taken))
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = onSnooze,
                    modifier = Modifier.weight(1f).semantics { contentDescription = snoozeCd },
                ) {
                    Text(stringResource(R.string.dose_action_snooze))
                }
                TextButton(
                    onClick = onSkip,
                    modifier = Modifier.weight(1f).semantics { contentDescription = skipCd },
                ) {
                    Text(stringResource(R.string.dose_action_skip))
                }
            }
        }
    }
}

/**
 * A row for a PRN (as-needed) medication on the Today screen (spec §2.7).
 *
 * PRN doses have no SCHEDULED or MISSED states — the "Log dose" button is the only action.
 * If [PrnMedRow.dosesToday] > 0, shows a "Logged today: N" count so the user can track intake
 * without the app forbidding or advising further doses (Law 3).
 * Tapping the medication name opens dose history (consistent with [DoseCard]).
 */
@Composable
private fun PrnMedCard(
    prn: PrnMedRow,
    onLogDose: () -> Unit,
    onHistory: () -> Unit,
) {
    val logDoseCd = stringResource(R.string.cd_prn_log_dose, prn.medName)

    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        // Info header — merged into one TalkBack node (§8: mergeDescendants = true so
        // TalkBack reads med name + PRN label + strength as one coherent sentence).
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .semantics(mergeDescendants = true) {}
                .clickable(
                    onClickLabel = stringResource(R.string.cd_dose_history_action, prn.medName),
                    onClick = onHistory,
                ),
        ) {
            Text(
                text = prn.medName,
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = prn.doseAmount,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.padding(top = 2.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.MedicalServices,
                    contentDescription = null, // decorative — text below carries the meaning
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = if (prn.indication != null) {
                        stringResource(R.string.today_prn_label_with_indication, prn.indication)
                    } else {
                        stringResource(R.string.schedule_type_prn)
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = prn.strength,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
            if (prn.dosesToday > 0) {
                Text(
                    text = stringResource(R.string.today_prn_doses_today, prn.dosesToday),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        Button(
            onClick = onLogDose,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp)
                .semantics { contentDescription = logDoseCd },
        ) {
            Text(stringResource(R.string.today_prn_log_dose))
        }
    }
}

/**
 * Empty-Today state (no doses today + no PRN meds).
 *
 * Calm, content-first per DESIGN.md. Provides a clear path to add a medication
 * so the user is never left without a next step (F3 spec requirement).
 */
@Composable
private fun EmptyToday(
    onOpenMedications: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.Schedule,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(R.string.empty_today_body),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 12.dp),
            )
            Spacer(Modifier.height(16.dp))
            // Clear path to add a medication (F3 spec: calm state + add-med CTA).
            val addMedCd = stringResource(R.string.cd_today_add_medication)
            FilledTonalButton(
                onClick = onOpenMedications,
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .semantics { contentDescription = addMedCd },
            ) {
                Icon(
                    imageVector = Icons.Outlined.AddCircleOutline,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.size(6.dp))
                Text(stringResource(R.string.today_empty_add_action))
            }
        }
    }
}

internal fun stateLabelRes(state: DoseState): Int = when (state) {
    DoseState.DUE -> R.string.dose_state_due
    DoseState.SNOOZED -> R.string.dose_state_snoozed
    DoseState.SCHEDULED -> R.string.dose_state_scheduled
    DoseState.TAKEN -> R.string.dose_state_taken
    DoseState.SKIPPED -> R.string.dose_state_skipped
    DoseState.MISSED -> R.string.dose_state_missed
}

/**
 * Icon + text label for a dose state (§8 / Law 10: icon + text, never color-only).
 *
 * The icon is decorative ([contentDescription] = null) because the text carries the semantic
 * meaning. [DoseState.MISSED] uses the warning icon + error tint to reinforce the urgency
 * visually (color as ADDITIONAL signal, not the sole signal — always paired with text).
 */
@Composable
internal fun DoseStateLabel(
    state: DoseState,
    modifier: Modifier = Modifier,
) {
    val icon: ImageVector = when (state) {
        DoseState.DUE, DoseState.SNOOZED, DoseState.SCHEDULED -> Icons.Outlined.Schedule
        DoseState.TAKEN, DoseState.SKIPPED -> Icons.Outlined.CheckCircleOutline
        DoseState.MISSED -> Icons.Outlined.WarningAmber
    }
    val tint: Color = when (state) {
        DoseState.MISSED -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = modifier.padding(top = 2.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null, // decorative — text label carries the semantic meaning
            modifier = Modifier.size(14.dp),
            tint = tint,
        )
        Text(
            text = stringResource(stateLabelRes(state)),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
