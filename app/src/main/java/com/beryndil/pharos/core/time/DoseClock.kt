package com.beryndil.pharos.core.time

import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Time math for schedule-anchored dosing. All scheduling is computed from local wall-clock
 * time + zone, NOT from stored epochs, so a DST shift moves the alarm to the correct wall time.
 * Storage is always a locale-independent [Instant]; display formatting lives elsewhere.
 */
object DoseClock {
    /**
     * The next [Instant] at which [time] occurs in [zone], strictly after [after].
     * DST-correct: spring-forward gaps and fall-back overlaps are resolved by java.time's
     * ZonedDateTime rules (gap -> shifted forward; overlap -> earlier offset).
     */
    fun nextOccurrence(time: LocalTime, zone: ZoneId, after: Instant): Instant {
        var candidateDate = ZonedDateTime.ofInstant(after, zone).toLocalDate()
        repeat(3) { // at most a couple iterations to step past today/gap
            val zdt = ZonedDateTime.of(candidateDate, time, zone)
            if (zdt.toInstant().isAfter(after)) return zdt.toInstant()
            candidateDate = candidateDate.plusDays(1)
        }
        return ZonedDateTime.of(candidateDate, time, zone).toInstant()
    }
}
