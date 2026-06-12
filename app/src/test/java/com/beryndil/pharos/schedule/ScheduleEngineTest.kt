package com.beryndil.pharos.schedule

import com.beryndil.pharos.data.regimen.entity.DoseState
import com.beryndil.pharos.data.regimen.entity.IntervalAnchorType
import com.beryndil.pharos.data.regimen.entity.ScheduleEntity
import com.beryndil.pharos.data.regimen.entity.SchedulePhaseEntity
import com.beryndil.pharos.data.regimen.entity.ScheduleType
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.UUID

class ScheduleEngineTest {

    private val NY = ZoneId.of("America/New_York")
    private val UTC = ZoneId.of("UTC")

    // ── helpers ───────────────────────────────────────────────────────────────

    private fun json(vararg times: String): String = Json.encodeToString(times.toList())
    private fun daysJson(vararg days: Int): String = Json.encodeToString(days.toList())

    private fun schedule(
        type: ScheduleType,
        zone: ZoneId = NY,
        scheduledTimesJson: String? = null,
        daysOfWeekJson: String? = null,
        intervalHours: Int? = null,
        intervalAnchorType: String? = null,
        windowStartTime: String? = null,
        windowEndTime: String? = null,
        dailyMaxDoses: Int? = null,
        startEpochMs: Long? = null,
        endEpochMs: Long? = null,
        isActive: Boolean = true,
    ) = ScheduleEntity(
        id = UUID.randomUUID().toString(),
        medicationId = "med-1",
        type = type.name,
        scheduledTimesJson = scheduledTimesJson,
        daysOfWeekJson = daysOfWeekJson,
        intervalHours = intervalHours,
        intervalAnchorType = intervalAnchorType,
        windowStartTime = windowStartTime,
        windowEndTime = windowEndTime,
        dailyMaxDoses = dailyMaxDoses,
        zoneId = zone.id,
        isActive = isActive,
        startEpochMs = startEpochMs,
        endEpochMs = endEpochMs,
        createdAtEpochMs = 0L,
    )

    private fun phase(
        scheduleId: String,
        order: Int,
        desc: String,
        durationDays: Int,
        vararg times: String,
    ) = SchedulePhaseEntity(
        id = UUID.randomUUID().toString(),
        scheduleId = scheduleId,
        phaseOrder = order,
        doseDescription = desc,
        durationDays = durationDays,
        scheduledTimesJson = Json.encodeToString(times.toList()),
    )

    private fun zdtMs(
        zone: ZoneId,
        year: Int,
        month: Int,
        day: Int,
        hour: Int,
        minute: Int = 0,
    ): Long = ZonedDateTime.of(LocalDate.of(year, month, day), LocalTime.of(hour, minute), zone)
        .toInstant().toEpochMilli()

    // ── 1. Fixed daily: 2 times/day × 3 days = 6 instances ───────────────────

    @Test
    fun fixedDailyGeneratesCorrectInstancesInRange() {
        val startDate = LocalDate.of(2026, 6, 1)
        val sched = schedule(
            type = ScheduleType.FIXED_DAILY,
            scheduledTimesJson = json("08:00", "20:00"),
            startEpochMs = ZonedDateTime.of(startDate, LocalTime.MIDNIGHT, NY).toInstant().toEpochMilli(),
        )
        val from = ZonedDateTime.of(startDate, LocalTime.MIDNIGHT, NY).toInstant()
        val to = from.plusMillis(3 * 86_400_000L)

        val instances = ScheduleEngine.generateInstances(sched, emptyList(), from, to, emptySet(), 0L)

        assertEquals(6, instances.size)

        // All should be SCHEDULED
        assertTrue(instances.all { it.state == DoseState.SCHEDULED.name })

        // Check first two
        val expected8am = zdtMs(NY, 2026, 6, 1, 8)
        val expected8pm = zdtMs(NY, 2026, 6, 1, 20)
        assertEquals(expected8am, instances[0].dueEpochMs)
        assertEquals(expected8pm, instances[1].dueEpochMs)
    }

    // ── 2. Append-only: existing due times not duplicated ─────────────────────

