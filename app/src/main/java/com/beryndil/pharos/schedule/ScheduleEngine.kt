package com.beryndil.pharos.schedule

import com.beryndil.pharos.core.time.DoseClock
import com.beryndil.pharos.data.regimen.entity.DoseInstanceEntity
import com.beryndil.pharos.data.regimen.entity.DoseState
import com.beryndil.pharos.data.regimen.entity.IntervalAnchorType
import com.beryndil.pharos.data.regimen.entity.ScheduleEntity
import com.beryndil.pharos.data.regimen.entity.SchedulePhaseEntity
import com.beryndil.pharos.data.regimen.entity.ScheduleType
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.UUID

/**
 * Pure, side-effect-free schedule instance generator.
 *
 * All time math is delegated to [DoseClock]. No I/O, no Android deps —
 * this object is safe to unit-test on the JVM without Robolectric.
 */
object ScheduleEngine {

    private val json = Json { ignoreUnknownKeys = true }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Generate [DoseInstanceEntity] rows for [schedule] in the wall-clock range [from, to).
     *
     * [existingDueTimes] contains already-persisted dueEpochMs values for this schedule;
     * any instance whose dueEpochMs is in this set is skipped (append-only invariant).
     *
     * [nowMs] is used to stamp createdAtEpochMs on each generated instance.
     *
     * Returns instances sorted by dueEpochMs. A second pass sets windowEndEpochMs on each
     * to min(due + 60 min, nextDue).
     */
    fun generateInstances(
        schedule: ScheduleEntity,
        phases: List<SchedulePhaseEntity>,
        from: Instant,
        to: Instant,
        existingDueTimes: Set<Long>,
        nowMs: Long,
    ): List<DoseInstanceEntity> {
        if (!schedule.isActive) return emptyList()
        if (from >= to) return emptyList()

        val zone = ZoneId.of(schedule.zoneId)
        val effectiveFrom = schedule.startEpochMs
            ?.let { Instant.ofEpochMilli(it) }
            ?.let { if (it.isAfter(from)) it else from }
            ?: from

        val rawEndMs = schedule.endEpochMs
        val effectiveTo = if (rawEndMs != null) {
            val schedEnd = Instant.ofEpochMilli(rawEndMs).plusMillis(86_400_000L) // +24h for inclusivity
            if (schedEnd.isBefore(to)) schedEnd else to
        } else {
            to
        }

        if (effectiveFrom >= effectiveTo) return emptyList()

        val type = runCatching { ScheduleType.valueOf(schedule.type) }.getOrNull()
            ?: return emptyList()

        val dueTimes: List<Long> = when (type) {
            ScheduleType.FIXED_DAILY -> generateFixedDaily(schedule, zone, effectiveFrom, effectiveTo)
            ScheduleType.DAYS_OF_WEEK -> generateDaysOfWeek(schedule, zone, effectiveFrom, effectiveTo)
            ScheduleType.INTERVAL -> generateInterval(schedule, zone, effectiveFrom, effectiveTo)
            ScheduleType.DOSE_WINDOW -> generateDoseWindow(schedule, zone, effectiveFrom, effectiveTo)
            ScheduleType.PRN -> emptyList()
            ScheduleType.TEMPORARY -> generateTemporary(schedule, zone, effectiveFrom, effectiveTo)
            ScheduleType.TAPER -> generateTaper(schedule, phases, zone, effectiveFrom, effectiveTo)
        }

        // Filter out existing, sort
        val filtered = dueTimes
            .filter { it !in existingDueTimes }
            .distinct()
            .sorted()

        if (filtered.isEmpty()) return emptyList()

        // For DOSE_WINDOW, windowEndEpochMs = schedule window close for that day
        // For everything else: second pass sets windowEndEpochMs = min(due+60min, nextDue)
        return if (type == ScheduleType.DOSE_WINDOW) {
            buildDoseWindowInstances(schedule, zone, filtered, existingDueTimes, nowMs)
        } else {
            buildInstancesWithWindowEnd(schedule, filtered, nowMs)
        }
    }

