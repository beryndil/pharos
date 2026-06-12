package com.beryndil.pharos.alarm

import android.app.AlarmManager
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.beryndil.pharos.core.time.DoseClock
import com.beryndil.pharos.data.regimen.RegimenDatabase
import com.beryndil.pharos.data.regimen.entity.DoseInstanceEntity
import com.beryndil.pharos.data.regimen.entity.DoseState
import com.beryndil.pharos.data.regimen.entity.MedicationEntity
import com.beryndil.pharos.data.regimen.entity.MedicationForm
import com.beryndil.pharos.data.regimen.entity.MedicationStatus
import com.beryndil.pharos.data.regimen.entity.ScheduleEntity
import com.beryndil.pharos.data.regimen.entity.ScheduleType
import com.beryndil.pharos.data.schedule.ScheduleRepository
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.UUID

/**
 * DST / timezone correctness for the alarm engine — a wrong-hour dose after a DST shift is a
 * SAFETY BUG and a launch gate (spec §3.4, Standards §2). All instants come from [DoseClock];
 * this proves the scheduled alarm fires at the intended WALL-CLOCK time across both US DST
 * boundaries, and that re-registration after a timezone change re-arms from the stored instant.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AlarmDstTest {

    private val zone = ZoneId.of("America/New_York")

    private lateinit var db: RegimenDatabase
    private lateinit var alarmManager: AlarmManager
    private lateinit var shadow: ShadowAlarmManager
    private lateinit var scheduler: AndroidAlarmScheduler
    private lateinit var scheduleRepository: ScheduleRepository
    private var nowMs = 0L

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, RegimenDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        shadow = shadowOf(alarmManager)
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        scheduler = AndroidAlarmScheduler(context)
        scheduleRepository = ScheduleRepository(
            db.scheduleDao(), db.schedulePhaseDao(), db.doseInstanceDao(),
        )
    }

    @After
    fun tearDown() = db.close()

    private fun coordinator() = AlarmCoordinator(
        scheduler = scheduler,
        notifier = NoOpNotifier,
        doseInstanceDao = db.doseInstanceDao(),
        medicationDao = db.medicationDao(),
        scheduleRepository = scheduleRepository,
        reliabilityLog = NoOpReliabilityLog,
        now = { nowMs },
        zoneProvider = { zone },
    )

    @Test
    fun springForward_doseFiresAtCorrectWallClock_and23HoursApart() = runTest {
        // US spring-forward: 2025-03-09, 02:00 → 03:00 EDT.
        val before = ZonedDateTime.of(2025, 3, 8, 7, 0, 0, 0, zone).toInstant()
        val mar8at8 = DoseClock.nextOccurrence(LocalTime.of(8, 0), zone, before)
        val mar9at8 = DoseClock.nextOccurrence(LocalTime.of(8, 0), zone, mar8at8)

        // The civil day containing the spring-forward is 23 h long.
        assertEquals(23L * 3_600_000L, mar9at8.toEpochMilli() - mar8at8.toEpochMilli())

        nowMs = mar8at8.toEpochMilli() + 60_000L // just after the Mar 8 dose
        val (med, sched) = seed()
        insertDose(med.id, sched.id, mar9at8.toEpochMilli())

        coordinator().rearmNextDoseAlarm()

        val trigger = shadow.peekNextScheduledAlarm()!!.triggerAtTime
        assertEquals(mar9at8.toEpochMilli(), trigger)
        // The alarm fires at 08:00 LOCAL on the day after the shift — not 07:00 or 09:00.
        assertEquals(
            LocalTime.of(8, 0),
            ZonedDateTime.ofInstant(Instant.ofEpochMilli(trigger), zone).toLocalTime(),
        )
    }

    @Test
    fun fallBack_doseFiresAtCorrectWallClock_and25HoursApart() = runTest {
        // US fall-back: 2025-11-02, 02:00 → 01:00 EST.
        val before = ZonedDateTime.of(2025, 11, 1, 7, 0, 0, 0, zone).toInstant()
        val nov1at8 = DoseClock.nextOccurrence(LocalTime.of(8, 0), zone, before)
        val nov2at8 = DoseClock.nextOccurrence(LocalTime.of(8, 0), zone, nov1at8)

        assertEquals(25L * 3_600_000L, nov2at8.toEpochMilli() - nov1at8.toEpochMilli())

        nowMs = nov1at8.toEpochMilli() + 60_000L
        val (med, sched) = seed()
        insertDose(med.id, sched.id, nov2at8.toEpochMilli())

        coordinator().rearmNextDoseAlarm()

        val trigger = shadow.peekNextScheduledAlarm()!!.triggerAtTime
        assertEquals(nov2at8.toEpochMilli(), trigger)
        assertEquals(
            LocalTime.of(8, 0),
            ZonedDateTime.ofInstant(Instant.ofEpochMilli(trigger), zone).toLocalTime(),
        )
    }

    @Test
    fun timezoneChange_reArmsFromStoredInstant_preservingTheAbsoluteMoment() = runTest {
        // A dose's stored instant is absolute. On a timezone change we re-read it; the alarm must
        // still fire at exactly that instant (time-zone TRAVEL re-anchoring UX is v1.x, §3.4).
        val due = ZonedDateTime.of(2025, 6, 1, 8, 0, 0, 0, zone).toInstant().toEpochMilli()
        nowMs = due - 3_600_000L
        val (med, sched) = seed()
        insertDose(med.id, sched.id, due)

        coordinator().onReRegistration("android.intent.action.TIMEZONE_CHANGED")

        assertEquals(
            "timezone change must re-arm the dose at its stored absolute instant",
            due,
            shadow.scheduledAlarms.first { it.triggerAtTime == due }.triggerAtTime,
        )
    }

    // ── helpers ─────────────────────────────────────────────────────────────────

    private suspend fun seed(): Pair<MedicationEntity, ScheduleEntity> {
        val med = MedicationEntity(
            id = UUID.randomUUID().toString(),
            name = "Levothyroxine",
            rxcui = null,
            ingredientsJson = "[]",
            strength = "50 mcg",
            form = MedicationForm.TABLET.name,
            doseAmount = "1 tablet",
            prescriber = null,
            pharmacy = null,
            purpose = null,
            isFreeText = false,
            status = MedicationStatus.ACTIVE.name,
            startEpochMs = nowMs,
            endEpochMs = null,
            createdAtEpochMs = nowMs,
            updatedAtEpochMs = nowMs,
        )
        db.medicationDao().insert(med)
        val sched = ScheduleEntity(
            id = UUID.randomUUID().toString(),
            medicationId = med.id,
            type = ScheduleType.FIXED_DAILY.name,
            scheduledTimesJson = """["08:00"]""",
            daysOfWeekJson = null,
            intervalHours = null,
            intervalAnchorType = null,
            windowStartTime = null,
            windowEndTime = null,
            dailyMaxDoses = null,
            zoneId = zone.id,
            isActive = true,
            startEpochMs = nowMs,
            endEpochMs = null,
            createdAtEpochMs = nowMs,
        )
        db.scheduleDao().insert(sched)
        return med to sched
    }

    private suspend fun insertDose(medId: String, scheduleId: String, dueEpochMs: Long) {
        db.doseInstanceDao().insert(
            DoseInstanceEntity(
                id = UUID.randomUUID().toString(),
                medicationId = medId,
                scheduleId = scheduleId,
                dueEpochMs = dueEpochMs,
                windowEndEpochMs = dueEpochMs + 3_600_000L,
                state = DoseState.SCHEDULED.name,
                takenEpochMs = null,
                skippedEpochMs = null,
                missedEpochMs = null,
                snoozeUntilEpochMs = null,
                createdAtEpochMs = nowMs,
            ),
        )
    }

    private object NoOpNotifier : DoseNotifier {
        override fun ensureChannels() = Unit
        override fun postDoseDueAlert(doseId: String, medName: String, dueEpochMs: Long) = Unit
        override fun postTestReminder() = Unit
        override fun canUseFullScreen() = true
    }

    private object NoOpReliabilityLog : ReliabilityLog {
        override suspend fun recordAlarmScheduled(kind: AlarmKind, mode: AlarmMode, triggerAtEpochMs: Long) = Unit
        override suspend fun recordAlarmFired(kind: AlarmKind, atEpochMs: Long) = Unit
        override suspend fun recordNoUpcomingAlarm(atEpochMs: Long) = Unit
        override suspend fun recordReRegistration(trigger: String, atEpochMs: Long) = Unit
    }
}
