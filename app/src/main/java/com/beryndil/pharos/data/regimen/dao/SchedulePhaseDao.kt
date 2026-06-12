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

    // No UPDATE or DELETE. Phases belong to an immutable schedule version.
}
