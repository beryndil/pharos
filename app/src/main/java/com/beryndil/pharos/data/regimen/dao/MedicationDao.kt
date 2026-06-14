package com.beryndil.pharos.data.regimen.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.beryndil.pharos.data.regimen.entity.MedicationEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MedicationDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(medication: MedicationEntity)

    /**
     * Full-row update. Allowed for medications (e.g., adding a prescriber, pausing).
     * This does NOT create a historical record — it modifies the current medication row.
     * The append-only invariant lives in [DoseInstanceDao] and [ScheduleDao].
     */
    @Update
    suspend fun update(medication: MedicationEntity)

    @Query("SELECT * FROM medications WHERE id = :id")
    suspend fun getById(id: String): MedicationEntity?

    @Query("SELECT * FROM medications WHERE status != 'ENDED' ORDER BY name ASC")
    fun observeActive(): Flow<List<MedicationEntity>>

    @Query("SELECT * FROM medications ORDER BY name ASC")
    fun observeAll(): Flow<List<MedicationEntity>>

    @Query("SELECT * FROM medications WHERE status = 'ACTIVE' ORDER BY name ASC")
    suspend fun getActiveOnce(): List<MedicationEntity>

    /** Returns every medication row — used by the backup export path only. */
    @Query("SELECT * FROM medications ORDER BY createdAtEpochMs ASC")
    suspend fun getAll(): List<MedicationEntity>

    /**
     * Returns active medications that have [MedicationEntity.isCritical] = true.
     * Used to determine whether to prompt for DND policy access (lazy, on first critical med)
     * and to populate the reliability dashboard critical-meds list.
     */
    @Query("SELECT * FROM medications WHERE status = 'ACTIVE' AND isCritical = 1 ORDER BY name ASC")
    suspend fun getCriticalActive(): List<MedicationEntity>

    /**
     * Returns the count of medications that have not been ended (status ACTIVE or PAUSED).
     * Used by [com.beryndil.pharos.ui.navigation.resolveStartDestination] to determine whether
     * to route a returning user to Today or to the medication-list empty state.
     */
    @Query("SELECT COUNT(*) FROM medications WHERE status != 'ENDED'")
    suspend fun countNonEnded(): Int

    /**
     * Permanently remove a medication row. Call only after all child records
     * (dose_transitions → dose_instances → schedule_phases → schedules → refill_records)
     * have been deleted, and after [clearSubstituteRef] has been called so no other
     * medication points to this one.
     */
    @Query("DELETE FROM medications WHERE id = :id")
    suspend fun deleteById(id: String)

    /** Clear any cross-medication substitute link that pointed at the medication being deleted. */
    @Query("UPDATE medications SET substituteForMedId = NULL WHERE substituteForMedId = :medId")
    suspend fun clearSubstituteRef(medId: String)
}
