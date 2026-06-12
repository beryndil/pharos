package com.beryndil.pharos.dose

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.beryndil.pharos.alarm.DoseNotifier
import com.beryndil.pharos.data.regimen.RegimenDatabase
import com.beryndil.pharos.data.regimen.entity.DoseInstanceEntity
import com.beryndil.pharos.data.regimen.entity.DoseState
import com.beryndil.pharos.data.regimen.entity.DoseTransitionCause
import com.beryndil.pharos.data.regimen.entity.MedicationEntity
import com.beryndil.pharos.data.regimen.entity.MedicationForm
import com.beryndil.pharos.data.regimen.entity.MedicationStatus
import com.beryndil.pharos.data.regimen.entity.ScheduleEntity
import com.beryndil.pharos.data.regimen.entity.ScheduleType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/**
 * Risk-core tests for [DoseStateMachine] (spec §2.6 / §2.7 / §2.8). Uses an in-memory
 * [RegimenDatabase] (no SQLCipher native .so in the JVM, DECISIONS.md A9) with recording fakes for
 * the timed-transition scheduler and notifier, and a pinned clock/zone/id provider.
 */
@RunWith(RobolectricTestRunner::class)
class DoseStateMachineTest {

    private lateinit var db: RegimenDatabase
    private lateinit var scheduler: RecordingTransitionScheduler
    private lateinit var notifier: RecordingNotifier

    private val zone = ZoneId.of("America/New_York")
    private val hour = 60L * 60L * 1000L
    private val minute = 60L * 1000L
    private var nowMs = 1_900_000_000_000L
    private val ids = AtomicLong(0)

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, RegimenDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        scheduler = RecordingTransitionScheduler()
        notifier = RecordingNotifier()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun machine() = DoseStateMachine(
        doseInstanceDao = db.doseInstanceDao(),
        doseTransitionDao = db.doseTransitionDao(),
        medicationDao = db.medicationDao(),
        scheduleDao = db.scheduleDao(),
        transitionScheduler = scheduler,
        notifier = notifier,
        now = { nowMs },
        zoneProvider = { zone },
        idProvider = { "tid-${ids.incrementAndGet()}" },
    )

    // ── legal transitions + append-only history ─────────────────────────────────────────────

    @Test
    fun enteredDue_thenTaken_recordsBothTransitions_andProjectsState() = runTest {
        val (med, sched) = seedFixed()
        val due = nowMs + hour
        val dose = insertScheduledDose(med.id, sched.id, due)

        // Coordinator marks DUE, then hands off.
        db.doseInstanceDao().markDue(dose.id)
        machine().onEnteredDue(dose.id, firedAtEpochMs = nowMs)

        // User confirms.
        nowMs += minute
        machine().onTaken(dose.id, nowMs)

        val updated = db.doseInstanceDao().getById(dose.id)!!
        assertEquals(DoseState.TAKEN.name, updated.state)
        assertEquals(nowMs, updated.takenEpochMs)

        val history = db.doseTransitionDao().getByDose(dose.id)
        assertEquals(2, history.size)
        assertEquals(DoseTransitionCause.ALARM_FIRED.name, history[0].cause)
        assertEquals(DoseState.DUE.name, history[0].toState)
        assertEquals(DoseTransitionCause.USER_TAKEN.name, history[1].cause)
        assertEquals(DoseState.TAKEN.name, history[1].toState)
        // The dose row was not duplicated — it is a single projection row.
        assertEquals(1, db.doseInstanceDao().countById(dose.id))
    }

    @Test
    fun illegalTransition_isRejected() = runTest {
        val (med, sched) = seedFixed()
        val dose = insertScheduledDose(med.id, sched.id, nowMs + hour)
        db.doseInstanceDao().markDue(dose.id)
        machine().onTaken(dose.id, nowMs)

        // A second "taken" on an already-TAKEN dose is illegal.
        assertThrows(IllegalDoseTransitionException::class.java) {
            kotlinx.coroutines.runBlocking { machine().onTaken(dose.id, nowMs + minute) }
        }
    }

