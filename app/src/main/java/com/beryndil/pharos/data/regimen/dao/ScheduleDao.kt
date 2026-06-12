package com.beryndil.pharos.data.regimen.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.beryndil.pharos.data.regimen.entity.ScheduleEntity
import kotlinx.coroutines.flow.Flow

/**
 * DAO for [ScheduleEntity].
 *
 * APPEND-ONLY contract: no full-row UPDATE, no DELETE. To change a schedule, call
 * [deactivate] on the current version then [insert] a new row. Both rows persist.
 */
@Dao
interface ScheduleDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(schedule: ScheduleEntity)

    /**
     * Marks a schedule version inactive without deleting it. The caller must then insert
     * a new row representing the updated schedule.
     */
    @Query("UPDATE schedules SET isActive = 0 WHERE id = :id")
    suspend fun deactivate(id: String)

    @Query("SELECT * FROM schedules WHERE medicationId = :medicationId AND isActive = 1")
    fun observeActiveByMedication(medicationId: String): Flow<List<ScheduleEntity>>

    @Query("SELECT * FROM schedules WHERE medicationId = :medicationId AND isActive = 1")
    suspend fun getActiveByMedicationOnce(medicationId: String): List<ScheduleEntity>

    @Query("SELECT * FROM schedules WHERE id = :id")
    suspend fun getById(id: String): ScheduleEntity?

    @Query("SELECT * FROM schedules WHERE medicationId = :medicationId ORDER BY createdAtEpochMs ASC")
    suspend fun getAllVersionsForMedication(medicationId: String): List<ScheduleEntity>

    /** Returns every schedule row (all versions, active + inactive) — backup export only. */
    @Query("SELECT * FROM schedules ORDER BY createdAtEpochMs ASC")
    suspend fun getAll(): List<ScheduleEntity>

    // No DELETE method — see class-level comment.
}
