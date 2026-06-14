package com.beryndil.pharos.data.regimen.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.beryndil.pharos.data.regimen.entity.SchedulePhaseEntity

@Dao
interface SchedulePhaseDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAll(phases: List<SchedulePhaseEntity>)

    @Query(
        "SELECT * FROM schedule_phases WHERE scheduleId = :scheduleId ORDER BY phaseOrder ASC",
    )
    suspend fun getPhasesForSchedule(scheduleId: String): List<SchedulePhaseEntity>

    /** Returns every phase row — backup export only. */
    @Query("SELECT * FROM schedule_phases ORDER BY scheduleId ASC, phaseOrder ASC")
    suspend fun getAll(): List<SchedulePhaseEntity>

    /** Delete all phases for all schedules belonging to a medication (medication-delete flow). */
    @Query(
        "DELETE FROM schedule_phases WHERE scheduleId IN " +
            "(SELECT id FROM schedules WHERE medicationId = :medicationId)",
    )
    suspend fun deleteByMedication(medicationId: String)
}
