package com.beryndil.pharos.data.regimen.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.beryndil.pharos.data.regimen.entity.DoseTransitionEntity
import kotlinx.coroutines.flow.Flow

/**
 * DAO for [DoseTransitionEntity] — the append-only dose-history log (spec §2.6, Law 9).
 *
 * ┌─ APPEND-ONLY INVARIANT ───────────────────────────────────────────────────────────────────┐
 * │ INSERT only. No @Update, no DELETE. Dose history is immutable and tamper-evident at the    │
 * │ DAO layer: a caller cannot rewrite or erase a past transition.                            │
 * └───────────────────────────────────────────────────────────────────────────────────────────┘
 */
@Dao
interface DoseTransitionDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(transition: DoseTransitionEntity)

    /** All transitions for one medication, newest first — the per-med history view. */
    @Query(
        "SELECT * FROM dose_transitions WHERE medicationId = :medicationId " +
            "ORDER BY atEpochMs DESC, id DESC",
    )
    fun observeByMedication(medicationId: String): Flow<List<DoseTransitionEntity>>

    /** All transitions for a single dose instance, oldest first. */
    @Query(
        "SELECT * FROM dose_transitions WHERE doseInstanceId = :doseInstanceId " +
            "ORDER BY atEpochMs ASC, id ASC",
    )
    suspend fun getByDose(doseInstanceId: String): List<DoseTransitionEntity>

    @Query("SELECT COUNT(*) FROM dose_transitions WHERE doseInstanceId = :doseInstanceId")
    suspend fun countByDose(doseInstanceId: String): Int

    // No DELETE / @Update — dose history is permanent (spec §3.3, Law 9).
}
