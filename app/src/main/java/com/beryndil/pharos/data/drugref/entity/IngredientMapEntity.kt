package com.beryndil.pharos.data.drugref.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index

/**
 * A drug → active-ingredient edge from the RxNorm pipeline (table `ingredient_map`).
 *
 * Each row links a drug concept ([drugRxcui]) to one of its active ingredients ([ingredientRxcui]).
 * Combo products (e.g., acetaminophen/hydrocodone) have one row per constituent ingredient.
 *
 * This table drives duplicate-ingredient detection (spec §2.4, Law 3): at add time the
 * ingredient RxCUIs resolved from this table are stored in [MedicationEntity.ingredientsJson]
 * and compared against all active medications. The comparison is RxCUI-set intersection —
 * correct even if [ingredientName] is not in the local DB (safety is never suppressed).
 */
@Entity(
    tableName = "ingredient_map",
    primaryKeys = ["drug_rxcui", "ingredient_rxcui"],
    indices = [
        Index(value = ["drug_rxcui"], name = "idx_drug"),
        Index(value = ["ingredient_rxcui"], name = "idx_ing"),
    ],
)
data class IngredientMapEntity(
    /** RxCUI of the drug / brand product. */
    @ColumnInfo(name = "drug_rxcui") val drugRxcui: String,

    /** RxCUI of the active ingredient (IN or PIN term type). */
    @ColumnInfo(name = "ingredient_rxcui") val ingredientRxcui: String,

    /** Canonical ingredient name — used for display in duplicate warnings. */
    @ColumnInfo(name = "ingredient_name") val ingredientName: String,
)