    @Test
    fun appendOnly_priorTransitionRowsAreNeverOverwritten() = runTest {
        val (med, sched) = seedFixed()
        val dose = insertScheduledDose(med.id, sched.id, nowMs + hour)
        db.doseInstanceDao().markDue(dose.id)
        val m = machine()
        m.onEnteredDue(dose.id, nowMs)
        nowMs += minute
        m.onSnooze(dose.id, nowMs)

        val afterSnooze = db.doseTransitionDao().getByDose(dose.id)
        assertEquals(2, afterSnooze.size)
        val firstId = afterSnooze[0].id
        val firstAt = afterSnooze[0].atEpochMs

        // Re-alert back to DUE then take — more rows appended, originals intact.
        nowMs += 16 * minute
        m.onReAlert(dose.id)
        nowMs += minute
        m.onTaken(dose.id, nowMs)

        val full = db.doseTransitionDao().getByDose(dose.id)
        assertEquals(4, full.size)
        assertEquals("first transition row is unchanged", firstId, full[0].id)
        assertEquals(firstAt, full[0].atEpochMs)
    }

    // ── D2 miss window ──────────────────────────────────────────────────────────────────────

    @Test
    fun missCheck_fixedDose_missesAtSixtyMinutes() = runTest {
        val (med, sched) = seedFixed()
        val due = nowMs
        val dose = insertScheduledDose(med.id, sched.id, due)
        db.doseInstanceDao().markDue(dose.id)
        machine().onEnteredDue(dose.id, due)

        // 59 minutes: not yet missed.
        nowMs = due + 59 * minute
        machine().onMissCheck(dose.id)
        assertEquals(DoseState.DUE.name, db.doseInstanceDao().getById(dose.id)!!.state)

        // 60 minutes: missed.
        nowMs = due + 60 * minute
        machine().onMissCheck(dose.id)
        val missed = db.doseInstanceDao().getById(dose.id)!!
        assertEquals(DoseState.MISSED.name, missed.state)
        assertEquals(nowMs, missed.missedEpochMs)
    }

    @Test
    fun missWindow_capsAtNextScheduledDose_whicheverFirst() = runTest {
        val (med, sched) = seedFixed()
        val due = nowMs
        val dose = insertScheduledDose(med.id, sched.id, due)
        // Same med's next dose 30 min later — closes the window before the 60-min grace (D2).
        insertScheduledDose(med.id, sched.id, due + 30 * minute)
        db.doseInstanceDao().markDue(dose.id)
        machine().onEnteredDue(dose.id, due)

        nowMs = due + 30 * minute
        machine().onMissCheck(dose.id)
        assertEquals(DoseState.MISSED.name, db.doseInstanceDao().getById(dose.id)!!.state)
    }

    @Test
    fun missWindow_windowedDose_usesWindowEnd() = runTest {
        val med = insertMed()
        val sched = insertSchedule(med.id, ScheduleType.DOSE_WINDOW)
        val due = nowMs
        val windowEnd = due + 2 * hour
        val dose = insertScheduledDose(med.id, sched.id, due, windowEndEpochMs = windowEnd)
        db.doseInstanceDao().markDue(dose.id)
        machine().onEnteredDue(dose.id, due)

        // Past the 60-min grace but inside the window: still DUE.
        nowMs = due + 90 * minute
        machine().onMissCheck(dose.id)
        assertEquals(DoseState.DUE.name, db.doseInstanceDao().getById(dose.id)!!.state)

        // Window end: missed.
        nowMs = windowEnd
        machine().onMissCheck(dose.id)
        assertEquals(DoseState.MISSED.name, db.doseInstanceDao().getById(dose.id)!!.state)
    }

    // ── D3 snooze ───────────────────────────────────────────────────────────────────────────

    @Test
    fun snooze_isFifteenMinutes_repeatable_andReAlertReturnsToDue() = runTest {
        val (med, sched) = seedFixed()
        val due = nowMs
        val dose = insertScheduledDose(med.id, sched.id, due)
        db.doseInstanceDao().markDue(dose.id)
        val m = machine()
        m.onEnteredDue(dose.id, due)

        m.onSnooze(dose.id, due)
        val snoozed = db.doseInstanceDao().getById(dose.id)!!
        assertEquals(DoseState.SNOOZED.name, snoozed.state)
        assertEquals(due + DoseStateMachine.SNOOZE_INTERVAL_MS, snoozed.snoozeUntilEpochMs)
        assertEquals(due + 15 * minute, scheduler.lastReAlert(dose.id))

        // Re-alert fires at the snooze target → back to DUE (escalation re-enters DUE).
        nowMs = due + 15 * minute
        m.onReAlert(dose.id)
        assertEquals(DoseState.DUE.name, db.doseInstanceDao().getById(dose.id)!!.state)

        // Snooze again — repeatable.
        m.onSnooze(dose.id, nowMs)
        assertEquals(DoseState.SNOOZED.name, db.doseInstanceDao().getById(dose.id)!!.state)
    }