    @Test
    fun fixedDailySkipsExistingDueTimes() {
        val startDate = LocalDate.of(2026, 6, 1)
        val sched = schedule(
            type = ScheduleType.FIXED_DAILY,
            scheduledTimesJson = json("08:00", "20:00"),
            startEpochMs = ZonedDateTime.of(startDate, LocalTime.MIDNIGHT, NY).toInstant().toEpochMilli(),
        )
        val from = ZonedDateTime.of(startDate, LocalTime.MIDNIGHT, NY).toInstant()
        val to = from.plusMillis(2 * 86_400_000L)

        val existing8am = zdtMs(NY, 2026, 6, 1, 8)
        val instances = ScheduleEngine.generateInstances(
            sched, emptyList(), from, to, setOf(existing8am), 0L,
        )

        // 4 total minus 1 skipped = 3
        assertEquals(3, instances.size)
        assertTrue(instances.none { it.dueEpochMs == existing8am })
    }

    // ── 3. Days-of-week filters correctly ─────────────────────────────────────

    @Test
    fun daysOfWeekFiltersByDays() {
        // Mon=1, Wed=3, Fri=5
        val startDate = LocalDate.of(2026, 6, 1) // Monday
        val sched = schedule(
            type = ScheduleType.DAYS_OF_WEEK,
            scheduledTimesJson = json("08:00"),
            daysOfWeekJson = daysJson(1, 3, 5), // Mon, Wed, Fri
            startEpochMs = ZonedDateTime.of(startDate, LocalTime.MIDNIGHT, NY).toInstant().toEpochMilli(),
        )
        val from = ZonedDateTime.of(startDate, LocalTime.MIDNIGHT, NY).toInstant()
        // 2 weeks = 14 days → 6 occurrences (Mon/Wed/Fri × 2 weeks)
        val to = from.plusMillis(14 * 86_400_000L)

        val instances = ScheduleEngine.generateInstances(sched, emptyList(), from, to, emptySet(), 0L)

        assertEquals(6, instances.size)
        // Verify all fall on Mon/Wed/Fri
        instances.forEach { inst ->
            val dow = ZonedDateTime.ofInstant(Instant.ofEpochMilli(inst.dueEpochMs), NY).dayOfWeek.value
            assertTrue("Expected Mon/Wed/Fri, got $dow", dow in setOf(1, 3, 5))
        }
    }

    // ── 4. Interval SCHEDULE_ANCHORED every 8h ────────────────────────────────

    @Test
    fun intervalScheduleAnchoredEvery8h() {
        val startDate = LocalDate.of(2026, 6, 1)
        val anchorMs = zdtMs(NY, 2026, 6, 1, 8) // 08:00 NY
        val sched = schedule(
            type = ScheduleType.INTERVAL,
            scheduledTimesJson = json("08:00"),
            intervalHours = 8,
            intervalAnchorType = IntervalAnchorType.SCHEDULE_ANCHORED.name,
            startEpochMs = ZonedDateTime.of(startDate, LocalTime.MIDNIGHT, NY).toInstant().toEpochMilli(),
        )
        val from = ZonedDateTime.of(startDate, LocalTime.MIDNIGHT, NY).toInstant()
        val to = from.plusMillis(2 * 86_400_000L) // 2 days

        val instances = ScheduleEngine.generateInstances(sched, emptyList(), from, to, emptySet(), 0L)

        // anchor=08:00 day1, to=midnight day3 (exclusive):
        // 08:00 day1, 16:00 day1, 00:00 day2, 08:00 day2, 16:00 day2 = 5 instances
        // (00:00 day3 == to and is excluded by the half-open [from, to) convention)
        assertEquals(5, instances.size)
        assertEquals(anchorMs, instances[0].dueEpochMs)
        assertEquals(anchorMs + 8 * 3_600_000L, instances[1].dueEpochMs)
        assertEquals(anchorMs + 16 * 3_600_000L, instances[2].dueEpochMs)
    }

    // ── 5. Interval respects daily window ─────────────────────────────────────

