package com.beryndil.pharos.data.drugref

/**
 * Raw label data returned by [DrugLabelService] before being persisted to [LabelCacheEntity].
 *
 * Either [sideEffectsText] or [interactionsText] may be null if the source database does not
 * have that section for the given drug. A null field means "not available" — the UI says so
 * plainly rather than hiding the section. Both being null is handled as "no data available".
 */
data class FetchedLabel(
    val sideEffectsText: String?,
    val interactionsText: String?,
    /** Human-readable source identifier shown in the UI (Law 9). Example: "openFDA". */
    val source: String,
)
