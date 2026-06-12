package com.beryndil.pharos.data.drugref.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A drug product from the trimmed RxNorm bundle.
 *
 * A product maps to one or more active ingredients (for combo products). The
 * [ingredientsJson] field holds a JSON array of ingredient RxCUI strings that links to
 * [IngredientEntity] rows — used for duplicate-ingredient detection (spec §2.4).
 */
@Entity(tableName = "products")
data class ProductEntity(
    /** RxNorm RxCUI for this product, e.g., "209387". */
    @PrimaryKey val rxcui: String,

    val name: String,

    /**
     * JSON array of ingredient RxCUI strings.
     * Example: `["161"]` for single-ingredient, `["161","5640"]` for combination product.
     */
    val ingredientsJson: String,

    /** Dosage form, e.g., "Tablet", "Capsule", "Solution". */
    val form: String,

    /** Strength string as it appears in RxNorm, e.g., "500 mg", "10 mg/5 mL". */
    val strength: String,
)