    @Test
    fun intervalRespectsDailyWindow() {
        // Every 6h, window 08:00-22:00 NY
        // Anchor 08:00 → doses at 08, 14, 20 (22:00 excluded), then 08 next day
        val startDate = LocalDate.of(2026, 6, 1)
        val sched = schedule(
            type = ScheduleType.INTERVAL,
            scheduledTimesJson = json("08:00"),
            intervalHours = 6,
            intervalAnchorType = IntervalAnchorType.SCHEDULE_ANCHORED.name,
            windowStartTime = "08:00",
            windowEndTime = "22:00",
            startEpochMs = ZonedDateTime.of(startDate, LocalTime.MIDNIGHT, NY).toInstant().toEpochMilli(),
        )
        val from = ZonedDateTime.of(startDate, LocalTime.MIDNIGHT, NY).toInstant()
        val to = from.plusMillis(2 * 86_400_000L)

        val instances = ScheduleEngine.generateInstances(sched, emptyList(), from, to, emptySet(), 0L)

        // 3 doses/day × 2 days = 6
        assertEquals(6, instances.size)
        // None should fall at or after 22:00
        instances.forEach { inst ->
            val lt = ZonedDateTime.ofInstant(Instant.ofEpochMilli(inst.dueEpochMs), NY).toLocalTime()
            assertTrue("Dose at $lt should be before 22:00", lt.isBefore(LocalTime.of(22, 0)))
            assertTrue("Dose at $lt should be at or after 08:00", !lt.isBefore(LocalTime.of(8, 0)))
        }
    }

    // ── 6. Interval LAST_TAKEN generates exactly 1 instance ──────────────────

    @Test
    fun intervalLastTakenGeneratesOneInstance() {
        val startDate = LocalDate.of(2026, 6, 1)
        val sched = schedule(
            type = ScheduleType.INTERVAL,
            scheduledTimesJson = json("08:00"),
            intervalHours = 8,
            intervalAnchorType = IntervalAnchorType.LAST_TAKEN.name,
            startEpochMs = ZonedDateTime.of(startDate, LocalTime.MIDNIGHT, NY).toInstant().toEpochMilli(),
        )
        val from = ZonedDateTime.of(startDate, LocalTime.MIDNIGHT, NY).toInstant()
        val to = from.plusMillis(30 * 86_400_000L)

        val instances = ScheduleEngine.generateInstances(sched, emptyList(), from, to, emptySet(), 0L)

        assertEquals(1, instances.size)
    }

    // ── 7. Dose window: dueEpochMs = window open, windowEndEpochMs = close ───

    @Test
    fun doseWindowGeneratesWindowInstances() {
        val startDate = LocalDate.of(2026, 6, 1)
        val sched = schedule(
            type = ScheduleType.DOSE_WINDOW,
            windowStartTime = "08:00",
            windowEndTime = "09:00",
            startEpochMs = ZonedDateTime.of(startDate, LocalTime.MIDNIGHT, NY).toInstant().toEpochMilli(),
        )
        val from = ZonedDateTime.of(startDate, LocalTime.MIDNIGHT, NY).toInstant()
        val to = from.plusMillis(3 * 86_400_000L)

        val instances = ScheduleEngine.generateInstances(sched, emptyList(), from, to, emptySet(), 0L)

        assertEquals(3, instances.size)
        instances.forEach { inst ->
            val due = ZonedDateTime.ofInstant(Instant.ofEpochMilli(inst.dueEpochMs), NY)
            val windowEnd = ZonedDateTime.ofInstant(Instant.ofEpochMilli(inst.windowEndEpochMs!!), NY)
            assertEquals(8, due.hour)
            assertEquals(0, due.minute)
            assertEquals(9, windowEnd.hour)
            assertEquals(0, windowEnd.minute)
        }
    }

    // ── 8. PRN generates no instances ─────────────────────────────────────────

    @Test
    fun prnGeneratesNoInstances() {
        val startDate = LocalDate.of(2026, 6, 1)
        val sched = schedule(
            type = ScheduleType.PRN,
            startEpochMs = ZonedDateTime.of(startDate, LocalTime.MIDNIGHT, NY).toInstant().toEpochMilli(),
        )
        val from = ZonedDateTime.of(startDate, LocalTime.MIDNIGHT, NY).toInstant()
        val to = from.plusMillis(30 * 86_400_000L)

        val instances = ScheduleEngine.generateInstances(sched, emptyList(), from, to, emptySet(), 0L)

        assertTrue(instances.isEmpty())
    }

