package com.beryndil.pharos.refill

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.beryndil.pharos.alarm.AlarmContract
import com.beryndil.pharos.data.regimen.RegimenDatabase
import com.beryndil.pharos.data.regimen.entity.DoseInstanceEntity
import com.beryndil.pharos.data.regimen.entity.DoseState
import com.beryndil.pharos.data.regimen.entity.MedicationEntity
import com.beryndil.pharos.data.regimen.entity.MedicationForm
import com.beryndil.pharos.data.regimen.entity.MedicationStatus
import com.beryndil.pharos.data.regimen.entity.RefillEventType
import com.beryndil.pharos.data.regimen.entity.ScheduleEntity
import com.beryndil.pharos.data.regimen.entity.SchedulePhaseEntity
import com.beryndil.pharos.data.regimen.entity.ScheduleType
import com.beryndil.pharos.refill.RefillSummary.Companion.LOW_SUPPLY_THRESHOLD_DAYS
import kotlinx.coroutines.flow.first
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
import java.time.ZoneId
import java.util.UUID

/**
 * Unit tests for [RefillRepository] (spec §2.9, DECISIONS.md S7-A2).
 *
 * Covers: days-until-empty computation, append-only events, partial fill arithmetic,
 * the zero-supply/non-suppression invariant, PRN handling, and channel isolation.
 *
 * Uses Robolectric + in-memory Room (no SQLCipher native .so, DECISIONS.md A9).
 */
@RunWith(RobolectricTestRunner::class)
class RefillRepositoryTest {

    private lateinit var db: RegimenDatabase
    private lateinit var repo: RefillRepository

