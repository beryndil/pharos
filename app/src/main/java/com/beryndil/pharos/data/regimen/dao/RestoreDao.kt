package com.beryndil.pharos.data.regimen.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.beryndil.pharos.data.regimen.entity.DoseInstanceEntity
import com.beryndil.pharos.data.regimen.entity.DoseTransitionEntity
import com.beryndil.pharos.data.regimen.entity.MedicationEntity
import com.beryndil.pharos.data.regimen.entity.RefillRecordEntity
import com.beryndil.pharos.data.regimen.entity.ScheduleEntity
import com.beryndil.pharos.data.regimen.entity.SchedulePhaseEntity
import com.beryndil.pharos.data.regimen.entity.SettingEntity

/**
 * DAO used EXCLUSIVELY by the backup restore path (spec §2.12, DECISIONS.md S9-A2).
 *
 * Contains destructive DELETE queries for each table and bulk-INSERT methods for importing
 * backup data. Kept separate from the normal append-only DAOs to make the restore-specific
 * mutation surface explicit and auditable. These methods must only be called from
 * [com.beryndil.pharos.backup.BackupRepository.restore] inside a Room [withTransaction] block.
 *
 * Delete order must respect foreign-key constraints:
 *   dose_transitions → dose_instances → schedule_phases → schedules
 *                                      refill_records   → medications
 *                   settings (no FK)
 */
@Dao
interface RestoreDao {

    // ── Clear tables (FK-safe deletion order) ────────────────────────────────

    @Query("DELETE FROM dose_transitions")
    suspend fun clearDoseTransitions()

    @Query("DELETE FROM dose_instances")
    suspend fun clearDoseInstances()

    @Query("DELETE FROM schedule_phases")
    suspend fun clearSchedulePhases()

    @Query("DELETE FROM schedules")
    suspend fun clearSchedules()

    @Query("DELETE FROM refill_records")
    suspend fun clearRefillRecords()

    @Query("DELETE FROM medications")
    suspend fun clearMedications()

    @Query("DELETE FROM settings")
    suspend fun clearSettings()

    // ── Bulk insert (FK-safe insertion order) ─────────────────────────────────

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertMedications(meds: List<MedicationEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertSchedules(schedules: List<ScheduleEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertSchedulePhases(phases: List<SchedulePhaseEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertDoseInstances(instances: List<DoseInstanceEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertDoseTransitions(transitions: List<DoseTransitionEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertRefillRecords(records: List<RefillRecordEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSettings(settings: List<SettingEntity>)

    // ── Empty-regimen detection (post-wipe restore offer, spec §2.12) ─────────

    @Query("SELECT COUNT(*) FROM medications")
    suspend fun countMedications(): Int
}
