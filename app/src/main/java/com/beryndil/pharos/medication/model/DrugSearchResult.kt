package com.beryndil.pharos.medication.model

/**
 * A resolved drug product from the local RxNorm fixture, returned by a name-search query.
 *
 * Carries everything needed for the confirmation step (name + strength + form + ingredients)
 * and for writing back to [com.beryndil.pharos.data.regimen.entity.MedicationEntity]
 * (rxcui + ingredientRxcuis for duplicate-ingredient detection).
 */
data class DrugSearchResult(
    /** RxCUI of this product (e.g., "866427" for Metoprolol Succinate 25 mg Tablet). */
    val rxcui: String,

    /** Display name as it appears in RxNorm (e.g., "Metoprolol Succinate 25 MG Oral Tablet"). */
    val name: String,

    /** Strength string from RxNorm (e.g., "25 mg"). */
    val strength: String,

    /** Dosage form string from RxNorm (e.g., "Tablet", "Capsule"). */
    val rxNormForm: String,

    /** List of ingredient RxCUI strings. Written to MedicationEntity.ingredientsJson on save. */
    val ingredientRxcuis: List<String>,

    /**
     * Human-readable ingredient names resolved from [ingredientRxcuis].
     * Shown on the confirmation screen to surface ambiguity
     * (e.g., "Metoprolol Tartrate" vs "Metoprolol Succinate").
     */
    val ingredientNames: List<String>,
)
