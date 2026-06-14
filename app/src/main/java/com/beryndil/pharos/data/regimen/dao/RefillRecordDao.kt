package com.beryndil.pharos.data.regimen.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.beryndil.pharos.data.regimen.entity.RefillRecordEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface RefillRecordDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(record: RefillRecordEntity)

    @Query(
        "SELECT * FROM refill_records WHERE medicationId = :medicationId " +
            "ORDER BY createdAtEpochMs DESC",
    )
    fun observeByMedication(medicationId: String): Flow<List<RefillRecordEntity>>

    @Query(
        "SELECT * FROM refill_records WHERE medicationId = :medicationId " +
            "ORDER BY createdAtEpochMs DESC LIMIT 1",
    )
    suspend fun getLatest(medicationId: String): RefillRecordEntity?

    /** Returns every refill record row — backup export only. */
    @Query("SELECT * FROM refill_records ORDER BY createdAtEpochMs ASC")
    suspend fun getAll(): List<RefillRecordEntity>

    /** Delete all refill records for a medication (medication-delete flow). */
    @Query("DELETE FROM refill_records WHERE medicationId = :medicationId")
    suspend fun deleteByMedication(medicationId: String)
}
