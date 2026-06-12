package com.beryndil.pharos.dose

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.beryndil.pharos.alarm.DoseActionHandler
import com.beryndil.pharos.alarm.DoseNotifier
import com.beryndil.pharos.data.dose.DoseRepository
import com.beryndil.pharos.data.regimen.RegimenDatabase
import com.beryndil.pharos.data.regimen.entity.DoseInstanceEntity
import com.beryndil.pharos.data.regimen.entity.DoseState
import com.beryndil.pharos.data.regimen.entity.MedicationEntity
import com.beryndil.pharos.data.regimen.entity.MedicationForm
import com.beryndil.pharos.data.regimen.entity.MedicationStatus
import com.beryndil.pharos.data.regimen.entity.ScheduleEntity
import com.beryndil.pharos.data.regimen.entity.ScheduleType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

/**
 * Tests for [DoseRepository] — specifically the PRN observation behavior (spec §2.7, A2-3).
 *
 * Uses Robolectric + in-memory Room (no SQLCipher, DECISIONS.md A9).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DoseRepositoryTest {

    private lateinit var db: RegimenDatabase
    private lateinit var repo: DoseRepository

    // Fixed test clock: 2026-06-12T12:00:00Z
    private val testNowMs = 1_749_729_600_000L
    private val testZone = ZoneId.of("America/New_York")

    // Minimal no-op DoseNotifier for constructing DoseStateMachine in tests.
    private val noOpNotifier = object : DoseNotifier {
        override fun ensureChannels() = Unit
        override fun postDoseDueAlert(doseId: String, medName: String, dueEpochMs: Long) = Unit
        override fun postTestReminder() = Unit
        override fun postTestCriticalReminder() = Unit
        override fun canUseFullScreen() = false
    }

    // Minimal no-op DoseTransitionScheduler for constructing DoseStateMachine in tests.
    private val noOpTransitionScheduler = object : DoseTransitionScheduler {
        override fun scheduleMissCheck(doseId: String, triggerAtEpochMs: Long) = Unit
        override fun scheduleReAlert(doseId: String, triggerAtEpochMs: Long) = Unit
        override fun cancelTimers(doseId: String) = Unit
    }

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, RegimenDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        val stateMachine = DoseStateMachine(
            doseInstanceDao = db.doseInstanceDao(),
            doseTransitionDao = db.doseTransitionDao(),
            medicationDao = db.medicationDao(),
            scheduleDao = db.scheduleDao(),
            transitionScheduler = noOpTransitionScheduler,
            notifier = noOpNotifier,
            now = { testNowMs },
            zoneProvider = { testZone },
        )

        repo = DoseRepository(
            doseInstanceDao = db.doseInstanceDao(),
            doseTransitionDao = db.doseTransitionDao(),
            medicationDao = db.medicationDao(),
            scheduleDao = db.scheduleDao(),
            stateMachine = stateMachine,
            now = { testNowMs },
            zoneProvider = { testZone },
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private suspend fun insertMed(name: String = "Lorazepam"): MedicationEntity {
        val med = MedicationEntity(
            id = UUID.randomUUID().toString(),
            name = name,
            rxcui = null,
            ingredientsJson = "[]",
            strength = "0.5 mg",
            form = MedicationForm.TABLET.name,
            doseAmount = "1 tablet",
            prescriber = null,
            pharmacy = null,
            purpose = null,
            isFreeText = false,
            status = MedicationStatus.ACTIVE.name,
            startEpochMs = testNowMs,
            endEpochMs = null,
            createdAtEpochMs = testNowMs,
            updatedAtEpochMs = testNowMs,
        )
        db.medicationDao().insert(med)
        return med
    }

    private suspend fun insertPrnSchedule(
        medId: String,
        dailyMax: Int? = null,
        isActive: Boolean = true,
    ): ScheduleEntity {
        val sched = ScheduleEntity(
            id = UUID.randomUUID().toString(),
            medicationId = medId,
            type = ScheduleType.PRN.name,
            scheduledTimesJson = null,
            daysOfWeekJson = null,
            intervalHours = null,
            intervalAnchorType = null,
            windowStartTime = null,
            windowEndTime = null,
            dailyMaxDoses = dailyMax,
            zoneId = testZone.id,
            isActive = isActive,
            startEpochMs = testNowMs,
            endEpochMs = null,
            createdAtEpochMs = testNowMs,
        )
        db.scheduleDao().insert(sched)
        return sched
    }

    private suspend fun insertTakenDose(medId: String, schedId: String, takenMs: Long) {
        val dose = DoseInstanceEntity(
            id = UUID.randomUUID().toString(),
            medicationId = medId,
            scheduleId = schedId,
            dueEpochMs = takenMs,
            windowEndEpochMs = null,
            state = DoseState.TAKEN.name,
            takenEpochMs = takenMs,
            skippedEpochMs = null,
            missedEpochMs = null,
            snoozeUntilEpochMs = null,
            createdAtEpochMs = takenMs,
        )
        db.doseInstanceDao().insert(dose)
    }

    private fun startOfDayMs(): Long = Instant.ofEpochMilli(testNowMs)
        .atZone(testZone)
        .toLocalDate()
        .atStartOfDay(testZone)
        .toInstant()
        .toEpochMilli()

    // ── Tests ─────────────────────────────────────────────────────────────────

    @Test
    fun `observePrnMeds returns empty when no PRN schedules exist`() = runTest {
        insertMed()
        // No PRN schedule inserted — today screen should show nothing in the PRN section
        val rows = repo.observePrnMeds().first()
        assertTrue("No PRN schedules → empty list", rows.isEmpty())
    }

    @Test
    fun `observePrnMeds returns correct medication info`() = runTest {
        val med = insertMed("Alprazolam")
        val sched = insertPrnSchedule(med.id, dailyMax = 3)

        val rows = repo.observePrnMeds().first()
        assertEquals(1, rows.size)
        val row = rows[0]
        assertEquals(med.id, row.medicationId)
        assertEquals(sched.id, row.scheduleId)
        assertEquals("Alprazolam", row.medName)
        assertEquals("0.5 mg", row.strength)
        assertEquals(3, row.dailyMax)
        assertEquals(0, row.dosesToday)
    }

    @Test
    fun `observePrnMeds counts TAKEN doses logged today`() = runTest {
        val med = insertMed()
        val sched = insertPrnSchedule(med.id)

        val startOfDay = startOfDayMs()
        insertTakenDose(med.id, sched.id, startOfDay + 1_000L)
        insertTakenDose(med.id, sched.id, testNowMs)

        val rows = repo.observePrnMeds().first()
        assertEquals(1, rows.size)
        assertEquals(2, rows[0].dosesToday)
    }

    @Test
    fun `observePrnMeds does not count doses from before today`() = runTest {
        val med = insertMed()
        val sched = insertPrnSchedule(med.id)

        val startOfDay = startOfDayMs()
        insertTakenDose(med.id, sched.id, startOfDay - 1_000L) // yesterday
        insertTakenDose(med.id, sched.id, testNowMs)            // today

        val rows = repo.observePrnMeds().first()
        assertEquals(1, rows.size)
        assertEquals("Only today's dose counted", 1, rows[0].dosesToday)
    }

    @Test
    fun `observePrnMeds excludes inactive schedules`() = runTest {
        val med = insertMed()
        insertPrnSchedule(med.id, isActive = false)

        val rows = repo.observePrnMeds().first()
        assertTrue("Inactive PRN schedule must not appear", rows.isEmpty())
    }

    @Test
    fun `observePrnMeds dailyMax is null when not configured`() = runTest {
        val med = insertMed()
        insertPrnSchedule(med.id, dailyMax = null)

        val rows = repo.observePrnMeds().first()
        assertEquals(1, rows.size)
        assertEquals(null, rows[0].dailyMax)
    }
}
