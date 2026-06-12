package com.beryndil.pharos.schedule.model

import androidx.compose.runtime.Immutable
import com.beryndil.pharos.data.regimen.entity.IntervalAnchorType
import com.beryndil.pharos.data.regimen.entity.ScheduleType
import java.time.DayOfWeek
import java.time.LocalTime
import java.util.UUID

@Immutable
data class ScheduleInput(
    val type: ScheduleType = ScheduleType.FIXED_DAILY,

    // FIXED_DAILY, DAYS_OF_WEEK, TEMPORARY, and TAPER phase times
    val times: List<LocalTime> = listOf(LocalTime.of(8, 0)),

    // DAYS_OF_WEEK
    val daysOfWeek: Set<DayOfWeek> = emptySet(),

    // INTERVAL
    val intervalHours: Int = 8,
    val intervalAnchor: IntervalAnchorType = IntervalAnchorType.SCHEDULE_ANCHORED,
    val intervalWindowEnabled: Boolean = false,
    val intervalWindowStart: LocalTime = LocalTime.of(8, 0),
    val intervalWindowEnd: LocalTime = LocalTime.of(22, 0),

    // DOSE_WINDOW
    val windowStart: LocalTime = LocalTime.of(8, 0),
    val windowEnd: LocalTime = LocalTime.of(9, 0),

    // PRN
    val dailyMaxDoses: Int? = null,

    // TAPER
    val phases: List<PhaseInput> = listOf(PhaseInput()),
)

@Immutable
data class PhaseInput(
    val id: String = UUID.randomUUID().toString(),
    val doseDescription: String = "",
    val durationDays: Int = 7,
    val times: List<LocalTime> = listOf(LocalTime.of(8, 0)),
)
