package com.beryndil.pharos.data.drugref.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.beryndil.pharos.data.drugref.entity.IngredientMapEntity

@Dao
interface IngredientMapDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(edges: List<IngredientMapEntity>)

    /**
     * Returns all ingredient edges for a given drug RxCUI.
     * Used at add-medication time to resolve the ingredient RxCUIs stored in
     * [MedicationEntity.ingredientsJson] for duplicate-ingredient detection (spec §2.4).
     */
    @Query("SELECT * FROM ingredient_map WHERE drug_rxcui = :drugRxcui")
    suspend fun ingredientsForDrug(drugRxcui: String): List<IngredientMapEntity>

    /**
     * Batch-fetch all ingredient edges for a list of drug RxCUIs.
     * Used to resolve ingredients for a page of search results in a single query.
     */
    @Query("SELECT * FROM ingredient_map WHERE drug_rxcui IN (:drugRxcuis)")
    suspend fun getForDrugs(drugRxcuis: List<String>): List<IngredientMapEntity>

    /**
     * Fetch ingredient-map rows by ingredient RxCUI — used to resolve display names for the
     * duplicate-ingredient warning (and for [MedicationRepository.getIngredientNames]).
     * The same [ingredientRxcui] may appear in many rows (one per drug containing it);
     * callers should group by [IngredientMapEntity.ingredientRxcui] and take the first name.
     */
    @Query("SELECT * FROM ingredient_map WHERE ingredient_rxcui IN (:rxcuis)")
    suspend fun getByIngredientRxcuis(rxcuis: List<String>): List<IngredientMapEntity>

    @Query("SELECT COUNT(*) FROM ingredient_map")
    suspend fun count(): Int
}
