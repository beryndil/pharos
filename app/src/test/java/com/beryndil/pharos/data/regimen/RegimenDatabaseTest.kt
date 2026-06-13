package com.beryndil.pharos.data.regimen

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.beryndil.pharos.data.regimen.entity.DoseInstanceEntity
import com.beryndil.pharos.data.regimen.entity.DoseState
import com.beryndil.pharos.data.regimen.entity.MedicationEntity
import com.beryndil.pharos.data.regimen.entity.MedicationForm
import com.beryndil.pharos.data.regimen.entity.MedicationStatus
import com.beryndil.pharos.data.regimen.entity.ScheduleEntity
import com.beryndil.pharos.data.regimen.entity.ScheduleType
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
import java.util.UUID

/**
 * Unit tests for [RegimenDatabase] entities and DAOs.
 *
 * Note on encryption: these tests use [Room.inMemoryDatabaseBuilder] without a SQLCipher
 * [net.zetetic.database.sqlcipher.SupportOpenHelperFactory] because SQLCipher's native .so
 * is not available in the JVM test environment. The Room schema, entities, and DAO logic are
 * fully exercised. The encryption layer is verified at integration level (on-device) per
 * PIPELINE.md §Testing reality.
 */
@RunWith(RobolectricTestRunner::class)
class RegimenDatabaseTest {

    private lateinit var db: RegimenDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, RegimenDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    // ── basic round-trip ──────────────────────────────────────────────────

    @Test
    fun insertAndRetrieveMedication() = runTest {
        val med = sampleMedication()
        db.medicationDao().insert(med)
        val retrieved = db.medicationDao().getById(med.id)
        assertNotNull("Medication should be retrievable after insert", retrieved)
        assertEquals(med, retrieved)
    }

    @Test
    fun insertMedicationAndSchedule_roundTrips() = runTest {
        val med = sampleMedication()
        db.medicationDao().insert(med)

        val schedule = sampleSchedule(med.id)
        db.scheduleDao().insert(schedule)

        val schedules = db.scheduleDao().getActiveByMedicationOnce(med.id)
        assertEquals(1, schedules.size)
        assertEquals(schedule, schedules.first())
    }

    // ── append-only invariant ─────────────────────────────────────────────

    /**
     * Verifying the append-only invariant for [DoseInstanceEntity]:
     *  1. Insert a SCHEDULED dose.
     *  2. Transition it to DUE via the state-transition DAO method.
     *  3. Assert row count is still 1 (no new row created).
     *  4. Assert state is now DUE; other fields are unchanged.
     */
    @Test
    fun appendOnlyInvariant_stateTransitionPreservesOriginalRow() = runTest {
        val med = sampleMedication()
        db.medicationDao().insert(med)

        val schedule = sampleSchedule(med.id)
        db.scheduleDao().insert(schedule)

        val doseId = UUID.randomUUID().toString()
        val dose = DoseInstanceEntity(
            id = doseId,
            medicationId = med.id,
            scheduleId = schedule.id,
            dueEpochMs = 1_700_000_000_000L,
            windowEndEpochMs = 1_700_000_000_000L + 60 * 60_000,
            state = DoseState.SCHEDULED.name,
            takenEpochMs = null,
            skippedEpochMs = null,
            missedEpochMs = null,
            snoozeUntilEpochMs = null,
            createdAtEpochMs = 1_699_000_000_000L,
        )
        db.doseInstanceDao().insert(dose)

        // Transition: SCHEDULED → DUE
        db.doseInstanceDao().markDue(doseId)

        val updated = db.doseInstanceDao().getById(doseId)
        assertNotNull(updated)
        assertEquals("State must be DUE after markDue", DoseState.DUE.name, updated!!.state)
        assertEquals("dueEpochMs must be unchanged", dose.dueEpochMs, updated.dueEpochMs)
        assertEquals(
            "createdAtEpochMs must be unchanged",
            dose.createdAtEpochMs,
            updated.createdAtEpochMs,
        )
        assertNull("takenEpochMs must still be null", updated.takenEpochMs)
        assertEquals(
            "Row count must remain 1 after state transition",
            1,
            db.doseInstanceDao().countById(doseId),
        )
    }

    @Test
    fun appendOnlyInvariant_missingDoseDoesNotAffectNextDose() = runTest {
        val med = sampleMedication()
        db.medicationDao().insert(med)
        val schedule = sampleSchedule(med.id)
        db.scheduleDao().insert(schedule)

        val baseEpoch = 1_700_000_000_000L
        val dose1Id = UUID.randomUUID().toString()
        val dose2Id = UUID.randomUUID().toString()
        db.doseInstanceDao().insert(sampleDose(dose1Id, med.id, schedule.id, baseEpoch))
        db.doseInstanceDao().insert(
            sampleDose(dose2Id, med.id, schedule.id, baseEpoch + 4 * 3_600_000L),
        )

        // Miss the first dose
        db.doseInstanceDao().markDue(dose1Id)
        db.doseInstanceDao().markMissed(dose1Id, baseEpoch + 3_600_000L)

        // Second dose must still be SCHEDULED, unaffected
        val dose2 = db.doseInstanceDao().getById(dose2Id)
        assertNotNull(dose2)
        assertEquals(
            "Second dose must remain SCHEDULED after first dose is missed",
            DoseState.SCHEDULED.name,
            dose2!!.state,
        )
    }

