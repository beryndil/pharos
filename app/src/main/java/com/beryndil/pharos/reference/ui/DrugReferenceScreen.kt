package com.beryndil.pharos.reference.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.beryndil.pharos.R
import com.beryndil.pharos.reference.DrugReferenceUiState
import java.text.DateFormat
import java.util.Date

/**
 * Drug reference screen — shows cached openFDA label sections (side effects + drug interactions)
 * with source and freshness date (spec §2.10, Law 3, Law 9, DESIGN.md).
 *
 * Design (DESIGN.md):
 *  - Content-first; no decoration. Large title, clear hierarchy.
 *  - Label text is reference only — never advice. Disclaimer present on every state.
 *  - Offline / free-text / loading states handled plainly with icon + text (Law 10).
 *  - ≥48dp touch targets; TalkBack labels on every control; sp text.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DrugReferenceScreen(
    uiState: DrugReferenceUiState,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    val backCd = stringResource(R.string.cd_back_button)

    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text(stringResource(R.string.screen_drug_reference)) },
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
        when (uiState) {
            is DrugReferenceUiState.Loading -> LoadingState(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            )
            is DrugReferenceUiState.FreeTextMed -> FreeTextState(
                medName = uiState.medName,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            )
            is DrugReferenceUiState.NotAvailableOffline -> OfflineState(
                medName = uiState.medName,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            )
            is DrugReferenceUiState.Loaded -> LoadedState(
                state = uiState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            )
        }
    }
}

// ── State composables ─────────────────────────────────────────────────────────

@Composable
private fun LoadingState(modifier: Modifier = Modifier) {
    // Hoist stringResource out of the semantics {} lambda (not a composable scope).
    val loadingCd = stringResource(R.string.cd_loading_drug_reference)
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        CircularProgressIndicator(
            modifier = Modifier.semantics { contentDescription = loadingCd },
        )
    }
}

@Composable
private fun FreeTextState(medName: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(modifier = Modifier.height(32.dp))
        Icon(
            imageVector = Icons.Outlined.Info,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.drug_reference_free_text),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun OfflineState(medName: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(modifier = Modifier.height(32.dp))
        Icon(
            imageVector = Icons.Outlined.WarningAmber,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.drug_reference_not_available_offline),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(24.dp))
        Disclaimer()
    }
}

@Composable
private fun LoadedState(state: DrugReferenceUiState.Loaded, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(bottom = 24.dp),
    ) {
        // Source + freshness metadata (Law 9).
        MetadataRow(source = state.source, fetchedAtEpochMs = state.fetchedAtEpochMs)

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

        // Side effects / adverse reactions section.
        LabelSection(
            title = stringResource(R.string.drug_reference_section_side_effects),
            body = state.sideEffectsText,
        )

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

        // Drug interactions reference section (reference text only — not a pairwise engine).
        LabelSection(
            title = stringResource(R.string.drug_reference_section_interactions),
            body = state.interactionsText,
        )

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

        Disclaimer(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
    }
}

@Composable
private fun MetadataRow(
    source: String,
    fetchedAtEpochMs: Long,
    modifier: Modifier = Modifier,
) {
    val dateStr = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(fetchedAtEpochMs))
    val metaCd = stringResource(R.string.cd_drug_reference_metadata, source, dateStr)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .semantics(mergeDescendants = true) { contentDescription = metaCd },
    ) {
        Text(
            text = stringResource(R.string.drug_reference_source_label),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = source,
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.drug_reference_fetched_label),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = dateStr,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun LabelSection(
    title: String,
    body: String?,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
        )
        Spacer(modifier = Modifier.height(8.dp))
        if (body.isNullOrBlank()) {
            Text(
                text = stringResource(R.string.drug_reference_section_none),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

/** Reference disclaimer (§4.2 — required wherever label text appears). */
@Composable
private fun Disclaimer(modifier: Modifier = Modifier) {
    Text(
        text = stringResource(R.string.drug_reference_disclaimer),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    )
}
