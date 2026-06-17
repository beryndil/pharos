package com.beryndil.pharos.data.regimen.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.beryndil.pharos.data.regimen.entity.PharmacyEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PharmacyDao {

    /** Insert-or-replace (upsert by primary key). */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(pharmacy: PharmacyEntity)

    @Update
    suspend fun update(pharmacy: PharmacyEntity)

    /** All pharmacies sorted by name ascending for autocomplete / manage screen. */
    @Query("SELECT * FROM pharmacies ORDER BY name ASC")
    fun observeAll(): Flow<List<PharmacyEntity>>

    /** One-shot read for a specific name (case-insensitive match). */
    @Query("SELECT * FROM pharmacies WHERE lower(name) = lower(:name) LIMIT 1")
    suspend fun getByName(name: String): PharmacyEntity?

    @Query("SELECT * FROM pharmacies WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): PharmacyEntity?

    @Query("DELETE FROM pharmacies WHERE id = :id")
    suspend fun deleteById(id: String)
}
