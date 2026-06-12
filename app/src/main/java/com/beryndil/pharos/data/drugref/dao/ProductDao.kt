package com.beryndil.pharos.data.drugref.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.beryndil.pharos.data.drugref.entity.ProductEntity

@Dao
interface ProductDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(products: List<ProductEntity>)

    @Query("SELECT * FROM products WHERE rxcui = :rxcui")
    suspend fun getByRxcui(rxcui: String): ProductEntity?

    @Query("SELECT * FROM products WHERE name LIKE '%' || :query || '%' ORDER BY name ASC LIMIT 30")
    suspend fun searchByName(query: String): List<ProductEntity>

    @Query("SELECT COUNT(*) FROM products")
    suspend fun count(): Int

    @Query("SELECT * FROM products ORDER BY name ASC")
    suspend fun getAll(): List<ProductEntity>
}
