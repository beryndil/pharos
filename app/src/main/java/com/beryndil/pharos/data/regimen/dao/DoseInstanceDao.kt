package com.beryndil.pharos.data.regimen.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.beryndil.pharos.data.regimen.entity.DoseInstanceEntity
import kotlinx.coroutines.flow.Flow

/**
 * DAO for [DoseInstanceEntity].
 *
 * ┌─ APPEND-ONLY INVARIANT ───────────────────────────────────────────────────────────────────┐
 * │ • INSERT new rows for new dose instances. Never DELETE rows.                              │
 * │ • State transitions (DUE → TAKEN, etc.) use narrow UPDATE queries that touch ONLY the    │
 * │   columns relevant to that transition. Full-row @Update is intentionally absent.         │
 * │ • This design makes dose history tamper-evident at the DAO layer: a caller cannot         │
 * │   accidentally overwrite a past takenEpochMs or missedEpochMs by passing a full entity.  │
 * └───────────────────────────────────────────────────────────────────────────────────────────┘
 */
@Dao
interface DoseInstanceDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(dose: DoseInstanceEntity)

    // ── state transitions ─────────────────────────────────────────────────────

    @Query("UPDATE dose_instances SET state = 'DUE' WHERE id = :id AND state = 'SCHEDULED'")
    suspend fun markDue(id: String)

    @Query(
        "UPDATE dose_instances SET state = 'TAKEN', takenEpochMs = :takenEpochMs " +
            "WHERE id = :id AND (state = 'DUE' OR state = 'SNOOZED')",
    )
    suspend fun markTaken(id: String, takenEpochMs: Long)

    @Query(
        "UPDATE dose_instances SET state = 'SNOOZED', snoozeUntilEpochMs = :snoozeUntilEpochMs " +
            "WHERE id = :id AND (state = 'DUE' OR state = 'SNOOZED')",
    )
    suspend fun markSnoozed(id: String, snoozeUntilEpochMs: Long)

    @Query(
        "UPDATE dose_instances SET state = 'SKIPPED', skippedEpochMs = :skippedEpochMs " +
            "WHERE id = :id AND state = 'DUE'",
    )
    suspend fun markSkipped(id: String, skippedEpochMs: Long)

    @Query(
        "UPDATE dose_instances SET state = 'MISSED', missedEpochMs = :missedEpochMs " +
            "WHERE id = :id AND (state = 'DUE' OR state = 'SNOOZED')",
    )
    suspend fun markMissed(id: String, missedEpochMs: Long)

    // ── queries ───────────────────────────────────────────────────────────────

    @Query("SELECT * FROM dose_instances WHERE id = :id")
    suspend fun getById(id: String): DoseInstanceEntity?

    @Query(
        "SELECT * FROM dose_instances WHERE medicationId = :medicationId " +
            "ORDER BY dueEpochMs DESC",
    )
    fun observeByMedication(medicationId: String): Flow<List<DoseInstanceEntity>>

    @Query(
        "SELECT * FROM dose_instances WHERE state = 'SCHEDULED' OR state = 'DUE' " +
            "ORDER BY dueEpochMs ASC",
    )
    fun observePending(): Flow<List<DoseInstanceEntity>>

    @Query(
        "SELECT * FROM dose_instances WHERE medicationId = :medicationId " +
            "AND state = 'SCHEDULED' ORDER BY dueEpochMs ASC LIMIT 1",
    )
    suspend fun getNextScheduled(medicationId: String): DoseInstanceEntity?

    /**
     * The single earliest SCHEDULED dose instance across ALL medications, or null if none.
     *
     * This is the heart of the single-fire-and-reschedule alarm model (spec §3.4): the alarm
     * engine schedules exactly ONE exact alarm — for the row this query returns — and on fire
     * marks it DUE (so it drops out of this query) and re-runs this to schedule the next.
     * A row whose [DoseInstanceEntity.dueEpochMs] is already in the past (e.g., a dose that came
     * due while the device was powered off) is returned too, so reboot recovery fires it
     * immediately rather than dropping it.
     */
    @Query(
        "SELECT * FROM dose_instances WHERE state = 'SCHEDULED' " +
            "ORDER BY dueEpochMs ASC LIMIT 1",
    )
    suspend fun getEarliestScheduled(): DoseInstanceEntity?

    @Query("SELECT COUNT(*) FROM dose_instances WHERE id = :id")
    suspend fun countById(id: String): Int

    @Query("SELECT dueEpochMs FROM dose_instances WHERE scheduleId = :scheduleId")
    suspend fun getDueTimesForSchedule(scheduleId: String): List<Long>

    @Query(
        "SELECT * FROM dose_instances WHERE scheduleId = :scheduleId AND state = 'TAKEN' " +
            "ORDER BY takenEpochMs DESC LIMIT 1",
    )
    suspend fun getLastTakenForSchedule(scheduleId: String): DoseInstanceEntity?

    // No DELETE method. Dose history is permanent (spec §3.3, Law 9).
}
