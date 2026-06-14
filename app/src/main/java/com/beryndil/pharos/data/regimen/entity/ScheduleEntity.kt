package com.beryndil.pharos.data.regimen.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A schedule that generates dose instances for a medication.
 *
 * APPEND-ONLY INVARIANT: schedule rows are NEVER updated in place. When a schedule
 * changes (e.g., user shifts from 08:00 to 09:00), the current row is deactivated
 * ([isActive] = false) and a NEW row is inserted. Both rows persist as historical records.
 * The [ScheduleDao] enforces this: it has no full-row UPDATE method.
 *
 * For [ScheduleType.TAPER] schedules, the ordered phases live in [SchedulePhaseEntity] child rows.
 *
 * Zone handling (Standards §2): [zoneId] stores the IANA zone string at schedule creation time
 * (e.g., "America/New_York"). All epoch-ms timestamps in [DoseInstanceEntity] are derived
 * from [zoneId] + wall-clock times through [com.beryndil.pharos.core.time.DoseClock].
 */
@Entity(
    tableName = "schedules",
    foreignKeys = [
        ForeignKey(
            entity = MedicationEntity::class,
            parentColumns = ["id"],
            childColumns = ["medicationId"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [Index("medicationId")],
)
data class ScheduleEntity(
    /** UUID assigned at creation. Never reused. */
    @PrimaryKey val id: String,

    val medicationId: String,

    /** Schedule type. Stored as [ScheduleType].name. */
    val type: String,

    /**
     * JSON array of wall-clock times for [ScheduleType.FIXED_DAILY] and
     * [ScheduleType.DAYS_OF_WEEK]. Format: `["08:00","20:00"]` (ISO HH:mm).
     * Null for other schedule types.
     */
    val scheduledTimesJson: String?,

    /**
     * JSON array of ISO weekday numbers (1=Monday … 7=Sunday) for [ScheduleType.DAYS_OF_WEEK].
     * Example: `[1,3,5]` = Mon/Wed/Fri. Null for other types.
     */
    val daysOfWeekJson: String?,

    /** Interval in hours for [ScheduleType.INTERVAL]. Null for other types. */
    val intervalHours: Int?,

    /**
     * How the interval next-dose is anchored (DECISIONS.md D1).
     * Stored as [IntervalAnchorType].name. Null for non-interval schedules.
     */
    val intervalAnchorType: String?,

    /**
     * Window open time as ISO HH:mm (e.g., "07:00") for [ScheduleType.DOSE_WINDOW].
     * Null for non-window schedules.
     */
    val windowStartTime: String?,

    /**
     * Window close time as ISO HH:mm (e.g., "09:00") for [ScheduleType.DOSE_WINDOW].
     * When the window closes with no user action the dose transitions to MISSED.
     * Null for non-window schedules.
     */
    val windowEndTime: String?,

    /**
     * Optional daily-maximum dose count for [ScheduleType.PRN] warning (spec §2.7).
     * Null = no daily-max warning configured.
     */
    val dailyMaxDoses: Int?,

    /**
     * Optional indication for [ScheduleType.PRN] schedules (user's own words).
     * Shown on the Today screen as "As needed for {indication}". Null = no indication set.
     */
    val indication: String? = null,

    /** IANA zone string at schedule creation time. Used for all alarm math via DoseClock. */
    val zoneId: String,

    /**
     * True while this version of the schedule is current.
     * False after supersession — row is kept for historical reference.
     */
    val isActive: Boolean,

    /** Effective start date of this schedule as UTC epoch-ms (inclusive). */
    val startEpochMs: Long?,

    /** Effective end date of this schedule as UTC epoch-ms (inclusive), or null for ongoing. */
    val endEpochMs: Long?,

    val createdAtEpochMs: Long,
)

/** Types of dose schedules. Covers all required v1 schedule types (spec §2.5). */
enum class ScheduleType {
    FIXED_DAILY, DAYS_OF_WEEK, INTERVAL, DOSE_WINDOW, PRN, TEMPORARY, TAPER
}

/** How interval scheduling computes the next dose time (DECISIONS.md D1). */
enum class IntervalAnchorType {
    /** Next dose = schedule origin + (N × interval). DST-safe, predictable. */
    SCHEDULE_ANCHORED,

    /** Next dose = last-taken timestamp + interval. Per-med opt-in only. */
    LAST_TAKEN,
}
