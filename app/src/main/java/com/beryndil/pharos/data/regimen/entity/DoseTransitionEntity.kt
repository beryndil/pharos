package com.beryndil.pharos.data.regimen.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * An append-only record of ONE state transition of a [DoseInstanceEntity] (spec §2.6, Law 9).
 *
 * ┌─ APPEND-ONLY INVARIANT ─────────────────────────────────────────────────────────────────┐
 * │ Every transition the [com.beryndil.pharos.dose.DoseStateMachine] performs writes ONE new  │
 * │ row here. Rows are NEVER updated or deleted ([DoseTransitionDao] exposes INSERT only).    │
 * │ The [DoseInstanceEntity] row carries the *current* state (a projection for fast queries); │
 * │ this table is the immutable history. Together they satisfy "never overwrite a past        │
 * │ record" — the prior state row is preserved as its own immutable transition entry.        │
 * └─────────────────────────────────────────────────────────────────────────────────────────┘
 *
 * Timestamps are UTC epoch-milliseconds (Standards §2). No advice or instruction is ever stored
 * (Law 3) — only the factual cause of the transition ([DoseTransitionCause]).
 */
@Entity(
    tableName = "dose_transitions",
    foreignKeys = [
        ForeignKey(
            entity = DoseInstanceEntity::class,
            parentColumns = ["id"],
            childColumns = ["doseInstanceId"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = MedicationEntity::class,
            parentColumns = ["id"],
            childColumns = ["medicationId"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [Index("doseInstanceId"), Index("medicationId"), Index("atEpochMs")],
)
data class DoseTransitionEntity(
    /** UUID assigned at insert. Never reused. */
    @PrimaryKey val id: String,

    /** The dose instance this transition belongs to. */
    val doseInstanceId: String,

    /** Denormalized for the per-med history view (avoids a join). */
    val medicationId: String,

    /** Source state ([DoseState].name). Null only for the initial SCHEDULED creation record. */
    val fromState: String?,

    /** Destination state ([DoseState].name). */
    val toState: String,

    /** What caused this transition. Stored as [DoseTransitionCause].name. */
    val cause: String,

    /** UTC epoch-ms when the transition occurred. */
    val atEpochMs: Long,
)

/**
 * The factual trigger of a dose transition. Never an instruction or recommendation (Law 3) —
 * the app records what happened, it does not advise.
 */
enum class DoseTransitionCause {
    /** The exact alarm fired (SCHEDULED → DUE). */
    ALARM_FIRED,

    /** The user explicitly confirmed taking the dose. */
    USER_TAKEN,

    /** The user explicitly deferred the dose. */
    USER_SNOOZED,

    /** The user explicitly skipped the dose. */
    USER_SKIPPED,

    /** A snoozed dose's interval elapsed and it re-entered DUE. */
    SNOOZE_ELAPSED,

    /** The miss window closed with no user action (DUE/SNOOZED → MISSED). */
    MISS_WINDOW_CLOSED,
}
