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

    // NOTE: No DELETE method. Medications are never physically removed (spec §3.3).
    // To end a medication, update its status to ENDED via [update].
}
