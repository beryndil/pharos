package com.beryndil.pharos.data.drugref.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.beryndil.pharos.data.drugref.entity.IngredientEntity

@Dao
interface IngredientDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(ingredients: List<IngredientEntity>)

    @Query("SELECT * FROM ingredients WHERE rxcui = :rxcui")
    suspend fun getByRxcui(rxcui: String): IngredientEntity?

    @Query("SELECT * FROM ingredients WHERE name LIKE :query || '%' ORDER BY name ASC LIMIT 20")
    suspend fun searchByName(query: String): List<IngredientEntity>

    @Query("SELECT COUNT(*) FROM ingredients")
    suspend fun count(): Int

    @Query("SELECT * FROM ingredients ORDER BY name ASC")
    suspend fun getAll(): List<IngredientEntity>
}
