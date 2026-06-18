package com.beryndil.pharos.data.regimen.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.beryndil.pharos.data.regimen.entity.SupplyRecordEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SupplyRecordDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(record: SupplyRecordEntity)

    @Query(
        "SELECT * FROM supply_records WHERE supplyId = :supplyId " +
            "ORDER BY createdAtEpochMs DESC",
    )
    fun observeBySupply(supplyId: String): Flow<List<SupplyRecordEntity>>

    @Query(
        "SELECT * FROM supply_records WHERE supplyId = :supplyId " +
            "ORDER BY createdAtEpochMs DESC LIMIT 1",
    )
    suspend fun getLatest(supplyId: String): SupplyRecordEntity?

    /** Backup export — all rows. */
    @Query("SELECT * FROM supply_records ORDER BY createdAtEpochMs ASC")
    suspend fun getAll(): List<SupplyRecordEntity>

    @Query("DELETE FROM supply_records WHERE supplyId = :supplyId")
    suspend fun deleteBySupply(supplyId: String)
}