    @Test
    fun appendOnlyInvariant_pastRowPreservedAfterNewScheduleVersion() = runTest {
        val med = sampleMedication()
        db.medicationDao().insert(med)

        val v1Schedule = sampleSchedule(med.id)
        db.scheduleDao().insert(v1Schedule)

        // Supersede v1: deactivate + insert v2
        db.scheduleDao().deactivate(v1Schedule.id)
        val v2Schedule = sampleSchedule(med.id, id = UUID.randomUUID().toString())
        db.scheduleDao().insert(v2Schedule)

        val allVersions = db.scheduleDao().getAllVersionsForMedication(med.id)
        assertEquals("Both schedule versions must persist", 2, allVersions.size)

        val v1 = allVersions.first { it.id == v1Schedule.id }
        assertFalse("v1 must be inactive", v1.isActive)
        assertTrue("v2 must be active", allVersions.first { it.id == v2Schedule.id }.isActive)
    }

    // ── newer-schema guard ────────────────────────────────────────────────
    //
    // Note on testability: the production path (passphrase != null) uses the SQLCipher API
    // which requires the native .so — not available in Robolectric. Full newer-schema
    // verification (throws for v999, doesn't throw for CURRENT_VERSION) is an emulator/device
    // test. The Robolectric tests below cover the null-passphrase (skip) contract only.

    /**
     * With [passphrase] = null, [RegimenDatabaseFactory.enforceSchemaVersion] must skip the
     * version check entirely — even when a plain-SQLite file with version 999 is on disk.
     *
     * Pre-fix: the function always opened the file with the plain Android API. On a SQLCipher-
     * encrypted file that returned SQLITE_NOTADB → [DefaultDatabaseErrorHandler.onCorruption]
     * deleted the file. Post-fix: null passphrase → early return, plain API never called.
     */
    @Test
    fun newerSchemaGuard_nullPassphrase_skipsCheck_noExceptionForNewerVersion() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        val tmpPath = context.getDatabasePath("pharos_regimen_test_newer.db")
        tmpPath.parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(tmpPath.path, null).use { it.setVersion(999) }

        try {
            val expected = context.getDatabasePath(RegimenDatabaseFactory.DATABASE_NAME)
            expected.parentFile?.mkdirs()
            tmpPath.copyTo(expected, overwrite = true)
            // Must NOT throw: null passphrase skips the check.
            RegimenDatabaseFactory.enforceSchemaVersion(context, null)
        } finally {
            context.getDatabasePath(RegimenDatabaseFactory.DATABASE_NAME).delete()
            tmpPath.delete()
        }
    }

    /**
     * With [passphrase] = null, the check is skipped and no exception is thrown regardless of
     * the on-disk version — including when the version matches [RegimenDatabaseFactory.CURRENT_VERSION].
     */
    @Test
    fun newerSchemaGuard_nullPassphrase_skipsCheck_noExceptionForCurrentVersion() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        val tmpPath = context.getDatabasePath("pharos_regimen_test_v1.db")
        tmpPath.parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(tmpPath.path, null)
            .use { it.setVersion(RegimenDatabaseFactory.CURRENT_VERSION) }

        try {
            val expected = context.getDatabasePath(RegimenDatabaseFactory.DATABASE_NAME)
            expected.parentFile?.mkdirs()
            tmpPath.copyTo(expected, overwrite = true)
            // Must NOT throw: null passphrase skips the check.
            RegimenDatabaseFactory.enforceSchemaVersion(context, null)
        } finally {
            context.getDatabasePath(RegimenDatabaseFactory.DATABASE_NAME).delete()
            tmpPath.delete()
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private fun sampleMedication(id: String = UUID.randomUUID().toString()) = MedicationEntity(
        id = id,
        name = "Metoprolol Succinate",
        rxcui = "866427",
        ingredientsJson = """["41493"]""",
        strength = "25 mg",
        form = MedicationForm.TABLET.name,
        doseAmount = "1 tablet",
        prescriber = null,
        pharmacy = null,
        purpose = null,
        isFreeText = false,
        status = MedicationStatus.ACTIVE.name,
        startEpochMs = 1_700_000_000_000L,
        endEpochMs = null,
        createdAtEpochMs = 1_700_000_000_000L,
        updatedAtEpochMs = 1_700_000_000_000L,
    )

    private fun sampleSchedule(
        medicationId: String,
        id: String = UUID.randomUUID().toString(),
    ) = ScheduleEntity(
        id = id,
        medicationId = medicationId,
        type = ScheduleType.FIXED_DAILY.name,
        scheduledTimesJson = """["08:00","20:00"]""",
        daysOfWeekJson = null,
        intervalHours = null,
        intervalAnchorType = null,
        windowStartTime = null,
        windowEndTime = null,
        dailyMaxDoses = null,
        zoneId = "America/New_York",
        isActive = true,
        startEpochMs = 1_700_000_000_000L,
        endEpochMs = null,
        createdAtEpochMs = 1_700_000_000_000L,
    )

    private fun sampleDose(
        id: String,
        medicationId: String,
        scheduleId: String,
        dueEpochMs: Long,
    ) = DoseInstanceEntity(
        id = id,
        medicationId = medicationId,
        scheduleId = scheduleId,
        dueEpochMs = dueEpochMs,
        windowEndEpochMs = dueEpochMs + 3_600_000L,
        state = DoseState.SCHEDULED.name,
        takenEpochMs = null,
        skippedEpochMs = null,
        missedEpochMs = null,
        snoozeUntilEpochMs = null,
        createdAtEpochMs = dueEpochMs - 86_400_000L,
    )
}
