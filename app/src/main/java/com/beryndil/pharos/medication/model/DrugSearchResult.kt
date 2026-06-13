package com.beryndil.pharos.medication.model

/**
 * A resolved drug concept from the local RxNorm asset, returned by a name-search query.
 *
 * Carries everything needed for the confirmation step (name + TTY + ingredients)
 * and for writing back to [com.beryndil.pharos.data.regimen.entity.MedicationEntity]
 * (rxcui + ingredientRxcuis for duplicate-ingredient detection).
 *
 * Strength and dosage form are NOT included — the RxNorm pipeline encodes them in the
 * drug [name] for clinical types (e.g., "metoprolol succinate 25 MG Oral Tablet") but does not
 * expose them as separate columns. The user enters strength and form in the Add/Edit details step.
 */
data class DrugSearchResult(
    /** RxCUI of this drug concept. */
    val rxcui: String,

    /** Canonical name as it appears in RxNorm (e.g., "Metoprolol Succinate 25 MG Oral Tablet"). */
    val name: String,

    /**
     * RxNorm term type (e.g., "IN", "PIN", "MIN", "BN", "SCD", "SBD").
     * Displayed in the search result subtitle to distinguish generic names from brand names.
     */
    val tty: String,

    /** List of ingredient RxCUI strings. Written to MedicationEntity.ingredientsJson on save. */
    val ingredientRxcuis: List<String>,

    /**
     * Human-readable ingredient names resolved from [ingredientRxcuis].
     * Shown on the confirmation screen (e.g., "Metoprolol Succinate").
     */
    val ingredientNames: List<String>,
)
