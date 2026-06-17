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
            "WHERE id = :id AND (state = 'DUE' OR state = 'SNOOZED')",
    )
    suspend fun markSkipped(id: String, skippedEpochMs: Long)

    /**
     * Re-enter DUE from SNOOZED when the snooze interval elapses (spec §2.6 SNOOZED → DUE).
     * Clears the snooze target so the next escalation/miss math is clean.
     */
    @Query(
        "UPDATE dose_instances SET state = 'DUE', snoozeUntilEpochMs = NULL " +
            "WHERE id = :id AND state = 'SNOOZED'",
    )
    suspend fun markDueFromSnooze(id: String)

    @Query(
        "UPDATE dose_instances SET state = 'MISSED', missedEpochMs = :missedEpochMs " +
            "WHERE id = :id AND (state = 'DUE' OR state = 'SNOOZED')",
    )
    suspend fun markMissed(id: String, missedEpochMs: Long)

    /** Mark all SCHEDULED instances for a deactivated schedule as MISSED so they leave the Today screen. */
    @Query(
        "UPDATE dose_instances SET state = 'MISSED', missedEpochMs = :missedEpochMs " +
            "WHERE scheduleId = :scheduleId AND state = 'SCHEDULED'",
    )
    suspend fun cancelScheduledBySchedule(scheduleId: String, missedEpochMs: Long)

    /**
     * Startup cleanup: mark SCHEDULED instances belonging to deactivated schedules as MISSED.
     * Returns the count of rows updated for diagnostic logging.
     */
    @Query(
        "UPDATE dose_instances SET state = 'MISSED', missedEpochMs = :nowMs " +
            "WHERE state = 'SCHEDULED' " +
            "AND scheduleId NOT IN (SELECT id FROM schedules WHERE isActive = 1)",
    )
    suspend fun cancelOrphanedScheduled(nowMs: Long): Int

    /** Diagnostic: all dose instances whose due time falls in [from, to) — any state. */
    @Query(
        "SELECT * FROM dose_instances WHERE dueEpochMs >= :from AND dueEpochMs < :to " +
            "ORDER BY dueEpochMs ASC",
    )
    suspend fun getAllInWindow(from: Long, to: Long): List<DoseInstanceEntity>

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
     * The same medication's next SCHEDULED dose strictly AFTER [afterEpochMs] — the second half
     * of the D2 miss-window rule ("…OR the start of the same medication's next scheduled dose,
     * whichever comes first"). Returns null when this is the med's last dose.
     */
    @Query(
        "SELECT * FROM dose_instances WHERE medicationId = :medicationId " +
            "AND state = 'SCHEDULED' AND dueEpochMs > :afterEpochMs " +
            "ORDER BY dueEpochMs ASC LIMIT 1",
    )
    suspend fun getNextScheduledAfter(medicationId: String, afterEpochMs: Long): DoseInstanceEntity?

    /**
     * All doses for the today surface, ordered by due time.
     *
     * DUE/SNOOZED: any instance due before [beforeEpochMs], including carryover from prior days
     * that still need the user's attention.
     * Today's window: every instance whose due time falls within [scheduledFromEpochMs, beforeEpochMs),
     * regardless of state — covers SCHEDULED (upcoming), MISSED/SKIPPED/TAKEN (completed today).
     * Without the second clause, doses swept to MISSED by the startup sweep would disappear from
     * the Today screen entirely even though the user never saw them.
     */
    @Query(
        "SELECT * FROM dose_instances " +
            "WHERE (state IN ('DUE', 'SNOOZED') AND dueEpochMs < :beforeEpochMs) " +
            "OR (dueEpochMs >= :scheduledFromEpochMs AND dueEpochMs < :beforeEpochMs) " +
            "ORDER BY dueEpochMs ASC",
    )
    fun observeActionable(scheduledFromEpochMs: Long, beforeEpochMs: Long): Flow<List<DoseInstanceEntity>>

    /** All SCHEDULED instances with dueEpochMs before [beforeEpochMs] — used by the startup sweep. */
    @Query(
        "SELECT * FROM dose_instances WHERE state = 'SCHEDULED' AND dueEpochMs < :beforeEpochMs " +
            "ORDER BY dueEpochMs ASC",
    )
    suspend fun getAllScheduledBefore(beforeEpochMs: Long): List<DoseInstanceEntity>

    /**
     * Count of TAKEN doses for a medication on/after [sinceEpochMs] — drives the PRN daily-max
     * warning (spec §2.7). PRN logs are TAKEN rows; this counts how many were logged today.
     */
    @Query(
        "SELECT COUNT(*) FROM dose_instances WHERE medicationId = :medicationId " +
            "AND state = 'TAKEN' AND takenEpochMs >= :sinceEpochMs",
    )
    suspend fun countTakenSince(medicationId: String, sinceEpochMs: Long): Int

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

    /** Returns every dose instance row — backup export only. */
    @Query("SELECT * FROM dose_instances ORDER BY dueEpochMs ASC")
    suspend fun getAll(): List<DoseInstanceEntity>

    /**
     * Reactively observe all TAKEN dose instances logged at or after [sinceEpochMs].
     * Used by [com.beryndil.pharos.data.dose.DoseRepository.observePrnMeds] to show a
     * live "Logged today: N" count on the Today screen for PRN medications (spec §2.7).
     * Re-emits whenever a new PRN log is inserted (or any dose transitions to TAKEN).
     */
    @Query(
        "SELECT * FROM dose_instances WHERE state = 'TAKEN' AND takenEpochMs >= :sinceEpochMs",
    )
    fun observeAllTakenSince(sinceEpochMs: Long): Flow<List<DoseInstanceEntity>>

    /**
     * Delete all dose instances for a medication. Called only from the explicit
     * user-initiated medication-delete flow; must run after transitions are deleted
     * and before schedules are deleted to satisfy FK constraints.
     */
    @Query("DELETE FROM dose_instances WHERE medicationId = :medicationId")
    suspend fun deleteByMedication(medicationId: String)
}
