package com.beryndil.pharos.data.regimen.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A single scheduled or logged dose occurrence.
 *
 * ┌─ APPEND-ONLY INVARIANT ─────────────────────────────────────────────────────────────────┐
 * │ Dose history is PERMANENT. The [DoseInstanceDao] has NO DELETE method.                  │
 * │ The ONLY mutations permitted are the state-transition helpers:                           │
 * │   markDue, markTaken, markSnoozed, markSkipped, markMissed                              │
 * │ These update exactly the columns that change during a valid state transition             │
 * │ (spec §2.6 state machine). Full-row UPDATE is not exposed.                              │
 * │                                                                                         │
 * │ Rationale: missing the 08:00 dose must have zero effect on the 12:00 dose.             │
 * │ Each row is an independent record of what happened at a point in time (Law 3, spec §2.6)│
 * └─────────────────────────────────────────────────────────────────────────────────────────┘
 *
 * Timestamps are UTC epoch-milliseconds. Zone-specific display is done at presentation time.
 */
@Entity(
    tableName = "dose_instances",
    foreignKeys = [
        ForeignKey(
            entity = MedicationEntity::class,
            parentColumns = ["id"],
            childColumns = ["medicationId"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = ScheduleEntity::class,
            parentColumns = ["id"],
            childColumns = ["scheduleId"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index("medicationId"),
        Index("scheduleId"),
        Index("dueEpochMs"),
        Index("state"),
        Index(value = ["scheduleId", "dueEpochMs"], unique = true),
    ],
)
data class DoseInstanceEntity(
    @PrimaryKey val id: String,

    val medicationId: String,
    val scheduleId: String,

    /**
     * Scheduled alarm fire time as UTC epoch-ms. For windowed doses this is the window-open time.
     * Stored as epoch-ms; zone is re-derived from [ScheduleEntity.zoneId] for display.
     */
    val dueEpochMs: Long,

    /**
     * For [ScheduleType.DOSE_WINDOW] and miss-window calculation: the time at which this dose
     * transitions to MISSED if not acted on. Equals the window-close time for windowed doses,
     * or (dueEpochMs + 60 min) for fixed-time doses (DECISIONS.md D2), or the next dose's
     * dueEpochMs, whichever comes first.
     */
    val windowEndEpochMs: Long?,

    /** Current state in the dose state machine. Stored as [DoseState].name. */
    val state: String,

    /** UTC epoch-ms when the user marked this dose TAKEN. Null until then. */
    val takenEpochMs: Long?,

    /** UTC epoch-ms when the user explicitly SKIPPED this dose. Null until then. */
    val skippedEpochMs: Long?,

    /** UTC epoch-ms when the miss window closed without user action. Null until then. */
    val missedEpochMs: Long?,

    /** UTC epoch-ms of the current snooze target (state = SNOOZED). Null otherwise. */
    val snoozeUntilEpochMs: Long?,

    val createdAtEpochMs: Long,
)

/**
 * States in the dose state machine (spec §2.6).
 *
 * Transitions:
 *   SCHEDULED → DUE (alarm fires)
 *   DUE → TAKEN  (user confirms)
 *   DUE → SNOOZED (user snoozes)
 *   DUE → SKIPPED (user skips)
 *   DUE → MISSED  (miss window closes without action)
 *   SNOOZED → DUE (snooze interval elapses)
 *   SNOOZED → MISSED (miss window closes while snoozed)
 */
enum class DoseState { SCHEDULED, DUE, TAKEN, SNOOZED, SKIPPED, MISSED }
