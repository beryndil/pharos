package com.beryndil.pharos.data.regimen.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.beryndil.pharos.data.regimen.entity.PrescriberEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PrescriberDao {

    /** Insert-or-replace (upsert by primary key). */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(prescriber: PrescriberEntity)

    @Update
    suspend fun update(prescriber: PrescriberEntity)

    /** All prescribers sorted by name ascending for autocomplete / manage screen. */
    @Query("SELECT * FROM prescribers ORDER BY name ASC")
    fun observeAll(): Flow<List<PrescriberEntity>>

    /** One-shot read for a specific name (case-insensitive match). */
    @Query("SELECT * FROM prescribers WHERE lower(name) = lower(:name) LIMIT 1")
    suspend fun getByName(name: String): PrescriberEntity?

    @Query("SELECT * FROM prescribers WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): PrescriberEntity?

    @Query("DELETE FROM prescribers WHERE id = :id")
    suspend fun deleteById(id: String)
}
