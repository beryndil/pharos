package com.beryndil.pharos.settings.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForwardIos
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.beryndil.pharos.R
import com.beryndil.pharos.settings.SettingsEvent
import com.beryndil.pharos.settings.SettingsUiState
import com.beryndil.pharos.settings.TextScale
import com.beryndil.pharos.settings.ThemeMode

/**
 * Settings screen — theme mode selector, in-app text size selector, navigation to About/Legal.
 *
 * Design (DESIGN.md): content-first, restrained. Section headings in labelLarge/onSurfaceVariant.
 * Selectable rows use [Modifier.selectable] with [Role.RadioButton] so TalkBack announces the
 * selected state automatically — no manual semantics block needed for selected.
 *
 * Accessibility (PROGRAMMING_STANDARDS §8 / Law 10):
 *  - All actionable rows ≥48dp (enforced via [Modifier.heightIn]).
 *  - Radio rows use [Modifier.selectable] + [Role.RadioButton] → TalkBack reads
 *    "Selected / Not selected" automatically.
 *  - Navigation rows carry explicit [contentDescription].
 *  - Theme options are radio + text, NOT color-only.
 *  - The text-size preview renders a live sample at the selected scale so the user can judge
 *    legibility before confirming — required for low-vision users.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    uiState: SettingsUiState,
    onEvent: (SettingsEvent) -> Unit,
    onOpenAbout: () -> Unit,
    onOpenLegal: () -> Unit,
    onOpenProfile: () -> Unit = {},
    onOpenContacts: () -> Unit = {},
    onOpenReliability: () -> Unit = {},
    onShareDebugLog: () -> Unit = {},
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    com.beryndil.pharos.core.ui.ClearWindowSecurity()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    val backCd = stringResource(R.string.cd_back_button)

    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text(stringResource(R.string.screen_settings)) },
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
                .verticalScroll(rememberScrollState()),
        ) {

            // ── Appearance section ────────────────────────────────────────
            SectionHeader(stringResource(R.string.settings_section_appearance))

            // Theme mode
            SectionLabel(stringResource(R.string.settings_theme_label))
            Spacer(modifier = Modifier.height(4.dp))

            Column(modifier = Modifier.selectableGroup()) {
                ThemeModeOption(
                    label = stringResource(R.string.settings_theme_system),
                    selected = uiState.themeMode == ThemeMode.SYSTEM,
                    onClick = { onEvent(SettingsEvent.SetThemeMode(ThemeMode.SYSTEM)) },
                )
                ThemeModeOption(
                    label = stringResource(R.string.settings_theme_light),
                    selected = uiState.themeMode == ThemeMode.LIGHT,
                    onClick = { onEvent(SettingsEvent.SetThemeMode(ThemeMode.LIGHT)) },
                )
                ThemeModeOption(
                    label = stringResource(R.string.settings_theme_dark),
                    selected = uiState.themeMode == ThemeMode.DARK,
                    onClick = { onEvent(SettingsEvent.SetThemeMode(ThemeMode.DARK)) },
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Text size
            SectionLabel(stringResource(R.string.settings_text_size_label))
            Spacer(modifier = Modifier.height(4.dp))

            Column(modifier = Modifier.selectableGroup()) {
                TextScaleOption(
                    label = stringResource(R.string.settings_text_size_default),
                    selected = uiState.textScale == TextScale.DEFAULT,
                    onClick = { onEvent(SettingsEvent.SetTextScale(TextScale.DEFAULT)) },
                )
                TextScaleOption(
                    label = stringResource(R.string.settings_text_size_large),
                    selected = uiState.textScale == TextScale.LARGE,
                    onClick = { onEvent(SettingsEvent.SetTextScale(TextScale.LARGE)) },
                )
                TextScaleOption(
                    label = stringResource(R.string.settings_text_size_extra_large),
                    selected = uiState.textScale == TextScale.EXTRA_LARGE,
                    onClick = { onEvent(SettingsEvent.SetTextScale(TextScale.EXTRA_LARGE)) },
                )
                TextScaleOption(
                    label = stringResource(R.string.settings_text_size_largest),
                    selected = uiState.textScale == TextScale.LARGEST,
                    onClick = { onEvent(SettingsEvent.SetTextScale(TextScale.LARGEST)) },
                )
            }

            // Live preview — shows bodyLarge at the currently applied scale (PharosTheme
            // recomposes when the selection changes) so the user can judge legibility.
            TextSizePreview()

            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider()

            // ── Profile section ───────────────────────────────────────────
            SectionHeader(stringResource(R.string.screen_profile))

            NavigationRow(
                label = stringResource(R.string.settings_action_profile),
                contentDesc = stringResource(R.string.cd_navigate_profile),
                onClick = onOpenProfile,
            )
            HorizontalDivider(modifier = Modifier.padding(start = 16.dp))
            NavigationRow(
                label = stringResource(R.string.settings_action_contacts),
                contentDesc = stringResource(R.string.cd_navigate_contacts),
                onClick = onOpenContacts,
            )

            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider()

            // ── Reminders section ─────────────────────────────────────────
            SectionHeader(stringResource(R.string.settings_section_reminders))

            NavigationRow(
                label = stringResource(R.string.settings_action_reliability),
                contentDesc = stringResource(R.string.cd_navigate_reliability),
                onClick = onOpenReliability,
            )

            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider()

            // ── Support section ───────────────────────────────────────────
            SectionHeader(stringResource(R.string.settings_section_support))

            NavigationRow(
                label = stringResource(R.string.settings_action_share_debug_log),
                contentDesc = stringResource(R.string.cd_share_debug_log),
                onClick = onShareDebugLog,
            )

            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider()

            // ── About & legal section ─────────────────────────────────────
            SectionHeader(stringResource(R.string.settings_section_about_legal))

            NavigationRow(
                label = stringResource(R.string.settings_action_about),
                contentDesc = stringResource(R.string.cd_navigate_about),
                onClick = onOpenAbout,
            )
            HorizontalDivider(modifier = Modifier.padding(start = 16.dp))
            NavigationRow(
                label = stringResource(R.string.settings_action_legal),
                contentDesc = stringResource(R.string.cd_navigate_legal_from_settings),
                onClick = onOpenLegal,
            )

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

// ── Private composables ─────────────────────────────────────────────────────

@Composable
private fun SectionHeader(title: String, modifier: Modifier = Modifier) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
    )
}

@Composable
private fun SectionLabel(label: String, modifier: Modifier = Modifier) {
    Text(
        text = label,
        style = MaterialTheme.typography.titleSmall,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
    )
}

/**
 * A single theme mode radio row.
 *
 * [Modifier.selectable] with [Role.RadioButton] ensures TalkBack announces "Radio button, Selected"
 * or "Radio button, Not selected" automatically — no custom semantics needed.
 */