    /**
     * For INTERVAL / LAST_TAKEN schedules: generate the single next instance that should
     * fire after [lastTakenMs]. Called by the alarm engine (Slice 4) after a dose is taken.
     *
     * Returns null if the schedule is not INTERVAL/LAST_TAKEN or if generation produces
     * no new instance (e.g. past endEpochMs).
     */
    fun generateNextLastTakenInstance(
        schedule: ScheduleEntity,
        lastTakenMs: Long,
        existingDueTimes: Set<Long>,
        nowMs: Long,
    ): DoseInstanceEntity? {
        val type = runCatching { ScheduleType.valueOf(schedule.type) }.getOrNull()
        if (type != ScheduleType.INTERVAL) return null
        val anchorType = runCatching { IntervalAnchorType.valueOf(schedule.intervalAnchorType ?: "") }
            .getOrNull()
        if (anchorType != IntervalAnchorType.LAST_TAKEN) return null

        val intervalMs = (schedule.intervalHours ?: 8).toLong() * 3_600_000L
        val nextDueMs = lastTakenMs + intervalMs

        // Check end boundary
        val endMs = schedule.endEpochMs
        if (endMs != null && nextDueMs > endMs + 86_400_000L) return null

        if (nextDueMs in existingDueTimes) return null

        // Apply daily window if configured
        if (schedule.windowStartTime != null && schedule.windowEndTime != null) {
            val zone = ZoneId.of(schedule.zoneId)
            val nextInstant = Instant.ofEpochMilli(nextDueMs)
            val zdt = ZonedDateTime.ofInstant(nextInstant, zone)
            val windowStart = parseTime(schedule.windowStartTime)
            val windowEnd = parseTime(schedule.windowEndTime)
            if (windowStart != null && windowEnd != null) {
                val localTime = zdt.toLocalTime()
                if (localTime.isBefore(windowStart) || !localTime.isBefore(windowEnd)) return null
            }
        }

        val windowEndMs = nextDueMs + 3_600_000L

        return DoseInstanceEntity(
            id = UUID.randomUUID().toString(),
            medicationId = schedule.medicationId,
            scheduleId = schedule.id,
            dueEpochMs = nextDueMs,
            windowEndEpochMs = windowEndMs,
            state = DoseState.SCHEDULED.name,
            takenEpochMs = null,
            skippedEpochMs = null,
            missedEpochMs = null,
            snoozeUntilEpochMs = null,
            createdAtEpochMs = nowMs,
        )
    }

    // ── Type-specific generators ──────────────────────────────────────────────

    private fun generateFixedDaily(
        schedule: ScheduleEntity,
        zone: ZoneId,
        from: Instant,
        to: Instant,
    ): List<Long> {
        val times = parseTimes(schedule.scheduledTimesJson)
        if (times.isEmpty()) return emptyList()
        return times.flatMap { time ->
            DoseClock.dailyOccurrencesInRange(time, zone, from, to)
        }.map { it.toEpochMilli() }
    }

    private fun generateDaysOfWeek(
        schedule: ScheduleEntity,
        zone: ZoneId,
        from: Instant,
        to: Instant,
    ): List<Long> {
        val times = parseTimes(schedule.scheduledTimesJson)
        if (times.isEmpty()) return emptyList()
        val allowedDays = parseDaysOfWeek(schedule.daysOfWeekJson)
        if (allowedDays.isEmpty()) return emptyList()

        return times.flatMap { time ->
            DoseClock.dailyOccurrencesInRange(time, zone, from, to)
                .filter { instant ->
                    val dow = ZonedDateTime.ofInstant(instant, zone).dayOfWeek.value
                    dow in allowedDays
                }
        }.map { it.toEpochMilli() }
    }