    @Test
    fun snooze_cannotPushPastMissWindow_becomesMissed() = runTest {
        val (med, sched) = seedFixed()
        val due = nowMs
        val dose = insertScheduledDose(med.id, sched.id, due)
        db.doseInstanceDao().markDue(dose.id)
        val m = machine()
        m.onEnteredDue(dose.id, due)

        // Snooze at 50 min: target would be 65 min but the window closes at 60 → capped at 60.
        nowMs = due + 50 * minute
        m.onSnooze(dose.id, nowMs)
        assertEquals(due + 60 * minute, db.doseInstanceDao().getById(dose.id)!!.snoozeUntilEpochMs)

        // Re-alert at the capped target (= window close) → MISSED, not DUE (D3).
        nowMs = due + 60 * minute
        m.onReAlert(dose.id)
        assertEquals(DoseState.MISSED.name, db.doseInstanceDao().getById(dose.id)!!.state)
    }

    // ── G1: per-medication configurable miss window ────────────────────────────────────────

    @Test
    fun customMissWindow_tight15min_missesAt15Minutes() = runTest {
        // A medication with a 15-minute miss window should miss at 15 min, not 60 min.
        val med = insertMed(missWindowMinutes = 15)
        val sched = insertSchedule(med.id, ScheduleType.FIXED_DAILY)
        val due = nowMs
        val dose = insertScheduledDose(med.id, sched.id, due)
        db.doseInstanceDao().markDue(dose.id)
        machine().onEnteredDue(dose.id, due)

        // 14 minutes: not yet missed.
        nowMs = due + 14 * minute
        machine().onMissCheck(dose.id)
        assertEquals(DoseState.DUE.name, db.doseInstanceDao().getById(dose.id)!!.state)

        // 15 minutes: missed.
        nowMs = due + 15 * minute
        machine().onMissCheck(dose.id)
        assertEquals(DoseState.MISSED.name, db.doseInstanceDao().getById(dose.id)!!.state)
    }

    @Test
    fun customMissWindow_loose180min_doesNotMissAt60Minutes() = runTest {
        // A medication with a 180-minute miss window must still be DUE at 60 min.
        val med = insertMed(missWindowMinutes = 180)
        val sched = insertSchedule(med.id, ScheduleType.FIXED_DAILY)
        val due = nowMs
        val dose = insertScheduledDose(med.id, sched.id, due)
        db.doseInstanceDao().markDue(dose.id)
        machine().onEnteredDue(dose.id, due)

        // 60 minutes: the default would miss here, but not with a 180-min window.
        nowMs = due + 60 * minute
        machine().onMissCheck(dose.id)
        assertEquals(
            "180-min window: dose must still be DUE at 60 min",
            DoseState.DUE.name,
            db.doseInstanceDao().getById(dose.id)!!.state,
        )

        // 180 minutes: missed.
        nowMs = due + 180 * minute
        machine().onMissCheck(dose.id)
        assertEquals(DoseState.MISSED.name, db.doseInstanceDao().getById(dose.id)!!.state)
    }

    @Test
    fun customMissWindow_snooze_capsAtCustomMissClose() = runTest {
        // With a 30-min window: snooze at 20 min (target = 35 min) must cap at 30 min.
        val med = insertMed(missWindowMinutes = 30)
        val sched = insertSchedule(med.id, ScheduleType.FIXED_DAILY)
        val due = nowMs
        val dose = insertScheduledDose(med.id, sched.id, due)
        db.doseInstanceDao().markDue(dose.id)
        val m = machine()
        m.onEnteredDue(dose.id, due)

        nowMs = due + 20 * minute
        m.onSnooze(dose.id, nowMs)

        val snoozed = db.doseInstanceDao().getById(dose.id)!!
        assertEquals(DoseState.SNOOZED.name, snoozed.state)
        // 20 + 15 = 35 min > 30 min window → capped at the 30-min miss close.
        assertEquals(
            "Snooze must cap at the custom 30-min miss window",
            due + 30 * minute,
            snoozed.snoozeUntilEpochMs,
        )

        // Re-alert at the capped target → MISSED (same as default-window behavior, D3).
        nowMs = due + 30 * minute
        m.onReAlert(dose.id)
        assertEquals(DoseState.MISSED.name, db.doseInstanceDao().getById(dose.id)!!.state)
    }

