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

    /**
     * All daily occurrences of [time] in [zone] within [from, to) (to is exclusive).
     *
     * DST-correct: uses [ZonedDateTime.of] which resolves spring-forward gaps by shifting
     * forward, and fall-back overlaps by using the earlier (pre-transition) offset.
     * This matches the wall-clock intent: if a dose is scheduled for 08:00, it fires at
     * 08:00 local time even across DST boundaries.
     */
    fun dailyOccurrencesInRange(
        time: LocalTime,
        zone: ZoneId,
        from: Instant,
        to: Instant,
    ): List<Instant> {
        if (from >= to) return emptyList()

        val result = mutableListOf<Instant>()
        // Start from the date of 'from' in the target zone; we'll discard occurrences < from
        var candidateDate = ZonedDateTime.ofInstant(from, zone).toLocalDate()
        val toMs = to.toEpochMilli()

        while (true) {
            val zdt = ZonedDateTime.of(candidateDate, time, zone)
            val instant = zdt.toInstant()
            if (instant.toEpochMilli() >= toMs) break
            if (!instant.isBefore(from)) {
                result += instant
            }
            candidateDate = candidateDate.plusDays(1)
        }
        return result
    }

    /**
     * Interval occurrences: anchor + k*intervalMs, filtered to [from, to) (to is exclusive).
     *
     * If [windowStart] and [windowEnd] are both non-null, occurrences outside the daily window
     * (in [zone]) are dropped. The window is checked against the local wall-clock time of the
     * candidate instant.
     *
     * [anchorMs] is the first anchor point in epoch-ms. k is chosen so that the first
     * candidate >= from is returned.
     */
    fun intervalOccurrencesInRange(
        anchorMs: Long,
        intervalMs: Long,
        zone: ZoneId,
        from: Instant,
        to: Instant,
        windowStart: LocalTime? = null,
        windowEnd: LocalTime? = null,
    ): List<Instant> {
        if (from >= to || intervalMs <= 0) return emptyList()

        val fromMs = from.toEpochMilli()
        val toMs = to.toEpochMilli()

        // Find starting k such that anchorMs + k*intervalMs >= fromMs
        val firstK = if (anchorMs >= fromMs) 0L else {
            (fromMs - anchorMs + intervalMs - 1) / intervalMs
        }

        val result = mutableListOf<Instant>()
        var k = firstK
        while (true) {
            val candidateMs = anchorMs + k * intervalMs
            if (candidateMs >= toMs) break

            val instant = Instant.ofEpochMilli(candidateMs)

            // Apply daily window filter if configured
            if (windowStart != null && windowEnd != null) {
                val localTime = ZonedDateTime.ofInstant(instant, zone).toLocalTime()
                val inWindow = if (windowStart <= windowEnd) {
                    !localTime.isBefore(windowStart) && localTime.isBefore(windowEnd)
                } else {
                    // window wraps midnight
                    !localTime.isBefore(windowStart) || localTime.isBefore(windowEnd)
                }
                if (inWindow) result += instant
            } else {
                result += instant
            }
            k++
        }
        return result
    }
}
