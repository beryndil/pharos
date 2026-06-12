package com.beryndil.pharos.data.drugref.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * An active ingredient from the trimmed RxNorm bundle.
 *
 * The [rxcui] is the RxNorm Concept Unique Identifier. [tty] is the RxNorm term type
 * (e.g., "IN" = ingredient, "PIN" = precise ingredient). Ingredient data drives the
 * duplicate-ingredient warning (spec §2.4).
 */
@Entity(tableName = "ingredients")
data class IngredientEntity(
    /** RxNorm RxCUI string, e.g., "161" for Acetaminophen. */
    @PrimaryKey val rxcui: String,

    val name: String,

    /** RxNorm term type. "IN" = ingredient, "PIN" = precise ingredient. */
    val tty: String,
)