    // ── independence (Law 3) ────────────────────────────────────────────────────────────────

    @Test
    fun missing0800_hasZeroEffectOn1200() = runTest {
        val (med, sched) = seedFixed()
        val dose0800 = insertScheduledDose(med.id, sched.id, nowMs)
        val dose1200 = insertScheduledDose(med.id, sched.id, nowMs + 4 * hour)
        db.doseInstanceDao().markDue(dose0800.id)
        val m = machine()
        m.onEnteredDue(dose0800.id, nowMs)

        // 08:00 misses.
        nowMs += 60 * minute
        m.onMissCheck(dose0800.id)
        assertEquals(DoseState.MISSED.name, db.doseInstanceDao().getById(dose0800.id)!!.state)

        // 12:00 is untouched — still SCHEDULED, fires as its own instance.
        val noon = db.doseInstanceDao().getById(dose1200.id)!!
        assertEquals(DoseState.SCHEDULED.name, noon.state)
        assertNull(noon.missedEpochMs)
    }

    // ── SKIPPED logs no advice ──────────────────────────────────────────────────────────────

    @Test
    fun skip_recordsTransition_withNoAdvice() = runTest {
        val (med, sched) = seedFixed()
        val dose = insertScheduledDose(med.id, sched.id, nowMs)
        db.doseInstanceDao().markDue(dose.id)
        machine().onSkip(dose.id, nowMs)

        val skipped = db.doseInstanceDao().getById(dose.id)!!
        assertEquals(DoseState.SKIPPED.name, skipped.state)
        assertEquals(nowMs, skipped.skippedEpochMs)
        val history = db.doseTransitionDao().getByDose(dose.id)
        val last = history.last()
        assertEquals(DoseTransitionCause.USER_SKIPPED.name, last.cause)
        // The cause vocabulary carries only factual triggers — never a skip/double/make-up
        // instruction (Law 3). USER_SKIPPED records that the user skipped; it advises nothing.
        assertTrue(
            DoseTransitionCause.values().none {
                it.name.contains("DOUBLE") || it.name.contains("MAKEUP") || it.name.contains("TAKE_BOTH")
            },
        )
    }

    // ── escalation re-alert intensity ─────────────────────────────────────────────────────────

    @Test
    fun reAlert_whileDue_raisesEscalationLevel_andSchedulesNext() = runTest {
        val (med, sched) = seedFixed()
        val due = nowMs
        val dose = insertScheduledDose(med.id, sched.id, due)
        db.doseInstanceDao().markDue(dose.id)
        machine().onEnteredDue(dose.id, due)

        nowMs = due + 12 * minute // 2 escalation intervals (5 min each) past due → level 2
        machine().onReAlert(dose.id)

        val last = notifier.posts.last()
        assertEquals(dose.id, last.doseId)
        assertEquals(2, last.level)
        // Still DUE, and a further re-alert is scheduled (escalation continues).
        assertEquals(DoseState.DUE.name, db.doseInstanceDao().getById(dose.id)!!.state)
        assertNotNull(scheduler.lastReAlert(dose.id))
    }

    // ── PRN (spec §2.7) ───────────────────────────────────────────────────────────────────────

    @Test
    fun prn_logsTakenOnly_noScheduledOrMissed_warnsNonBlockingPastMax() = runTest {
        val med = insertMed()
        val sched = insertSchedule(med.id, ScheduleType.PRN, dailyMaxDoses = 2)
        val m = machine()

        val r1 = m.logPrn(med.id, sched.id)
        assertEquals(1, r1.doseNumber)
        assertFalse(r1.exceedsMax)

        nowMs += minute
        val r2 = m.logPrn(med.id, sched.id)
        assertEquals(2, r2.doseNumber)
        assertFalse(r2.exceedsMax)

        nowMs += minute
        val r3 = m.logPrn(med.id, sched.id)
        assertEquals(3, r3.doseNumber)
        assertTrue("3rd dose exceeds the max of 2 → warn", r3.exceedsMax)
        assertEquals(2, r3.dailyMax)

        // All three are TAKEN logs; no SCHEDULED/MISSED PRN rows exist (spec §2.7).
        val rows = db.doseInstanceDao().observeByMedication(med.id).first()
        assertEquals(3, rows.size)
        assertTrue(rows.all { it.state == DoseState.TAKEN.name })
    }

