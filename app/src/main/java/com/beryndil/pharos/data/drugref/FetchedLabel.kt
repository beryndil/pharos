package com.beryndil.pharos.data.drugref

/**
 * Raw label data returned by [DrugLabelService] before being persisted to [LabelCacheEntity].
 *
 * All text fields may be null if the source database does not have that section. Null means
 * "not available" — the UI hides sections with no data rather than showing empty placeholders.
 */
data class FetchedLabel(
    val sideEffectsText: String?,
    val interactionsText: String?,
    val warningsText: String?,
    val precautionsText: String?,
    val contraindicationsText: String?,
    val boxedWarningText: String?,
    val foodEffectText: String?,
    /** Human-readable source identifier shown in the UI (Law 9). Example: "openFDA". */
    val source: String,
)