    // ── 9. Temporary stops at endEpochMs ─────────────────────────────────────

    @Test
    fun temporaryStopsAtEndDate() {
        val startDate = LocalDate.of(2026, 6, 1)
        val endDate = LocalDate.of(2026, 6, 3) // 3-day course
        val startMs = ZonedDateTime.of(startDate, LocalTime.MIDNIGHT, NY).toInstant().toEpochMilli()
        val endMs = ZonedDateTime.of(endDate, LocalTime.MIDNIGHT, NY).toInstant().toEpochMilli()

        val sched = schedule(
            type = ScheduleType.TEMPORARY,
            scheduledTimesJson = json("08:00", "20:00"),
            startEpochMs = startMs,
            endEpochMs = endMs,
        )
        val from = ZonedDateTime.of(startDate, LocalTime.MIDNIGHT, NY).toInstant()
        val to = from.plusMillis(30 * 86_400_000L) // ask for 30 days

        val instances = ScheduleEngine.generateInstances(sched, emptyList(), from, to, emptySet(), 0L)

        // Jun 1 + Jun 2 = 2 days × 2 times = 4; Jun 3 (endEpochMs) adds +24h so Jun 3 is included
        // The end is endMs + 24h, so Jun 3 08:00 and Jun 3 20:00 are in range
        // Jun 4 and beyond are excluded
        assertTrue(instances.isNotEmpty())
        instances.forEach { inst ->
            val date = ZonedDateTime.ofInstant(Instant.ofEpochMilli(inst.dueEpochMs), NY).toLocalDate()
            assertTrue("Instance date $date should not be after Jun 3", !date.isAfter(endDate))
        }
    }

    // ── 10. Taper: phases in order ────────────────────────────────────────────

    @Test
    fun taperPhasesInOrder() {
        val startDate = LocalDate.of(2026, 6, 1)
        val sched = schedule(
            type = ScheduleType.TAPER,
            startEpochMs = ZonedDateTime.of(startDate, LocalTime.MIDNIGHT, NY).toInstant().toEpochMilli(),
        )
        val phases = listOf(
            phase(sched.id, 0, "2 tablets", 3, "08:00", "20:00"), // days 1-3: 2 times/day = 6 doses
            phase(sched.id, 1, "1 tablet", 2, "08:00"),           // days 4-5: 1 time/day = 2 doses
        )
        val from = ZonedDateTime.of(startDate, LocalTime.MIDNIGHT, NY).toInstant()
        val to = from.plusMillis(10 * 86_400_000L)

        val instances = ScheduleEngine.generateInstances(sched, phases, from, to, emptySet(), 0L)

        assertEquals(8, instances.size) // 6 + 2

        // First 6 should be from phase 1 (08:00 and 20:00 on days 1-3)
        val phase1End = ZonedDateTime.of(startDate.plusDays(3), LocalTime.MIDNIGHT, NY).toInstant()
        val phase1Instances = instances.filter { Instant.ofEpochMilli(it.dueEpochMs).isBefore(phase1End) }
        assertEquals(6, phase1Instances.size)

        // Last 2 should be from phase 2 (08:00 on days 4-5)
        val phase2Instances = instances.filter { !Instant.ofEpochMilli(it.dueEpochMs).isBefore(phase1End) }
        assertEquals(2, phase2Instances.size)
        phase2Instances.forEach { inst ->
            val hour = ZonedDateTime.ofInstant(Instant.ofEpochMilli(inst.dueEpochMs), NY).hour
            assertEquals(8, hour)
        }
    }

    // ── 11. DST spring-forward: 8am fires at correct wall time ───────────────

