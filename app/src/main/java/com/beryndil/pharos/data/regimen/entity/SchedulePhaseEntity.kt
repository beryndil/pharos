package com.beryndil.pharos.data.regimen.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One phase of a [ScheduleType.TAPER] schedule (e.g., prednisone pack).
 *
 * Phases are child rows of [ScheduleEntity]. Because parent schedules are APPEND-ONLY
 * (a change creates a new parent row), phase rows are also effectively immutable — they
 * belong to a specific immutable parent version. No UPDATE or DELETE is permitted.
 *
 * Example (prednisone taper):
 *  phase 1: "2 tablets", 5 days, ["08:00","12:00"]
 *  phase 2: "1 tablet",  5 days, ["08:00"]
 *  phase 3: "1 tablet",  3 days, ["08:00"] (every other day modelled by custom logic later)
 */
@Entity(
    tableName = "schedule_phases",
    foreignKeys = [
        ForeignKey(
            entity = ScheduleEntity::class,
            parentColumns = ["id"],
            childColumns = ["scheduleId"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [Index("scheduleId")],
)
data class SchedulePhaseEntity(
    @PrimaryKey val id: String,

    val scheduleId: String,

    /** 0-based ordering index. Lower = earlier in the taper. */
    val phaseOrder: Int,

    /** Human-readable dose description for this phase, e.g., "2 tablets". */
    val doseDescription: String,

    /** How many calendar days this phase lasts. */
    val durationDays: Int,

    /**
     * JSON array of daily alarm times for this phase as ISO HH:mm strings.
     * Example: `["08:00","20:00"]`.
     */
    val scheduledTimesJson: String,
)
