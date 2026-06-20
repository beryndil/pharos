package com.beryndil.pharos.data.regimen.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.beryndil.pharos.data.regimen.entity.DoseInstanceEntity
import com.beryndil.pharos.data.regimen.entity.DoseTransitionEntity
import com.beryndil.pharos.data.regimen.entity.MedicationEntity
import com.beryndil.pharos.data.regimen.entity.PharmacyEntity
import com.beryndil.pharos.data.regimen.entity.PrescriberEntity
import com.beryndil.pharos.data.regimen.entity.RefillRecordEntity
import com.beryndil.pharos.data.regimen.entity.ScheduleEntity
import com.beryndil.pharos.data.regimen.entity.SchedulePhaseEntity
import com.beryndil.pharos.data.regimen.entity.SettingEntity
import com.beryndil.pharos.data.regimen.entity.SupplyEntity
import com.beryndil.pharos.data.regimen.entity.SupplyRecordEntity

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
 *   supply_records → supplies
 *   settings / prescribers / pharmacies (no FK)
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

    @Query("DELETE FROM supply_records")
    suspend fun clearSupplyRecords()

    @Query("DELETE FROM supplies")
    suspend fun clearSupplies()

    @Query("DELETE FROM prescribers")
    suspend fun clearPrescribers()

    @Query("DELETE FROM pharmacies")
    suspend fun clearPharmacies()

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

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertPrescribers(prescribers: List<PrescriberEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertPharmacies(pharmacies: List<PharmacyEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertSupplies(supplies: List<SupplyEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertSupplyRecords(records: List<SupplyRecordEntity>)

    // ── Empty-regimen detection (post-wipe restore offer, spec §2.12) ─────────

    @Query("SELECT COUNT(*) FROM medications")
    suspend fun countMedications(): Int
}