    @Test
    fun dstSpringForwardFixed8am() {
        // 2026 spring-forward in NY: clocks move forward at 2026-03-08 02:00 → 03:00
        val startDate = LocalDate.of(2026, 3, 7) // day before DST
        val sched = schedule(
            type = ScheduleType.FIXED_DAILY,
            scheduledTimesJson = json("08:00"),
            startEpochMs = ZonedDateTime.of(startDate, LocalTime.MIDNIGHT, NY).toInstant().toEpochMilli(),
        )
        val from = ZonedDateTime.of(startDate, LocalTime.MIDNIGHT, NY).toInstant()
        val to = from.plusMillis(3 * 86_400_000L) // Mar 7, 8, 9

        val instances = ScheduleEngine.generateInstances(sched, emptyList(), from, to, emptySet(), 0L)

        assertEquals(3, instances.size)

        // All three instances should be at local wall time 08:00
        instances.forEach { inst ->
            val zdt = ZonedDateTime.ofInstant(Instant.ofEpochMilli(inst.dueEpochMs), NY)
            assertEquals(8, zdt.hour)
            assertEquals(0, zdt.minute)
        }

        // Mar 8 08:00 EST and Mar 8 08:00 EDT are different UTC instants
        val mar7_8am = zdtMs(NY, 2026, 3, 7, 8)
        val mar8_8am = zdtMs(NY, 2026, 3, 8, 8)
        val mar9_8am = zdtMs(NY, 2026, 3, 9, 8)
        assertEquals(mar7_8am, instances[0].dueEpochMs)
        assertEquals(mar8_8am, instances[1].dueEpochMs)
        assertEquals(mar9_8am, instances[2].dueEpochMs)
        // On DST day, 8am EDT is 1 hour earlier UTC than if it were still EST
        assertTrue("DST day should shift UTC instant", mar8_8am - mar7_8am == 23 * 3_600_000L)
    }

    // ── 12. DST fall-back: 2am uses earlier (pre-transition) offset ──────────

    @Test
    fun dstFallBackFixed2am() {
        // 2026 fall-back in NY: 2026-11-01 02:00 → 01:00
        val startDate = LocalDate.of(2026, 10, 31) // day before fall-back
        val sched = schedule(
            type = ScheduleType.FIXED_DAILY,
            scheduledTimesJson = json("02:00"),
            startEpochMs = ZonedDateTime.of(startDate, LocalTime.MIDNIGHT, NY).toInstant().toEpochMilli(),
        )
        val from = ZonedDateTime.of(startDate, LocalTime.MIDNIGHT, NY).toInstant()
        val to = from.plusMillis(3 * 86_400_000L)

        val instances = ScheduleEngine.generateInstances(sched, emptyList(), from, to, emptySet(), 0L)

        assertEquals(3, instances.size)

        // All should be at wall-time 02:00 in NY (java.time uses the earlier/pre-transition offset)
        instances.forEach { inst ->
            val zdt = ZonedDateTime.ofInstant(Instant.ofEpochMilli(inst.dueEpochMs), NY)
            assertEquals(2, zdt.hour)
            assertEquals(0, zdt.minute)
        }
    }

    // ── 13. Instances are independent ────────────────────────────────────────

    @Test
    fun instancesAreIndependent() {
        val startDate = LocalDate.of(2026, 6, 1)
        val sched = schedule(
            type = ScheduleType.FIXED_DAILY,
            scheduledTimesJson = json("08:00", "20:00"),
            startEpochMs = ZonedDateTime.of(startDate, LocalTime.MIDNIGHT, NY).toInstant().toEpochMilli(),
        )
        val from = ZonedDateTime.of(startDate, LocalTime.MIDNIGHT, NY).toInstant()
        val to = from.plusMillis(3 * 86_400_000L)

        val instances = ScheduleEngine.generateInstances(sched, emptyList(), from, to, emptySet(), 0L)

        // Each instance has a unique ID
        val ids = instances.map { it.id }.toSet()
        assertEquals(instances.size, ids.size)

        // No instance holds a reference to another — all have null taken/skipped/missed/snooze
        instances.forEach { inst ->
            assertTrue(inst.takenEpochMs == null)
            assertTrue(inst.skippedEpochMs == null)
            assertTrue(inst.missedEpochMs == null)
            assertTrue(inst.snoozeUntilEpochMs == null)
        }
    }

    // ── 14. Inactive schedule yields no instances ─────────────────────────────

    @Test
    fun pauseYieldsNoInstances() {
        val startDate = LocalDate.of(2026, 6, 1)
        val sched = schedule(
            type = ScheduleType.FIXED_DAILY,
            scheduledTimesJson = json("08:00"),
            startEpochMs = ZonedDateTime.of(startDate, LocalTime.MIDNIGHT, NY).toInstant().toEpochMilli(),
            isActive = false, // deactivated
        )
        val from = ZonedDateTime.of(startDate, LocalTime.MIDNIGHT, NY).toInstant()
        val to = from.plusMillis(30 * 86_400_000L)

        val instances = ScheduleEngine.generateInstances(sched, emptyList(), from, to, emptySet(), 0L)

        assertTrue(instances.isEmpty())
    }

