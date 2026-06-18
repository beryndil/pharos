package com.beryndil.pharos.data.regimen.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.beryndil.pharos.data.regimen.entity.SupplyEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SupplyDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(supply: SupplyEntity)

    @Update
    suspend fun update(supply: SupplyEntity)

    @Query("SELECT * FROM supplies WHERE status = 'ACTIVE' ORDER BY createdAtEpochMs ASC")
    fun observeActive(): Flow<List<SupplyEntity>>

    @Query("SELECT * FROM supplies WHERE id = :id")
    suspend fun getById(id: String): SupplyEntity?

    /** Backup export — all rows. */
    @Query("SELECT * FROM supplies ORDER BY createdAtEpochMs ASC")
    suspend fun getAll(): List<SupplyEntity>

    /** All active supplies for the low-supply check worker. */
    @Query("SELECT * FROM supplies WHERE status = 'ACTIVE'")
    suspend fun getActiveOnce(): List<SupplyEntity>

    @Query("UPDATE supplies SET status = :status WHERE id = :id")
    suspend fun updateStatus(id: String, status: String)
}
