package com.beryndil.pharos.medication.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.beryndil.pharos.R
import com.beryndil.pharos.medication.LabelPreviewState

// ── Drug info preview (shared by edit + detail screens) ───────────────────
//
// These composables render cached openFDA label sections (boxed warning,
// interactions, side effects, warnings, precautions, food effect) as
// reference-only text (Law 3 — no advice). Extracted from
// AddEditMedicationScreen.kt so MedicationDetailScreen can reuse them.

@Composable
internal fun DrugInfoLoadingRow(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
        Text(
            text = stringResource(R.string.drug_info_loading),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
internal fun DrugInfoPreviewSections(preview: LabelPreviewState.Available, modifier: Modifier = Modifier) {
    val hasAnyData = preview.boxedWarningText != null || preview.sideEffectsText != null ||
        preview.interactionsText != null || preview.warningsText != null ||
        preview.precautionsText != null || preview.foodEffectText != null
    if (!hasAnyData) return

    SelectionContainer {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        DrugInfoSectionHeader(stringResource(R.string.section_drug_info))
        preview.boxedWarningText?.let { DrugInfoBoxedWarning(it) }
        preview.sideEffectsText?.let {
            DrugInfoSection(stringResource(R.string.drug_reference_section_side_effects), it)
        }
        preview.interactionsText?.let {
            DrugInfoSection(stringResource(R.string.drug_reference_section_interactions), it)
        }
        preview.warningsText?.let {
            DrugInfoSection(stringResource(R.string.drug_reference_section_warnings), it)
        }
        preview.precautionsText?.let {
            DrugInfoSection(stringResource(R.string.drug_reference_section_precautions), it)
        }
        preview.foodEffectText?.let {
            DrugInfoSection(stringResource(R.string.drug_reference_section_food_effect), it)
        }
        Text(
            text = stringResource(R.string.drug_reference_disclaimer),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    }
}

@Composable
private fun DrugInfoSectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp),
    )
}

@Composable
private fun DrugInfoBoxedWarning(body: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = MaterialTheme.colorScheme.errorContainer,
                shape = RoundedCornerShape(8.dp),
            )
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = stringResource(R.string.drug_reference_section_boxed_warning),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onErrorContainer,
        )
        Text(
            text = body,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onErrorContainer,
        )
    }
}

@Composable
internal fun DrugInfoSection(title: String, body: String) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val truncated = body.length > DRUG_INFO_PREVIEW_CHARS
    val displayed = if (truncated && !expanded) body.take(DRUG_INFO_PREVIEW_CHARS).trimEnd() + "…" else body
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(text = title, style = MaterialTheme.typography.titleSmall)
        Text(
            text = displayed,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (truncated) {
            Text(
                text = if (expanded) stringResource(R.string.drug_info_show_less)
                       else stringResource(R.string.drug_info_read_more),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clickable { expanded = !expanded }
                    .padding(vertical = 4.dp),
            )
        }
    }
}

private const val DRUG_INFO_PREVIEW_CHARS = 400

@Composable
internal fun DrugInfoCard(labelPreview: LabelPreviewState, modifier: Modifier = Modifier) {
    var expanded by rememberSaveable { mutableStateOf(false) }

    when (labelPreview) {
        is LabelPreviewState.Loading -> DrugInfoLoadingRow(modifier)
        is LabelPreviewState.Available -> {
            val hasAnyData = labelPreview.boxedWarningText != null ||
                labelPreview.sideEffectsText != null ||
                labelPreview.interactionsText != null ||
                labelPreview.warningsText != null ||
                labelPreview.precautionsText != null ||
                labelPreview.foodEffectText != null
            if (!hasAnyData) return
            val expandLabel = if (expanded)
                stringResource(R.string.cd_drug_info_collapse)
            else
                stringResource(R.string.cd_drug_info_expand)
            OutlinedCard(modifier = modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(onClickLabel = expandLabel) { expanded = !expanded },
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(R.string.section_drug_info),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Icon(
                            imageVector = if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (expanded) {
                        Spacer(Modifier.height(8.dp))
                        DrugInfoPreviewSections(preview = labelPreview)
                    }
                }
            }
        }
        else -> {}
    }
}
