package com.beryndil.pharos.medication.model

/**
 * A duplicate-ingredient warning produced when the medication being added/edited shares an
 * active ingredient with an existing medication in the regimen.
 *
 * Spec §2.4 phrasing (Law 3): "Heads up — [Med A] and [Med B] both contain [ingredient].
 * Taking both could mean a higher total dose than you intend. Check with your doctor or
 * pharmacist."
 *
 * [newMedName] is provided separately by the caller (the form's current display name) so that
 * the repository does not need to know the name of the medication being built.
 */
data class DuplicateWarning(
    /** Name of the existing ACTIVE medication that shares an ingredient. */
    val existingMedName: String,

    /** Human-readable name of the shared ingredient. */
    val ingredientName: String,
)