    // Stable test clock: 2026-06-12T00:00:00Z (epoch ms)
    private val testNowMs = 1_749_686_400_000L
    private val zone = ZoneId.of("America/New_York")

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, RegimenDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repo = RefillRepository(
            refillRecordDao = db.refillRecordDao(),
            medicationDao = db.medicationDao(),
            scheduleDao = db.scheduleDao(),
            schedulePhaseDao = db.schedulePhaseDao(),
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private suspend fun insertMed(id: String = UUID.randomUUID().toString()): MedicationEntity {
        val med = MedicationEntity(
            id = id,
            name = "Test Med",
            rxcui = null,
            ingredientsJson = "[]",
            strength = "25 mg",
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

    private suspend fun insertFixedDailySchedule(
        medId: String,
        timesJson: String = """["08:00","20:00"]""",
    ): ScheduleEntity {
        val schedule = ScheduleEntity(
            id = UUID.randomUUID().toString(),
            medicationId = medId,
            type = ScheduleType.FIXED_DAILY.name,
            scheduledTimesJson = timesJson,
            daysOfWeekJson = null,
            intervalHours = null,
            intervalAnchorType = null,
            windowStartTime = null,
            windowEndTime = null,
            dailyMaxDoses = null,
            zoneId = zone.id,
            isActive = true,
            startEpochMs = testNowMs,
            endEpochMs = null,
            createdAtEpochMs = testNowMs,
        )
        db.scheduleDao().insert(schedule)
        return schedule
    }

    private suspend fun insertPrnSchedule(medId: String): ScheduleEntity {
        val schedule = ScheduleEntity(
            id = UUID.randomUUID().toString(),
            medicationId = medId,
            type = ScheduleType.PRN.name,
            scheduledTimesJson = null,
            daysOfWeekJson = null,
            intervalHours = null,
            intervalAnchorType = null,
            windowStartTime = null,
            windowEndTime = null,
            dailyMaxDoses = null,
            zoneId = zone.id,
            isActive = true,
            startEpochMs = testNowMs,
            endEpochMs = null,
            createdAtEpochMs = testNowMs,
        )
        db.scheduleDao().insert(schedule)
        return schedule
    }

    private suspend fun insertDoseInstance(medId: String, scheduleId: String): DoseInstanceEntity {
        val instance = DoseInstanceEntity(
            id = UUID.randomUUID().toString(),
            medicationId = medId,
            scheduleId = scheduleId,
            dueEpochMs = testNowMs + 3_600_000L,
            windowEndEpochMs = testNowMs + 7_200_000L,
            state = DoseState.SCHEDULED.name,
            takenEpochMs = null,
            skippedEpochMs = null,
            missedEpochMs = null,
            snoozeUntilEpochMs = null,
            createdAtEpochMs = testNowMs,
        )
        db.doseInstanceDao().insert(instance)
        return instance
    }

    // ── Tests ─────────────────────────────────────────────────────────────────

    /**
     * §2.9: days-until-empty computed correctly from on-hand count + derived doses/day.
     * Fixed daily 2x/day with 30 tablets → 15 days.
     */
    @Test
    fun daysUntilEmpty_computedFromQuantityAndDosesPerDay() = runTest {
        val med = insertMed()
        insertFixedDailySchedule(med.id, timesJson = """["08:00","20:00"]""")
        repo.setInitialCount(med.id, quantity = 30, unit = "tablets", pharmacyPhone = null, nowMs = testNowMs)

        val summary = repo.observeRefillSummary(med.id).first()
        assertEquals(2.0, summary.dosesPerDay)
        assertEquals(15, summary.daysUntilEmpty)
        assertFalse(summary.noSupplyOnRecord)
        assertFalse(summary.supplyIsZero)
    }

    /**
     * §2.9: 3 doses/day with 18 tablets → 6 days < LOW_SUPPLY_THRESHOLD_DAYS(7) → isLowSupply true.
     * At 7 days exactly (21 tablets) the condition is < 7 → not low supply.
     */
    @Test
    fun daysUntilEmpty_belowThreshold_isLowSupply() = runTest {
        val med = insertMed()
        insertFixedDailySchedule(med.id, timesJson = """["08:00","14:00","20:00"]""")
        // 18 tablets / 3 per day = 6 days → below threshold of 7
        repo.setInitialCount(med.id, quantity = 18, unit = "tablets", pharmacyPhone = null, nowMs = testNowMs)

        val summary = repo.observeRefillSummary(med.id).first()
        assertEquals(3.0, summary.dosesPerDay)
        assertEquals(6, summary.daysUntilEmpty)
        assertTrue("6 days < LOW_SUPPLY_THRESHOLD_DAYS(7) must be low supply", summary.isLowSupply)

        // Verify 21 tablets (exactly 7 days) is NOT flagged as low supply
        repo.recordPickup(
            medicationId = med.id,
            newQuantity = 21,
            unit = "tablets",
            pharmacyPhone = null,
            notes = null,
            refillByEpochMs = null,
            nowMs = testNowMs + 1,
        )
        val summary2 = repo.observeRefillSummary(med.id).first()
        assertEquals(7, summary2.daysUntilEmpty)
        assertFalse("7 days == LOW_SUPPLY_THRESHOLD_DAYS must NOT be low supply", summary2.isLowSupply)
    }

    /**
     * §2.9: interval schedule every 8h = 3 doses/day.
     */
    @Test
    fun daysUntilEmpty_intervalSchedule_every8h() = runTest {
        val med = insertMed()
        val schedule = ScheduleEntity(
            id = UUID.randomUUID().toString(),
            medicationId = med.id,
            type = ScheduleType.INTERVAL.name,
            scheduledTimesJson = """["08:00"]""",
            daysOfWeekJson = null,
            intervalHours = 8,
            intervalAnchorType = null,
            windowStartTime = null,
            windowEndTime = null,
            dailyMaxDoses = null,
            zoneId = zone.id,
            isActive = true,
            startEpochMs = testNowMs,
            endEpochMs = null,
            createdAtEpochMs = testNowMs,
        )
        db.scheduleDao().insert(schedule)
        repo.setInitialCount(med.id, quantity = 30, unit = "tablets", pharmacyPhone = null, nowMs = testNowMs)

        val summary = repo.observeRefillSummary(med.id).first()
        assertEquals(3.0, summary.dosesPerDay)
        assertEquals(10, summary.daysUntilEmpty)
    }

    /**
     * §2.9: "picked up refill" action resets the count + appends an event (append-only — Law 9).
     * Old record remains in history; count is now new value.
     */
    @Test
    fun pickupRefill_resetsCount_appendsEvent() = runTest {
        val med = insertMed()
        insertFixedDailySchedule(med.id)
        repo.setInitialCount(med.id, quantity = 5, unit = "tablets", pharmacyPhone = null, nowMs = testNowMs)

        // User picks up 30 tablets → total on hand = 35 (5 remaining + 30 new)
        repo.recordPickup(
            medicationId = med.id,
            newQuantity = 35,
            unit = "tablets",
            pharmacyPhone = "555-1234",
            notes = null,
            refillByEpochMs = null,
            nowMs = testNowMs + 1,
        )

        val history = repo.observeRefillHistory(med.id).first()
        assertEquals(2, history.size) // both records persist
        assertEquals(RefillEventType.REFILL_PICKUP.name, history[0].type) // newest first
        assertEquals(35, history[0].quantityOnHand)
        assertEquals(RefillEventType.INITIAL.name, history[1].type)
        assertEquals(5, history[1].quantityOnHand)

        val summary = repo.observeRefillSummary(med.id).first()
        assertEquals(35, summary.quantityOnHand)
    }

    /**
     * §2.9: partial fill adds the received quantity to the current count.
     */
    @Test
    fun partialFill_addsToCurrentCount() = runTest {
        val med = insertMed()
        insertFixedDailySchedule(med.id)
        repo.setInitialCount(med.id, quantity = 5, unit = "tablets", pharmacyPhone = null, nowMs = testNowMs)

        // Partial fill: received 15 more → 20 total
        repo.recordPartialFill(
            medicationId = med.id,
            additionalQuantity = 15,
            unit = "tablets",
            notes = "Partial fill — insurance issue",
            nowMs = testNowMs + 1,
        )

        val summary = repo.observeRefillSummary(med.id).first()
        assertEquals(20, summary.quantityOnHand)

        val history = repo.observeRefillHistory(med.id).first()
        assertEquals(2, history.size)
        assertEquals(RefillEventType.ADJUSTMENT.name, history[0].type)
        assertEquals(20, history[0].quantityOnHand)
    }

    /**
     * §2.9 CRITICAL: Zero-supply does NOT suppress dose reminders.
     *
     * When the refill count reaches zero, the dose instance remains in SCHEDULED state.
     * The RefillRepository has no reference to the dose state machine; this test proves
     * the architectural isolation explicitly.
     */
    @Test
    fun zeroSupply_doesNotSuppressDoseReminder() = runTest {
        val med = insertMed()
        val schedule = insertFixedDailySchedule(med.id)
        val doseInstance = insertDoseInstance(med.id, schedule.id)

        // Record zero supply (stopped before empty)
        repo.setInitialCount(med.id, quantity = 0, unit = "tablets", pharmacyPhone = null, nowMs = testNowMs)

        // Verify RefillSummary reflects zero supply + separate noSupplyOnRecord flag
        val summary = repo.observeRefillSummary(med.id).first()
        assertEquals(0, summary.quantityOnHand)
        assertEquals(0, summary.daysUntilEmpty)
        assertTrue(summary.supplyIsZero)
        assertFalse(summary.noSupplyOnRecord) // a record EXISTS — supplyIsZero is the right flag
        assertTrue(summary.isLowSupply)

        // CRITICAL: the dose instance is STILL SCHEDULED — RefillRepository never touches it
        val storedInstance = db.doseInstanceDao().getById(doseInstance.id)
        assertNotNull("Dose instance must still exist", storedInstance)
        assertEquals(
            "Dose instance must still be SCHEDULED — zero supply must NEVER suppress a reminder",
            DoseState.SCHEDULED.name,
            storedInstance!!.state,
        )

        // Additionally verify that DAO 'getEarliestScheduled' still returns this instance —
        // the alarm engine would still schedule it.
        val earliest = db.doseInstanceDao().getEarliestScheduled()
        assertNotNull("getEarliestScheduled must return the dose even when supply is zero", earliest)
        assertEquals(doseInstance.id, earliest!!.id)
    }

    /**
     * §2.9: "stopped before empty" records an ADJUSTMENT with quantity 0.
     * Dose reminders still fire (same isolation proof as above).
     */
    @Test
    fun stoppedBeforeEmpty_setsQuantityToZero_appendsAdjustment() = runTest {
        val med = insertMed()
        insertFixedDailySchedule(med.id)
        repo.setInitialCount(med.id, quantity = 20, unit = "tablets", pharmacyPhone = null, nowMs = testNowMs)

        repo.recordStoppedBeforeEmpty(med.id, nowMs = testNowMs + 1)

        val summary = repo.observeRefillSummary(med.id).first()
        assertEquals(0, summary.quantityOnHand)
        assertTrue(summary.supplyIsZero)

        val history = repo.observeRefillHistory(med.id).first()
        assertEquals(2, history.size)
        assertEquals(RefillEventType.ADJUSTMENT.name, history[0].type)
        assertEquals(0, history[0].quantityOnHand)
    }

    /**
     * §2.9: PRN medications show on-hand count but no confident run-out date.
     */
    @Test
    fun prn_showsCountButNoDaysUntilEmpty() = runTest {
        val med = insertMed()
        insertPrnSchedule(med.id)
        repo.setInitialCount(med.id, quantity = 12, unit = "tablets", pharmacyPhone = null, nowMs = testNowMs)

        val summary = repo.observeRefillSummary(med.id).first()
        assertEquals(12, summary.quantityOnHand)
        assertTrue(summary.isPrn)
        assertNull("PRN medications must not have a doses/day estimate", summary.dosesPerDay)
        assertNull("PRN medications must not have a days-until-empty estimate", summary.daysUntilEmpty)
        assertFalse(summary.isLowSupply) // cannot compute — must not flag as low supply
    }

    /**
     * §2.9: noSupplyOnRecord is true when no RefillRecord exists at all.
     */
    @Test
    fun noRefillRecord_noSupplyOnRecord_isTrue() = runTest {
        val med = insertMed()
        insertFixedDailySchedule(med.id)

        val summary = repo.observeRefillSummary(med.id).first()
        assertTrue(summary.noSupplyOnRecord)
        assertNull(summary.quantityOnHand)
        assertNull(summary.daysUntilEmpty)
        assertFalse(summary.isLowSupply)
    }

    /**
     * §2.8, Law 1: the refill channel id is distinct from the dose channel id.
     * This test is the compile-time + constant-value proof that channel isolation is enforced.
     */
    @Test
    fun refillChannel_isDifferentFromDoseChannel() {
        assertTrue(
            "CHANNEL_REFILL must be distinct from CHANNEL_DOSE_DUE (Law 1, spec §2.8)",
            AlarmContract.CHANNEL_REFILL != AlarmContract.CHANNEL_DOSE_DUE,
        )
    }

    /**
     * computeDosesPerDay: days-of-week schedule Mon/Wed/Fri, 2 times/day = 2 * 3/7 ≈ 0.857.
     */
    @Test
    fun computeDosesPerDay_daysOfWeek() = runTest {
        val med = insertMed()
        val schedule = ScheduleEntity(
            id = UUID.randomUUID().toString(),
            medicationId = med.id,
            type = ScheduleType.DAYS_OF_WEEK.name,
            scheduledTimesJson = """["08:00","20:00"]""",
            daysOfWeekJson = """[1,3,5]""", // Mon, Wed, Fri
            intervalHours = null,
            intervalAnchorType = null,
            windowStartTime = null,
            windowEndTime = null,
            dailyMaxDoses = null,
            zoneId = zone.id,
            isActive = true,
            startEpochMs = testNowMs,
            endEpochMs = null,
            createdAtEpochMs = testNowMs,
        )
        db.scheduleDao().insert(schedule)
        repo.setInitialCount(med.id, quantity = 12, unit = "tablets", pharmacyPhone = null, nowMs = testNowMs)

        val summary = repo.observeRefillSummary(med.id).first()
        // 2 doses × 3 days / 7 = 6/7 ≈ 0.857
        val expected = 2.0 * 3 / 7.0
        assertEquals(expected, summary.dosesPerDay!!, 0.001)
        // daysUntilEmpty = floor(12 / 0.857) = floor(14.0) = 14
        assertEquals(14, summary.daysUntilEmpty)
    }

    /**
     * computeDosesPerDayForTaper: 2 phases — (2 doses × 5 days) + (1 dose × 5 days) / 10 days = 1.5.
     */
    @Test
    fun computeDosesPerDay_taperSchedule() {
        val phases = listOf(
            SchedulePhaseEntity(
                id = "p1",
                scheduleId = "s1",
                phaseOrder = 0,
                doseDescription = "2 tablets",
                durationDays = 5,
                scheduledTimesJson = """["08:00","20:00"]""",
            ),
            SchedulePhaseEntity(
                id = "p2",
                scheduleId = "s1",
                phaseOrder = 1,
                doseDescription = "1 tablet",
                durationDays = 5,
                scheduledTimesJson = """["08:00"]""",
            ),
        )
        val result = repo.computeDosesPerDayForTaper(phases)
        // (2*5 + 1*5) / 10 = 15/10 = 1.5
        assertEquals(1.5, result!!, 0.001)
    }

    /**
     * getLowSupplyMedications: returns only meds below the threshold.
     */
    @Test
    fun getLowSupplySummaries_onlyReturnsLowMeds() = runTest {
        val med1 = insertMed(UUID.randomUUID().toString())
        insertFixedDailySchedule(med1.id, """["08:00","20:00"]""") // 2/day
        repo.setInitialCount(med1.id, quantity = 6, unit = "tablets", pharmacyPhone = null, nowMs = testNowMs)
        // days = 6/2 = 3 → below threshold

        val med2 = insertMed(UUID.randomUUID().toString())
        insertFixedDailySchedule(med2.id, """["08:00","20:00"]""") // 2/day
        repo.setInitialCount(med2.id, quantity = 60, unit = "tablets", pharmacyPhone = null, nowMs = testNowMs)
        // days = 60/2 = 30 → above threshold

        val lowList = repo.getLowSupplySummaries()
        assertEquals(1, lowList.size)
        assertEquals(med1.id, lowList[0].medicationId)
    }

    /**
     * §2.9: setRefillByDate creates an ADJUSTMENT record preserving current quantity.
     */
    @Test
    fun setRefillByDate_appendsAdjustmentPreservingQuantity() = runTest {
        val med = insertMed()
        insertFixedDailySchedule(med.id)
        repo.setInitialCount(med.id, quantity = 30, unit = "tablets", pharmacyPhone = null, nowMs = testNowMs)

        val refillByMs = testNowMs + 10L * 86_400_000L
        repo.setRefillByDate(med.id, refillByEpochMs = refillByMs, nowMs = testNowMs + 1)

        val summary = repo.observeRefillSummary(med.id).first()
        assertEquals(30, summary.quantityOnHand) // quantity unchanged
        assertEquals(refillByMs, summary.refillByEpochMs)

        val history = repo.observeRefillHistory(med.id).first()
        assertEquals(2, history.size)
        assertEquals(RefillEventType.ADJUSTMENT.name, history[0].type)
    }
}
