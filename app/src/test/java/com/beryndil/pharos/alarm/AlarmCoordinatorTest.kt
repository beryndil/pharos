package com.beryndil.pharos.alarm

import android.app.AlarmManager
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.UUID

/**
 * Unit tests for [AlarmCoordinator] — the single-fire-and-reschedule core (spec §3.4).
 *
 * Uses an in-memory [RegimenDatabase] (no SQLCipher native .so in the JVM, per DECISIONS.md A9),
 * a real [AndroidAlarmScheduler] over [ShadowAlarmManager] to assert exact trigger times, and
 * recording fakes for the notifier and reliability log.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AlarmCoordinatorTest {

    private lateinit var db: RegimenDatabase
    private lateinit var alarmManager: AlarmManager
    private lateinit var shadow: ShadowAlarmManager
    private lateinit var scheduler: AndroidAlarmScheduler
    private lateinit var notifier: RecordingNotifier
    private lateinit var reliability: RecordingReliabilityLog
    private lateinit var scheduleRepository: ScheduleRepository

    private val zone = ZoneId.of("America/New_York")
    private var nowMs = 1_900_000_000_000L

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
        notifier = RecordingNotifier()
        reliability = RecordingReliabilityLog()
        scheduleRepository = ScheduleRepository(
            scheduleDao = db.scheduleDao(),
            schedulePhaseDao = db.schedulePhaseDao(),
            doseInstanceDao = db.doseInstanceDao(),
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun coordinator() = AlarmCoordinator(
        scheduler = scheduler,
        notifier = notifier,
        doseInstanceDao = db.doseInstanceDao(),
        medicationDao = db.medicationDao(),
        scheduleRepository = scheduleRepository,
        reliabilityLog = reliability,
        now = { nowMs },
        zoneProvider = { zone },
    )

    // ── rearm picks the earliest SCHEDULED dose ────────────────────────────────

    @Test
    fun rearm_schedulesEarliestScheduledDose_withSetAlarmClock() = runTest {
        val med = insertMed()
        val sched = insertSchedule(med.id)
        val due1 = nowMs + 3_600_000L
        val due2 = nowMs + 7_200_000L
        insertDose(med.id, sched.id, due2) // out of order on purpose
        val dose1 = insertDose(med.id, sched.id, due1)

        val result = coordinator().rearmNextDoseAlarm()

        assertEquals(dose1.id, result.doseId)
        assertEquals(due1, result.triggerAtEpochMs)
        assertEquals(AlarmMode.EXACT, result.mode)
        assertEquals(due1, shadow.peekNextScheduledAlarm()!!.triggerAtTime)
        assertNotNull("exact dose alarm uses setAlarmClock", alarmManager.nextAlarmClock)
        assertEquals(AlarmMode.EXACT, reliability.lastScheduledMode)
    }

    @Test
    fun rearm_withNoScheduledDoses_cancelsAndRecordsNone() = runTest {
        val result = coordinator().rearmNextDoseAlarm()

        assertNull(result.doseId)
        assertTrue("no pending alarm when nothing is scheduled", shadow.scheduledAlarms.isEmpty())
        assertTrue(reliability.recordedNoUpcoming)
    }

    @Test
    fun rearm_overdueDose_schedulesAtPastTrigger_forRebootRecovery() = runTest {
        val med = insertMed()
        val sched = insertSchedule(med.id)
        val overdue = nowMs - 3_600_000L // came due while the device was off
        insertDose(med.id, sched.id, overdue)

        val result = coordinator().rearmNextDoseAlarm()

        // A past trigger fires immediately — the overdue dose is recovered, not dropped.
        assertEquals(overdue, result.triggerAtEpochMs)
        assertEquals(overdue, shadow.peekNextScheduledAlarm()!!.triggerAtTime)
    }

    // ── fire → mark DUE → reschedule the next ──────────────────────────────────

    @Test
    fun onDoseAlarmFired_marksDue_postsAlert_andReschedulesNext() = runTest {
        val med = insertMed()
        val sched = insertSchedule(med.id)
        val due1 = nowMs + 3_600_000L
        val due2 = nowMs + 7_200_000L
        val dose1 = insertDose(med.id, sched.id, due1)
        val dose2 = insertDose(med.id, sched.id, due2)

        val c = coordinator()
        c.rearmNextDoseAlarm()
        c.onDoseAlarmFired(dose1.id)

        // dose1 transitioned SCHEDULED → DUE (the alarm-firing transition).
        assertEquals(DoseState.DUE.name, db.doseInstanceDao().getById(dose1.id)!!.state)
        // The full-screen alert was posted for the fired dose, with the med name.
        assertEquals(dose1.id, notifier.lastDoseId)
        assertEquals(med.name, notifier.lastMedName)
        // The NEXT dose is now the pending alarm — single-fire-and-reschedule.
        assertEquals(due2, shadow.peekNextScheduledAlarm()!!.triggerAtTime)
        assertEquals(DoseState.SCHEDULED.name, db.doseInstanceDao().getById(dose2.id)!!.state)
        // Never repeating.
        assertEquals(0L, shadow.peekNextScheduledAlarm()!!.interval)
    }

    @Test
    fun onDoseAlarmFired_lastDose_leavesNoPendingAlarm() = runTest {
        val med = insertMed()
        val sched = insertSchedule(med.id)
        val dose = insertDose(med.id, sched.id, nowMs + 3_600_000L)

        val c = coordinator()
        c.rearmNextDoseAlarm()
        c.onDoseAlarmFired(dose.id)

        assertTrue("no more SCHEDULED doses → no pending alarm", shadow.scheduledAlarms.isEmpty())
    }

    // ── graceful degradation ───────────────────────────────────────────────────

    @Test
    fun rearm_whenExactDenied_usesWindowedFallback_neverDrops() = runTest {
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        val med = insertMed()
        val sched = insertSchedule(med.id)
        val due = nowMs + 3_600_000L
        insertDose(med.id, sched.id, due)

        val result = coordinator().rearmNextDoseAlarm()

        assertEquals(AlarmMode.WINDOWED_FALLBACK, result.mode)
        assertEquals(due, shadow.peekNextScheduledAlarm()!!.triggerAtTime)
        assertNull("fallback must not use setAlarmClock", alarmManager.nextAlarmClock)
        assertEquals(AlarmMode.WINDOWED_FALLBACK, reliability.lastScheduledMode)
    }

    // ── re-registration triggers rebuild the alarm set ─────────────────────────

    @Test
    fun onReRegistration_rebuildsPendingDoseAlarm_andRecordsTrigger() = runTest {
        val med = insertMed()
        val sched = insertSchedule(med.id)
        val due = nowMs + 3_600_000L
        insertDose(med.id, sched.id, due)

        for (trigger in REREGISTRATION_TRIGGERS) {
            shadow.scheduledAlarms.clear()
            coordinator().onReRegistration(trigger)

            assertTrue(
                "re-registration ($trigger) must re-arm the dose alarm",
                shadow.scheduledAlarms.any { it.triggerAtTime == due },
            )
            assertEquals(trigger, reliability.lastReRegistrationTrigger)
        }
    }

    // ── test-reminder path (Law 6) ─────────────────────────────────────────────

    @Test
    fun scheduleTestReminder_schedulesThroughTheEngine() = runTest {
        coordinator().scheduleTestReminder(delayMs = 5_000L)

        assertEquals(nowMs + 5_000L, shadow.peekNextScheduledAlarm()!!.triggerAtTime)
        assertEquals(AlarmKind.TEST, reliability.lastScheduledKind)
    }

    @Test
    fun fireTestReminder_postsTestNotification() {
        coordinator().fireTestReminder()
        assertTrue(notifier.testReminderPosted)
    }

    // ── daily rollover re-anchors at the next local midnight + tops up ─────────

    @Test
    fun scheduleDailyRollover_targetsNextLocalMidnight() = runTest {
        coordinator().scheduleDailyRollover()

        val expected = ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(nowMs), zone)
            .toLocalDate()
            .plusDays(1)
            .atStartOfDay(zone)
            .toInstant()
            .toEpochMilli()
        assertEquals(expected, shadow.peekNextScheduledAlarm()!!.triggerAtTime)
        assertEquals(AlarmKind.DAILY_ROLLOVER, reliability.lastScheduledKind)
    }

    @Test
    fun onDailyRollover_topsUpGeneration_forActiveMeds() = runTest {
        val med = insertMed()
        val sched = insertSchedule(med.id) // FIXED_DAILY 08:00 — but no instances generated yet
        assertTrue(db.doseInstanceDao().getDueTimesForSchedule(sched.id).isEmpty())

        coordinator().onDailyRollover()

        assertFalse(
            "daily rollover must extend the generated dose horizon",
            db.doseInstanceDao().getDueTimesForSchedule(sched.id).isEmpty(),
        )
    }

    // ── helpers ─────────────────────────────────────────────────────────────────

    private suspend fun insertMed(
        id: String = UUID.randomUUID().toString(),
        status: MedicationStatus = MedicationStatus.ACTIVE,
    ): MedicationEntity {
        val med = MedicationEntity(
            id = id,
            name = "Metoprolol",
            rxcui = "866427",
            ingredientsJson = """["41493"]""",
            strength = "25 mg",
            form = MedicationForm.TABLET.name,
            doseAmount = "1 tablet",
            prescriber = null,
            pharmacy = null,
            purpose = null,
            isFreeText = false,
            status = status.name,
            startEpochMs = nowMs,
            endEpochMs = null,
            createdAtEpochMs = nowMs,
            updatedAtEpochMs = nowMs,
        )
        db.medicationDao().insert(med)
        return med
    }

    private suspend fun insertSchedule(
        medId: String,
        id: String = UUID.randomUUID().toString(),
    ): ScheduleEntity {
        val sched = ScheduleEntity(
            id = id,
            medicationId = medId,
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
        return sched
    }

    private suspend fun insertDose(
        medId: String,
        scheduleId: String,
        dueEpochMs: Long,
    ): DoseInstanceEntity {
        val dose = DoseInstanceEntity(
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
        )
        db.doseInstanceDao().insert(dose)
        return dose
    }

    private companion object {
        val REREGISTRATION_TRIGGERS = listOf(
            "android.intent.action.BOOT_COMPLETED",
            "android.intent.action.MY_PACKAGE_REPLACED",
            "android.intent.action.TIME_SET",
            "android.intent.action.TIMEZONE_CHANGED",
            "android.intent.action.DATE_CHANGED",
        )
    }
}

// ── recording fakes ─────────────────────────────────────────────────────────────

private class RecordingNotifier : DoseNotifier {
    var lastDoseId: String? = null
    var lastMedName: String? = null
    var testReminderPosted = false

    override fun ensureChannels() = Unit
    override fun postDoseDueAlert(doseId: String, medName: String, dueEpochMs: Long) {
        lastDoseId = doseId
        lastMedName = medName
    }

    override fun postTestReminder() {
        testReminderPosted = true
    }

    override fun canUseFullScreen(): Boolean = true
}

private class RecordingReliabilityLog : ReliabilityLog {
    var lastScheduledKind: AlarmKind? = null
    var lastScheduledMode: AlarmMode? = null
    var recordedNoUpcoming = false
    var lastReRegistrationTrigger: String? = null

    override suspend fun recordAlarmScheduled(
        kind: AlarmKind,
        mode: AlarmMode,
        triggerAtEpochMs: Long,
    ) {
        lastScheduledKind = kind
        lastScheduledMode = mode
    }

    override suspend fun recordAlarmFired(kind: AlarmKind, atEpochMs: Long) = Unit
    override suspend fun recordNoUpcomingAlarm(atEpochMs: Long) {
        recordedNoUpcoming = true
    }

    override suspend fun recordReRegistration(trigger: String, atEpochMs: Long) {
        lastReRegistrationTrigger = trigger
    }
}