@Composable
private fun ThemeModeOption(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .selectable(
                selected = selected,
                onClick = onClick,
                role = Role.RadioButton,
            )
            .padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        RadioButton(selected = selected, onClick = null) // null: row handles the click
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(start = 12.dp),
        )
    }
}

/**
 * A single text-scale radio row.
 *
 * The label itself is rendered at the base [MaterialTheme.typography.bodyLarge] size (which
 * reflects the currently applied PharosTheme scale) so the user sees their chosen size live.
 */
@Composable
private fun TextScaleOption(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .selectable(
                selected = selected,
                onClick = onClick,
                role = Role.RadioButton,
            )
            .padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(start = 12.dp),
        )
    }
}

/**
 * Live text-size preview box rendered below the size selector.
 *
 * The text renders at [MaterialTheme.typography.bodyLarge], which reflects the currently applied
 * PharosTheme typography (i.e. the CURRENT selection as collected by MainActivity, not a local
 * preview-only scale). Because the settings change is written immediately and MainActivity
 * collects the pref as state, the typography recomposes globally — this preview just shows
 * the same bodyLarge the rest of the app sees, giving the user an accurate preview.
 */
@Composable
private fun TextSizePreview(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(
            text = stringResource(R.string.settings_text_size_preview),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * A tappable navigation row with a trailing chevron icon.
 *
 * Row height ≥48dp per Law 10 / PROGRAMMING_STANDARDS §8.
 * The explicit [contentDescription] is the entire row's TalkBack label (the label text alone
 * satisfies the requirement, but the dedicated cd makes the intent clearer for screen readers).
 */
@Composable
private fun NavigationRow(
    label: String,
    contentDesc: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(
                onClickLabel = contentDesc,
                onClick = onClick,
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        Icon(
            imageVector = Icons.AutoMirrored.Outlined.ArrowForwardIos,
            contentDescription = null, // decorative — the row click-label carries the intent
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