    // ── helpers ───────────────────────────────────────────────────────────────────────────────

    private fun DoseStateMachine.logPrn(medId: String, schedId: String) =
        kotlinx.coroutines.runBlocking { logPrnTaken(medId, schedId) }

    private suspend fun seedFixed(): Pair<MedicationEntity, ScheduleEntity> {
        val med = insertMed()
        val sched = insertSchedule(med.id, ScheduleType.FIXED_DAILY)
        return med to sched
    }

    private suspend fun insertMed(missWindowMinutes: Int = 60): MedicationEntity {
        val med = MedicationEntity(
            id = UUID.randomUUID().toString(),
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
            missWindowMinutes = missWindowMinutes,
            status = MedicationStatus.ACTIVE.name,
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
        type: ScheduleType,
        dailyMaxDoses: Int? = null,
    ): ScheduleEntity {
        val sched = ScheduleEntity(
            id = UUID.randomUUID().toString(),
            medicationId = medId,
            type = type.name,
            scheduledTimesJson = if (type == ScheduleType.FIXED_DAILY) """["08:00"]""" else null,
            daysOfWeekJson = null,
            intervalHours = null,
            intervalAnchorType = null,
            windowStartTime = if (type == ScheduleType.DOSE_WINDOW) "08:00" else null,
            windowEndTime = if (type == ScheduleType.DOSE_WINDOW) "10:00" else null,
            dailyMaxDoses = dailyMaxDoses,
            zoneId = zone.id,
            isActive = true,
            startEpochMs = nowMs,
            endEpochMs = null,
            createdAtEpochMs = nowMs,
        )
        db.scheduleDao().insert(sched)
        return sched
    }

    private suspend fun insertScheduledDose(
        medId: String,
        scheduleId: String,
        dueEpochMs: Long,
        windowEndEpochMs: Long = dueEpochMs + 60L * 60L * 1000L,
    ): DoseInstanceEntity {
        val dose = DoseInstanceEntity(
            id = UUID.randomUUID().toString(),
            medicationId = medId,
            scheduleId = scheduleId,
            dueEpochMs = dueEpochMs,
            windowEndEpochMs = windowEndEpochMs,
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
}

// ── recording fakes ──────────────────────────────────────────────────────────────────────────

private class RecordingTransitionScheduler : DoseTransitionScheduler {
    val missChecks = mutableListOf<Pair<String, Long>>()
    val reAlerts = mutableListOf<Pair<String, Long>>()
    val canceled = mutableListOf<String>()

    override fun scheduleMissCheck(doseId: String, triggerAtEpochMs: Long) {
        missChecks.add(doseId to triggerAtEpochMs)
    }

    override fun scheduleReAlert(doseId: String, triggerAtEpochMs: Long) {
        reAlerts.add(doseId to triggerAtEpochMs)
    }

    override fun cancelTimers(doseId: String) {
        canceled.add(doseId)
    }

    fun lastReAlert(doseId: String): Long? = reAlerts.lastOrNull { it.first == doseId }?.second
}

private class RecordingNotifier : DoseNotifier {
    data class Post(val doseId: String, val medName: String, val due: Long, val level: Int)

    val posts = mutableListOf<Post>()
    val canceled = mutableListOf<String>()

    override fun ensureChannels() = Unit
    override fun postDoseDueAlert(doseId: String, medName: String, dueEpochMs: Long) {
        posts.add(Post(doseId, medName, dueEpochMs, 0))
    }

    override fun postDoseDueAlert(doseId: String, medName: String, dueEpochMs: Long, escalationLevel: Int) {
        posts.add(Post(doseId, medName, dueEpochMs, escalationLevel))
    }

    override fun postTestReminder() = Unit
    override fun postTestCriticalReminder() = Unit
    override fun canUseFullScreen(): Boolean = true
    override fun cancelDoseAlert(doseId: String) {
        canceled.add(doseId)
    }
}