    private fun generateInterval(
        schedule: ScheduleEntity,
        zone: ZoneId,
        from: Instant,
        to: Instant,
    ): List<Long> {
        val anchorType = runCatching { IntervalAnchorType.valueOf(schedule.intervalAnchorType ?: "") }
            .getOrElse { IntervalAnchorType.SCHEDULE_ANCHORED }
        val intervalMs = (schedule.intervalHours ?: 8).toLong() * 3_600_000L

        if (anchorType == IntervalAnchorType.LAST_TAKEN) {
            // LAST_TAKEN: generate only the first instance (Slice 4 handles subsequent)
            val anchorTime = parseTimes(schedule.scheduledTimesJson).firstOrNull()
                ?: LocalTime.of(8, 0)
            val startDate = schedule.startEpochMs
                ?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }
                ?: from.atZone(zone).toLocalDate()
            val anchorInstant = ZonedDateTime.of(startDate, anchorTime, zone).toInstant()
            val anchorMs = anchorInstant.toEpochMilli()

            // Find the first occurrence after 'from'
            val fromMs = from.toEpochMilli()
            val toMs = to.toEpochMilli()
            if (anchorMs >= fromMs && anchorMs < toMs) return listOf(anchorMs)
            if (anchorMs < fromMs) {
                val diff = fromMs - anchorMs
                val steps = diff / intervalMs + 1
                val nextMs = anchorMs + steps * intervalMs
                if (nextMs < toMs) return listOf(nextMs)
            }
            return emptyList()
        }