    // ── 15. windowEndEpochMs = min(due+60min, nextDue) ───────────────────────

    @Test
    fun windowEndMsIsSetCorrectly() {
        // 3 doses/day: 08:00, 12:00, 20:00
        // windowEnd for 08:00 = min(09:00, 12:00) = 09:00
        // windowEnd for 12:00 = min(13:00, 20:00) = 13:00
        // windowEnd for 20:00 (last of day) = 21:00 (no next)
        val startDate = LocalDate.of(2026, 6, 1)
        val sched = schedule(
            type = ScheduleType.FIXED_DAILY,
            scheduledTimesJson = json("08:00", "12:00", "20:00"),
            startEpochMs = ZonedDateTime.of(startDate, LocalTime.MIDNIGHT, NY).toInstant().toEpochMilli(),
        )
        val from = ZonedDateTime.of(startDate, LocalTime.MIDNIGHT, NY).toInstant()
        val to = from.plusMillis(86_400_000L) // 1 day

        val instances = ScheduleEngine.generateInstances(sched, emptyList(), from, to, emptySet(), 0L)

        assertEquals(3, instances.size)

        val i0 = instances[0] // 08:00
        val i1 = instances[1] // 12:00
        val i2 = instances[2] // 20:00

        // windowEnd[0] = min(08:00 + 60min, 12:00) = 09:00
        val expected0End = i0.dueEpochMs + 3_600_000L
        assertEquals(expected0End, i0.windowEndEpochMs)
        // 09:00 < 12:00, so windowEnd = due+60min
        assertTrue(i0.windowEndEpochMs!! < i1.dueEpochMs)

        // windowEnd[1] = min(12:00 + 60min, 20:00) = 13:00
        val expected1End = i1.dueEpochMs + 3_600_000L
        assertEquals(expected1End, i1.windowEndEpochMs)

        // windowEnd[2] = 20:00 + 60min = 21:00 (no next)
        val expected2End = i2.dueEpochMs + 3_600_000L
        assertEquals(expected2End, i2.windowEndEpochMs)
    }

    // ── 16. Taper boundary: last day of phase 1 and first day of phase 2 are adjacent ──

    @Test
    fun taperBoundaryIsCorrect() {
        val startDate = LocalDate.of(2026, 6, 1)
        val sched = schedule(
            type = ScheduleType.TAPER,
            startEpochMs = ZonedDateTime.of(startDate, LocalTime.MIDNIGHT, NY).toInstant().toEpochMilli(),
        )
        val phases = listOf(
            phase(sched.id, 0, "2 tablets", 3, "08:00"), // Jun 1, 2, 3
            phase(sched.id, 1, "1 tablet", 3, "08:00"),  // Jun 4, 5, 6
        )
        val from = ZonedDateTime.of(startDate, LocalTime.MIDNIGHT, NY).toInstant()
        val to = from.plusMillis(10 * 86_400_000L)

        val instances = ScheduleEngine.generateInstances(sched, phases, from, to, emptySet(), 0L)

        assertEquals(6, instances.size)

        // Last instance of phase 1 = Jun 3 08:00
        val lastPhase1 = ZonedDateTime.ofInstant(Instant.ofEpochMilli(instances[2].dueEpochMs), NY)
        assertEquals(LocalDate.of(2026, 6, 3), lastPhase1.toLocalDate())

        // First instance of phase 2 = Jun 4 08:00
        val firstPhase2 = ZonedDateTime.ofInstant(Instant.ofEpochMilli(instances[3].dueEpochMs), NY)
        assertEquals(LocalDate.of(2026, 6, 4), firstPhase2.toLocalDate())

        // No gap: Jun 3 → Jun 4 are adjacent calendar days
        val daysBetween = java.time.temporal.ChronoUnit.DAYS.between(
            lastPhase1.toLocalDate(), firstPhase2.toLocalDate(),
        )
        assertEquals(1L, daysBetween)
    }
}
