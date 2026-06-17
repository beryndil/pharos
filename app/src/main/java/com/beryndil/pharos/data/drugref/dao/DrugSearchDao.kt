package com.beryndil.pharos.data.drugref.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.beryndil.pharos.data.drugref.entity.DrugSearchEntity

@Dao
interface DrugSearchDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(drugs: List<DrugSearchEntity>)

    /**
     * Case-insensitive name search (prefix-first, then contains), limited to 30 results.
     *
     * The caller is responsible for lowercasing [q] before calling; [q] is matched against the
     * pre-lowercased `name_lower` column so the LIKE index is used. Results with a prefix match
     * are ranked first, then ordered by name length (shorter = more specific).
     */
    @Query(
        """
        SELECT * FROM drug_search
        WHERE name_lower LIKE '%' || :q || '%'
        ORDER BY
            CASE WHEN name_lower LIKE :q || '%' THEN 0 ELSE 1 END,
            length(name) ASC
        LIMIT 30
        """,
    )
    suspend fun searchByName(q: String): List<DrugSearchEntity>

    /**
     * Look up all `drug_search` rows for a given RxCUI.
     * Returns the first match (or null) for callers that only need one row per rxcui.
     */
    @Query("SELECT * FROM drug_search WHERE rxcui = :rxcui LIMIT 1")
    suspend fun getByRxcui(rxcui: String): DrugSearchEntity?

    /**
     * Returns the name of the first BN (brand name) concept whose ingredient set includes
     * [ingredientRxcui], ordered by RxCUI ascending so the oldest/most-established brand
     * is preferred (e.g., Zestril ~35208 over Qbrelis ~2121695).
     */
    @Query("""
        SELECT ds.name FROM drug_search ds
        INNER JOIN ingredient_map im ON ds.rxcui = im.drug_rxcui
        WHERE im.ingredient_rxcui = :ingredientRxcui AND ds.tty = 'BN'
        ORDER BY CAST(ds.rxcui AS INTEGER) ASC
        LIMIT 1
    """)
    suspend fun firstBrandNameForIngredient(ingredientRxcui: String): String?

    /**
     * Returns all BN brand names whose ingredient set includes any of [ingredientRxcuis].
     * Used to populate the "in place of" suggestions dropdown. Oldest brand first (RxCUI asc).
     */
    @Query("""
        SELECT DISTINCT ds.name FROM drug_search ds
        INNER JOIN ingredient_map im ON ds.rxcui = im.drug_rxcui
        WHERE im.ingredient_rxcui IN (:ingredientRxcuis) AND ds.tty = 'BN'
        ORDER BY CAST(ds.rxcui AS INTEGER) ASC
    """)
    suspend fun allBrandNamesForIngredients(ingredientRxcuis: List<String>): List<String>

    @Query("SELECT COUNT(*) FROM drug_search")
    suspend fun count(): Int
}