        // SCHEDULE_ANCHORED
        val anchorTime = parseTimes(schedule.scheduledTimesJson).firstOrNull()
            ?: LocalTime.of(8, 0)
        val startDate = schedule.startEpochMs
            ?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }
            ?: from.atZone(zone).toLocalDate()
        val anchorInstant = ZonedDateTime.of(startDate, anchorTime, zone).toInstant()
        val anchorMs = anchorInstant.toEpochMilli()

        val windowStart = schedule.windowStartTime?.let { parseTime(it) }
        val windowEnd = schedule.windowEndTime?.let { parseTime(it) }

        return DoseClock.intervalOccurrencesInRange(
            anchorMs = anchorMs,
            intervalMs = intervalMs,
            zone = zone,
            from = from,
            to = to,
            windowStart = windowStart,
            windowEnd = windowEnd,
        ).map { it.toEpochMilli() }
    }

    private fun generateDoseWindow(
        schedule: ScheduleEntity,
        zone: ZoneId,
        from: Instant,
        to: Instant,
    ): List<Long> {
        val windowStart = parseTime(schedule.windowStartTime ?: return emptyList())
            ?: return emptyList()
        // Return the window-open times (one per day); windowEndEpochMs handled separately
        return DoseClock.dailyOccurrencesInRange(windowStart, zone, from, to)
            .map { it.toEpochMilli() }
    }

    private fun generateTemporary(
        schedule: ScheduleEntity,
        zone: ZoneId,
        from: Instant,
        to: Instant,
    ): List<Long> {
        // Same as FIXED_DAILY; effectiveTo already clamps to endEpochMs
        return generateFixedDaily(schedule, zone, from, to)
    }

    private fun generateTaper(
        schedule: ScheduleEntity,
        phases: List<SchedulePhaseEntity>,
        zone: ZoneId,
        from: Instant,
        to: Instant,
    ): List<Long> {
        if (phases.isEmpty()) return emptyList()

        val startDate = schedule.startEpochMs
            ?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }
            ?: from.atZone(zone).toLocalDate()

        val result = mutableListOf<Long>()
        var phaseStartDate: LocalDate = startDate

        val sortedPhases = phases.sortedBy { it.phaseOrder }
        for (phase in sortedPhases) {
            val phaseTimes = parseTimes(phase.scheduledTimesJson)
            val phaseEndDate = phaseStartDate.plusDays(phase.durationDays.toLong())
            val phaseFrom = ZonedDateTime.of(phaseStartDate, LocalTime.MIDNIGHT, zone).toInstant()
                .let { if (it.isBefore(from)) from else it }
            val phaseTo = ZonedDateTime.of(phaseEndDate, LocalTime.MIDNIGHT, zone).toInstant()
                .let { if (it.isAfter(to)) to else it }

            if (phaseFrom < phaseTo) {
                phaseTimes.forEach { time ->
                    result += DoseClock.dailyOccurrencesInRange(time, zone, phaseFrom, phaseTo)
                        .map { it.toEpochMilli() }
                }
            }
            phaseStartDate = phaseEndDate
        }
        return result
    }

    // ── Instance builders ─────────────────────────────────────────────────────

    private fun buildInstancesWithWindowEnd(
        schedule: ScheduleEntity,
        sortedDueTimes: List<Long>,
        nowMs: Long,
    ): List<DoseInstanceEntity> {
        return sortedDueTimes.mapIndexed { index, dueMs ->
            val nextDueMs = if (index + 1 < sortedDueTimes.size) sortedDueTimes[index + 1] else null
            val windowEndMs = if (nextDueMs != null) {
                minOf(dueMs + 3_600_000L, nextDueMs)
            } else {
                dueMs + 3_600_000L
            }
            DoseInstanceEntity(
                id = UUID.randomUUID().toString(),
                medicationId = schedule.medicationId,
                scheduleId = schedule.id,
                dueEpochMs = dueMs,
                windowEndEpochMs = windowEndMs,
                state = DoseState.SCHEDULED.name,
                takenEpochMs = null,
                skippedEpochMs = null,
                missedEpochMs = null,
                snoozeUntilEpochMs = null,
                createdAtEpochMs = nowMs,
            )
        }
    }

    private fun buildDoseWindowInstances(
        schedule: ScheduleEntity,
        zone: ZoneId,
        sortedDueTimes: List<Long>,
        existingDueTimes: Set<Long>,
        nowMs: Long,
    ): List<DoseInstanceEntity> {
        val windowEndTime = parseTime(schedule.windowEndTime ?: return emptyList())
            ?: return emptyList()

        return sortedDueTimes.map { dueMs ->
            // windowEndEpochMs = window close time on the same day as dueMs
            val dueInstant = Instant.ofEpochMilli(dueMs)
            val dueDate = ZonedDateTime.ofInstant(dueInstant, zone).toLocalDate()
            val windowEndMs = ZonedDateTime.of(dueDate, windowEndTime, zone).toInstant().toEpochMilli()

            DoseInstanceEntity(
                id = UUID.randomUUID().toString(),
                medicationId = schedule.medicationId,
                scheduleId = schedule.id,
                dueEpochMs = dueMs,
                windowEndEpochMs = windowEndMs,
                state = DoseState.SCHEDULED.name,
                takenEpochMs = null,
                skippedEpochMs = null,
                missedEpochMs = null,
                snoozeUntilEpochMs = null,
                createdAtEpochMs = nowMs,
            )
        }
    }

    // ── Parsing helpers ───────────────────────────────────────────────────────

    internal fun parseTimes(jsonOrNull: String?): List<LocalTime> {
        if (jsonOrNull.isNullOrBlank()) return emptyList()
        return try {
            json.decodeFromString<List<String>>(jsonOrNull)
                .mapNotNull { parseTime(it) }
        } catch (_: Exception) {
            emptyList()
        }
    }

    internal fun parseTime(hhmm: String): LocalTime? = try {
        LocalTime.parse(hhmm)
    } catch (_: Exception) {
        null
    }

    private fun parseDaysOfWeek(jsonOrNull: String?): Set<Int> {
        if (jsonOrNull.isNullOrBlank()) return emptySet()
        return try {
            json.decodeFromString<List<Int>>(jsonOrNull).toSet()
        } catch (_: Exception) {
            emptySet()
        }
    }
}
