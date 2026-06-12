package com.beryndil.pharos.data.schedule

import com.beryndil.pharos.data.regimen.dao.DoseInstanceDao
import com.beryndil.pharos.data.regimen.dao.ScheduleDao
import com.beryndil.pharos.data.regimen.dao.SchedulePhaseDao
import com.beryndil.pharos.data.regimen.entity.DoseInstanceEntity
import com.beryndil.pharos.data.regimen.entity.IntervalAnchorType
import com.beryndil.pharos.data.regimen.entity.ScheduleEntity
import com.beryndil.pharos.data.regimen.entity.SchedulePhaseEntity
import com.beryndil.pharos.data.regimen.entity.ScheduleType
import com.beryndil.pharos.schedule.ScheduleEngine
import com.beryndil.pharos.schedule.model.PhaseInput
import com.beryndil.pharos.schedule.model.ScheduleInput
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.UUID

/**
 * Repository for schedule management (Slice 3).
 *
 * Bridges the DAO layer and ScheduleEngine. All suspend functions must be
 * called from a [kotlinx.coroutines.Dispatchers.IO] coroutine.
 */
class ScheduleRepository(
    private val scheduleDao: ScheduleDao,
    private val schedulePhaseDao: SchedulePhaseDao,
    private val doseInstanceDao: DoseInstanceDao,
) {

    private val json = Json { ignoreUnknownKeys = true }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Replace the active schedule for [medId] with one built from [input].
     *
     * Steps:
     * 1. Deactivate all currently active schedules for medId.
     * 2. Insert the new ScheduleEntity (and phases for TAPER).
     * 3. Generate dose instances for [generationDays] days and insert them.
     */
    suspend fun saveSchedule(
        medId: String,
        input: ScheduleInput,
        startDate: LocalDate,
        endDate: LocalDate?,
        zoneId: ZoneId,
        generationDays: Int = 90,
    ) {
        val nowMs = System.currentTimeMillis()

        // 1. Deactivate old active schedules
        val oldActive = scheduleDao.getActiveByMedicationOnce(medId)
        for (old in oldActive) {
            scheduleDao.deactivate(old.id)
        }

        // 2. Build and insert the new schedule
        val entity = inputToEntity(input, medId, startDate, endDate, zoneId, nowMs)
        scheduleDao.insert(entity)

        // 3. For TAPER, insert phases
        if (input.type == ScheduleType.TAPER) {
            val phases = inputToPhasesForTaper(input, entity.id)
            schedulePhaseDao.insertAll(phases)
        }

        // 4. Generate instances
        val from = ZonedDateTime.of(startDate, java.time.LocalTime.MIDNIGHT, zoneId).toInstant()
        val to = from.plusMillis(generationDays.toLong() * 86_400_000L)

        val phases = if (input.type == ScheduleType.TAPER) {
            schedulePhaseDao.getPhasesForSchedule(entity.id)
        } else {
            emptyList()
        }

        val instances = ScheduleEngine.generateInstances(
            schedule = entity,
            phases = phases,
            from = from,
            to = to,
            existingDueTimes = emptySet(),
            nowMs = nowMs,
        )

        for (instance in instances) {
            doseInstanceDao.insert(instance)
        }
    }

    /**
     * Extend the generated dose instances for [medId] covering [from, to).
     * Only inserts instances not already persisted (append-only, deduped by dueEpochMs).
     */
    suspend fun generateInstancesForMed(
        medId: String,
        from: Instant,
        to: Instant,
    ) {
        val activeSchedules = scheduleDao.getActiveByMedicationOnce(medId)
        if (activeSchedules.isEmpty()) return

        val nowMs = System.currentTimeMillis()

        for (schedule in activeSchedules) {
            val existingDueTimes = doseInstanceDao.getDueTimesForSchedule(schedule.id).toSet()
            val phases = if (schedule.type == ScheduleType.TAPER.name) {
                schedulePhaseDao.getPhasesForSchedule(schedule.id)
            } else {
                emptyList()
            }

            val instances = ScheduleEngine.generateInstances(
                schedule = schedule,
                phases = phases,
                from = from,
                to = to,
                existingDueTimes = existingDueTimes,
                nowMs = nowMs,
            )

            for (instance in instances) {
                doseInstanceDao.insert(instance)
            }
        }
    }

    /** Returns the currently active schedule for [medId], or null if none. */
    suspend fun getActiveSchedule(medId: String): ScheduleEntity? =
        scheduleDao.getActiveByMedicationOnce(medId).firstOrNull()

    /** Returns the phases for [scheduleId] in phase order. */
    suspend fun getSchedulePhases(scheduleId: String): List<SchedulePhaseEntity> =
        schedulePhaseDao.getPhasesForSchedule(scheduleId)

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun inputToEntity(
        input: ScheduleInput,
        medId: String,
        startDate: LocalDate,
        endDate: LocalDate?,
        zoneId: ZoneId,
        nowMs: Long,
    ): ScheduleEntity {
        val id = UUID.randomUUID().toString()
        val zone = zoneId.id

        val startMs = ZonedDateTime.of(startDate, java.time.LocalTime.MIDNIGHT, zoneId)
            .toInstant().toEpochMilli()
        val endMs = endDate?.let {
            ZonedDateTime.of(it, java.time.LocalTime.MIDNIGHT, zoneId).toInstant().toEpochMilli()
        }

        return when (input.type) {
            ScheduleType.FIXED_DAILY -> ScheduleEntity(
                id = id,
                medicationId = medId,
                type = input.type.name,
                scheduledTimesJson = encodeTimes(input.times),
                daysOfWeekJson = null,
                intervalHours = null,
                intervalAnchorType = null,
                windowStartTime = null,
                windowEndTime = null,
                dailyMaxDoses = null,
                zoneId = zone,
                isActive = true,
                startEpochMs = startMs,
                endEpochMs = endMs,
                createdAtEpochMs = nowMs,
            )

            ScheduleType.DAYS_OF_WEEK -> ScheduleEntity(
                id = id,
                medicationId = medId,
                type = input.type.name,
                scheduledTimesJson = encodeTimes(input.times),
                daysOfWeekJson = encodeDaysOfWeek(input.daysOfWeek),
                intervalHours = null,
                intervalAnchorType = null,
                windowStartTime = null,
                windowEndTime = null,
                dailyMaxDoses = null,
                zoneId = zone,
                isActive = true,
                startEpochMs = startMs,
                endEpochMs = endMs,
                createdAtEpochMs = nowMs,
            )

            ScheduleType.INTERVAL -> ScheduleEntity(
                id = id,
                medicationId = medId,
                type = input.type.name,
                // First dose anchor time as 1-element array
                scheduledTimesJson = encodeTimes(input.times.take(1).ifEmpty { listOf(java.time.LocalTime.of(8, 0)) }),
                daysOfWeekJson = null,
                intervalHours = input.intervalHours,
                intervalAnchorType = input.intervalAnchor.name,
                windowStartTime = if (input.intervalWindowEnabled) formatTime(input.intervalWindowStart) else null,
                windowEndTime = if (input.intervalWindowEnabled) formatTime(input.intervalWindowEnd) else null,
                dailyMaxDoses = null,
                zoneId = zone,
                isActive = true,
                startEpochMs = startMs,
                endEpochMs = endMs,
                createdAtEpochMs = nowMs,
            )

            ScheduleType.DOSE_WINDOW -> ScheduleEntity(
                id = id,
                medicationId = medId,
                type = input.type.name,
                scheduledTimesJson = null,
                daysOfWeekJson = null,
                intervalHours = null,
                intervalAnchorType = null,
                windowStartTime = formatTime(input.windowStart),
                windowEndTime = formatTime(input.windowEnd),
                dailyMaxDoses = null,
                zoneId = zone,
                isActive = true,
                startEpochMs = startMs,
                endEpochMs = endMs,
                createdAtEpochMs = nowMs,
            )

            ScheduleType.PRN -> ScheduleEntity(
                id = id,
                medicationId = medId,
                type = input.type.name,
                scheduledTimesJson = null,
                daysOfWeekJson = null,
                intervalHours = null,
                intervalAnchorType = null,
                windowStartTime = null,
                windowEndTime = null,
                dailyMaxDoses = input.dailyMaxDoses,
                zoneId = zone,
                isActive = true,
                startEpochMs = startMs,
                endEpochMs = endMs,
                createdAtEpochMs = nowMs,
            )

            ScheduleType.TEMPORARY -> ScheduleEntity(
                id = id,
                medicationId = medId,
                type = input.type.name,
                scheduledTimesJson = encodeTimes(input.times),
                daysOfWeekJson = null,
                intervalHours = null,
                intervalAnchorType = null,
                windowStartTime = null,
                windowEndTime = null,
                dailyMaxDoses = null,
                zoneId = zone,
                isActive = true,
                startEpochMs = startMs,
                endEpochMs = endMs,
                createdAtEpochMs = nowMs,
            )

            ScheduleType.TAPER -> ScheduleEntity(
                id = id,
                medicationId = medId,
                type = input.type.name,
                scheduledTimesJson = null,
                daysOfWeekJson = null,
                intervalHours = null,
                intervalAnchorType = null,
                windowStartTime = null,
                windowEndTime = null,
                dailyMaxDoses = null,
                zoneId = zone,
                isActive = true,
                startEpochMs = startMs,
                endEpochMs = endMs,
                createdAtEpochMs = nowMs,
            )
        }
    }

    private fun inputToPhasesForTaper(
        input: ScheduleInput,
        scheduleId: String,
    ): List<SchedulePhaseEntity> {
        return input.phases.mapIndexed { index, phase ->
            SchedulePhaseEntity(
                id = UUID.randomUUID().toString(),
                scheduleId = scheduleId,
                phaseOrder = index,
                doseDescription = phase.doseDescription,
                durationDays = phase.durationDays,
                scheduledTimesJson = encodeTimes(phase.times),
            )
        }
    }

    // ── JSON / format helpers ─────────────────────────────────────────────────

    private fun encodeTimes(times: List<java.time.LocalTime>): String =
        json.encodeToString(times.map { "%02d:%02d".format(it.hour, it.minute) })

    private fun encodeDaysOfWeek(days: Set<java.time.DayOfWeek>): String =
        json.encodeToString(days.map { it.value }.sorted())

    private fun formatTime(time: java.time.LocalTime): String =
        "%02d:%02d".format(time.hour, time.minute)

    // ── Entity → ScheduleInput (for loading existing schedules) ──────────────

    /**
     * Reconstruct a [ScheduleInput] from a persisted [ScheduleEntity] and its [phases].
     * Used by [AddEditMedicationViewModel] when loading an existing medication for editing.
     */
    fun entityToScheduleInput(
        entity: ScheduleEntity,
        phases: List<SchedulePhaseEntity>,
    ): ScheduleInput {
        val type = runCatching { ScheduleType.valueOf(entity.type) }
            .getOrElse { ScheduleType.FIXED_DAILY }

        val times = parseTimes(entity.scheduledTimesJson)

        return when (type) {
            ScheduleType.FIXED_DAILY -> ScheduleInput(
                type = type,
                times = times.ifEmpty { listOf(java.time.LocalTime.of(8, 0)) },
            )

            ScheduleType.DAYS_OF_WEEK -> ScheduleInput(
                type = type,
                times = times.ifEmpty { listOf(java.time.LocalTime.of(8, 0)) },
                daysOfWeek = parseDayValues(entity.daysOfWeekJson),
            )

            ScheduleType.INTERVAL -> {
                val anchorType = runCatching { IntervalAnchorType.valueOf(entity.intervalAnchorType ?: "") }
                    .getOrElse { IntervalAnchorType.SCHEDULE_ANCHORED }
                val windowStart = entity.windowStartTime?.let { parseTime(it) }
                val windowEnd = entity.windowEndTime?.let { parseTime(it) }
                ScheduleInput(
                    type = type,
                    times = times.ifEmpty { listOf(java.time.LocalTime.of(8, 0)) },
                    intervalHours = entity.intervalHours ?: 8,
                    intervalAnchor = anchorType,
                    intervalWindowEnabled = windowStart != null && windowEnd != null,
                    intervalWindowStart = windowStart ?: java.time.LocalTime.of(8, 0),
                    intervalWindowEnd = windowEnd ?: java.time.LocalTime.of(22, 0),
                )
            }

            ScheduleType.DOSE_WINDOW -> {
                val windowStart = entity.windowStartTime?.let { parseTime(it) }
                    ?: java.time.LocalTime.of(8, 0)
                val windowEnd = entity.windowEndTime?.let { parseTime(it) }
                    ?: java.time.LocalTime.of(9, 0)
                ScheduleInput(type = type, windowStart = windowStart, windowEnd = windowEnd)
            }

            ScheduleType.PRN -> ScheduleInput(
                type = type,
                dailyMaxDoses = entity.dailyMaxDoses,
            )

            ScheduleType.TEMPORARY -> ScheduleInput(
                type = type,
                times = times.ifEmpty { listOf(java.time.LocalTime.of(8, 0)) },
            )

            ScheduleType.TAPER -> ScheduleInput(
                type = type,
                phases = phases.sortedBy { it.phaseOrder }.map { phase ->
                    PhaseInput(
                        id = UUID.randomUUID().toString(),
                        doseDescription = phase.doseDescription,
                        durationDays = phase.durationDays,
                        times = parseTimes(phase.scheduledTimesJson).ifEmpty {
                            listOf(java.time.LocalTime.of(8, 0))
                        },
                    )
                }.ifEmpty { listOf(PhaseInput()) },
            )
        }
    }

    private fun parseTimes(jsonOrNull: String?): List<java.time.LocalTime> {
        if (jsonOrNull.isNullOrBlank()) return emptyList()
        return try {
            json.decodeFromString<List<String>>(jsonOrNull)
                .mapNotNull { parseTime(it) }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun parseTime(hhmm: String): java.time.LocalTime? = try {
        java.time.LocalTime.parse(hhmm)
    } catch (_: Exception) {
        null
    }

    private fun parseDayValues(jsonOrNull: String?): Set<java.time.DayOfWeek> {
        if (jsonOrNull.isNullOrBlank()) return emptySet()
        return try {
            json.decodeFromString<List<Int>>(jsonOrNull)
                .mapNotNull { runCatching { java.time.DayOfWeek.of(it) }.getOrNull() }
                .toSet()
        } catch (_: Exception) {
            emptySet()
        }
    }
}
