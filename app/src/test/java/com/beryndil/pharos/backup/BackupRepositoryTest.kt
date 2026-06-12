package com.beryndil.pharos.backup

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.beryndil.pharos.data.regimen.RegimenDatabase
import com.beryndil.pharos.data.regimen.entity.DoseInstanceEntity
import com.beryndil.pharos.data.regimen.entity.DoseState
import com.beryndil.pharos.data.regimen.entity.DoseTransitionCause
import com.beryndil.pharos.data.regimen.entity.DoseTransitionEntity
import com.beryndil.pharos.data.regimen.entity.MedicationEntity
import com.beryndil.pharos.data.regimen.entity.MedicationForm
import com.beryndil.pharos.data.regimen.entity.MedicationStatus
import com.beryndil.pharos.data.regimen.entity.RefillRecordEntity
import com.beryndil.pharos.data.regimen.entity.RefillEventType
import com.beryndil.pharos.data.regimen.entity.ScheduleEntity
import com.beryndil.pharos.data.regimen.entity.ScheduleType
import com.beryndil.pharos.data.regimen.entity.SettingEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.UUID

/**
 * Integration tests for [BackupRepository] using an in-memory Room DB (no SQLCipher — per A9).
 *
 * Tests:
 *  1. Backup → restore round-trip reproduces the full regimen including append-only history.
 *  2. Wrong passphrase fails cleanly (RestoreResult.Error, database unchanged).
 *  3. Truncated/corrupt file rejected (RestoreResult.Error, database unchanged).
 *  4. Tampered ciphertext (bad GCM tag) rejected.
 *  5. Newer-schema-version payload rejected.
 *  6. CSV export produces expected rows.
 *  7. Empty-regimen detection returns true for fresh DB, false after med inserted.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackupRepositoryTest {

    private lateinit var db: RegimenDatabase
    private lateinit var context: Context
    private lateinit var repository: BackupRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, RegimenDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = BackupRepository(db, context)
    }

    @After
    fun tearDown() {
        db.close()
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun med(name: String = "Metformin") = MedicationEntity(
        id = UUID.randomUUID().toString(),
        name = name,
        rxcui = null,
        ingredientsJson = "[]",
        strength = "500 mg",
        form = MedicationForm.TABLET.name,
        doseAmount = "1 tablet",
        prescriber = "Dr. Smith",
        pharmacy = null,
        purpose = "blood sugar",
        isFreeText = false,
        status = MedicationStatus.ACTIVE.name,
        startEpochMs = 1_700_000_000_000L,
        endEpochMs = null,
        createdAtEpochMs = 1_700_000_000_000L,
        updatedAtEpochMs = 1_700_000_000_000L,
    )

    private fun schedule(medId: String) = ScheduleEntity(
        id = UUID.randomUUID().toString(),
        medicationId = medId,
        type = ScheduleType.FIXED_DAILY.name,
        scheduledTimesJson = "[\"08:00\"]",
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

    private fun doseInstance(medId: String, scheduleId: String) = DoseInstanceEntity(
        id = UUID.randomUUID().toString(),
        medicationId = medId,
        scheduleId = scheduleId,
        dueEpochMs = 1_700_000_000_000L + 28800000L,
        windowEndEpochMs = 1_700_000_000_000L + 32400000L,
        state = DoseState.TAKEN.name,
        takenEpochMs = 1_700_000_000_000L + 28900000L,
        skippedEpochMs = null,
        missedEpochMs = null,
        snoozeUntilEpochMs = null,
        createdAtEpochMs = 1_700_000_000_000L,
    )

    private fun transition(doseId: String, medId: String) = DoseTransitionEntity(
        id = UUID.randomUUID().toString(),
        doseInstanceId = doseId,
        medicationId = medId,
        fromState = DoseState.DUE.name,
        toState = DoseState.TAKEN.name,
        cause = DoseTransitionCause.USER_TAKEN.name,
        atEpochMs = 1_700_000_000_000L + 28900000L,
    )

    private fun refillRecord(medId: String) = RefillRecordEntity(
        id = UUID.randomUUID().toString(),
        medicationId = medId,
        quantityOnHand = 30,
        quantityUnit = "tablets",
        refillByEpochMs = null,
        pharmacyPhone = null,
        notes = null,
        type = RefillEventType.INITIAL.name,
        createdAtEpochMs = 1_700_000_000_000L,
    )

    /** Encrypt a payload and return a ByteArray representing the backup file. */
    private fun buildBackupBytes(
        payload: BackupPayload,
        passphrase: CharArray = "test-passphrase".toCharArray(),
    ): ByteArray {
        val json = kotlinx.serialization.json.Json { encodeDefaults = true }
        val plaintextBytes = json.encodeToString(BackupPayload.serializer(), payload)
            .toByteArray(Charsets.UTF_8)
        val salt = BackupCrypto.generateSalt()
        val nonce = BackupCrypto.generateNonce()
        val envelope = BackupEnvelope(salt = salt, nonce = nonce, contentLen = plaintextBytes.size.toLong())
        val aad = envelope.toBytes()
        val key = BackupCrypto.deriveKey(passphrase, salt)
        val ciphertext = BackupCrypto.encrypt(plaintextBytes, key, nonce, aad)
        val out = ByteArrayOutputStream()
        out.write(aad)
        out.write(ciphertext)
        return out.toByteArray()
    }

    /** Fake SAF URI backed by [bytes]. */
    private fun uriFor(bytes: ByteArray): Uri {
        // We override openInputStream in the mock resolver, but for tests we use a file URI.
        // Instead, write to a temp file and return its file:// URI.
        val file = java.io.File.createTempFile("backup_test", ".bak")
        file.writeBytes(bytes)
        return Uri.fromFile(file)
    }

    private fun outputUri(): Pair<Uri, java.io.File> {
        val file = java.io.File.createTempFile("backup_out", ".bak")
        return Pair(Uri.fromFile(file), file)
    }

    // ── Tests ─────────────────────────────────────────────────────────────────

    @Test
    fun `round-trip backup and restore reproduces full regimen including history`() = runTest {
        // Set up a regimen with a medication, schedule, dose instance, transition, refill
        val medication = med()
        val sched = schedule(medication.id)
        val dose = doseInstance(medication.id, sched.id)
        val trans = transition(dose.id, medication.id)
        val refill = refillRecord(medication.id)
        val setting = SettingEntity("key1", "val1", 1_700_000_000_000L)

        db.medicationDao().insert(medication)
        db.scheduleDao().insert(sched)
        db.doseInstanceDao().insert(dose)
        db.doseTransitionDao().insert(trans)
        db.refillRecordDao().insert(refill)
        db.settingDao().upsert(setting)

        val passphrase = "secure-phrase".toCharArray()

        // Create backup
        val (outUri, outFile) = outputUri()
        val backupResult = repository.createBackup(passphrase.copyOf(), outUri)
        assertTrue("Backup must succeed: $backupResult", backupResult is BackupResult.Success)
        assertTrue("Output file must be non-empty", outFile.length() > 0)

        // Wipe the database (simulate post-wipe scenario)
        val restoreDao = db.restoreDao()
        restoreDao.clearDoseTransitions()
        restoreDao.clearDoseInstances()
        restoreDao.clearSchedulePhases()
        restoreDao.clearSchedules()
        restoreDao.clearRefillRecords()
        restoreDao.clearMedications()
        restoreDao.clearSettings()
        assertEquals(0, db.restoreDao().countMedications())

        // Restore
        val restoreResult = repository.restore(passphrase.copyOf(), outUri)
        assertTrue("Restore must succeed: $restoreResult", restoreResult is RestoreResult.Success)
        assertEquals(1, (restoreResult as RestoreResult.Success).medicationCount)

        // Verify full regimen is restored
        val restoredMeds = db.medicationDao().getAll()
        assertEquals(1, restoredMeds.size)
        assertEquals(medication.id, restoredMeds[0].id)
        assertEquals(medication.name, restoredMeds[0].name)
        assertEquals(medication.prescriber, restoredMeds[0].prescriber)

        val restoredSchedules = db.scheduleDao().getAll()
        assertEquals(1, restoredSchedules.size)
        assertEquals(sched.id, restoredSchedules[0].id)

        // Append-only history preserved
        val restoredInstances = db.doseInstanceDao().getAll()
        assertEquals(1, restoredInstances.size)
        assertEquals(dose.id, restoredInstances[0].id)
        assertEquals(DoseState.TAKEN.name, restoredInstances[0].state)

        val restoredTransitions = db.doseTransitionDao().getAll()
        assertEquals(1, restoredTransitions.size)
        assertEquals(trans.id, restoredTransitions[0].id)
        assertEquals(DoseTransitionCause.USER_TAKEN.name, restoredTransitions[0].cause)

        val restoredRefills = db.refillRecordDao().getAll()
        assertEquals(1, restoredRefills.size)
        assertEquals(refill.id, restoredRefills[0].id)

        val restoredSettings = db.settingDao().getAll()
        assertEquals(1, restoredSettings.size)
        assertEquals("key1", restoredSettings[0].key)
    }

    @Test
    fun `wrong passphrase is rejected cleanly with no partial import`() = runTest {
        // Seed some data
        val medication = med()
        db.medicationDao().insert(medication)

        val correctPass = "correct-password".toCharArray()
        val wrongPass = "wrong-password".toCharArray()

        // Create backup with correct passphrase
        val (outUri, _) = outputUri()
        repository.createBackup(correctPass, outUri)

        // Clear the DB (simulate post-wipe)
        val rd = db.restoreDao()
        rd.clearDoseTransitions(); rd.clearDoseInstances(); rd.clearSchedulePhases()
        rd.clearSchedules(); rd.clearRefillRecords(); rd.clearMedications(); rd.clearSettings()
        assertEquals(0, db.restoreDao().countMedications())

        // Attempt restore with wrong passphrase
        val result = repository.restore(wrongPass, outUri)
        assertTrue("Wrong passphrase must return RestoreResult.Error", result is RestoreResult.Error)
        val message = (result as RestoreResult.Error).message
        assertTrue("Error message should describe the problem", message.isNotBlank())

        // Database must remain empty — no partial import
        assertEquals(
            "Database must be untouched after a wrong-passphrase restore",
            0, db.restoreDao().countMedications(),
        )
    }

    @Test
    fun `truncated backup file is rejected`() = runTest {
        val medication = med()
        db.medicationDao().insert(medication)

        val (outUri, outFile) = outputUri()
        repository.createBackup("passphrase".toCharArray(), outUri)

        // Truncate the backup to half its size
        val truncatedBytes = outFile.readBytes().take(outFile.length().toInt() / 2).toByteArray()
        val truncUri = uriFor(truncatedBytes)

        val result = repository.restore("passphrase".toCharArray(), truncUri)
        assertTrue("Truncated file must be rejected: $result", result is RestoreResult.Error)
    }

    @Test
    fun `tampered ciphertext is rejected`() = runTest {
        val medication = med()
        db.medicationDao().insert(medication)

        val (outUri, outFile) = outputUri()
        repository.createBackup("passphrase".toCharArray(), outUri)

        // Flip a bit in the ciphertext (past the header)
        val bytes = outFile.readBytes().clone()
        val flipPos = BackupEnvelope.HEADER_SIZE + 5
        bytes[flipPos] = (bytes[flipPos].toInt() xor 0x01).toByte()
        val tamperedUri = uriFor(bytes)

        val result = repository.restore("passphrase".toCharArray(), tamperedUri)
        assertTrue("Tampered file must be rejected: $result", result is RestoreResult.Error)
    }

    @Test
    fun `newer schema version backup is refused`() = runTest {
        // Build a payload with a future schema version
        val futurePayload = BackupPayload(
            schemaVersion = BackupPayload.CURRENT_SCHEMA_VERSION + 1,
            exportedAtEpochMs = 1_700_000_000_000L,
            medications = emptyList(),
            schedules = emptyList(),
            schedulePhases = emptyList(),
            doseInstances = emptyList(),
            doseTransitions = emptyList(),
            refillRecords = emptyList(),
            settings = emptyList(),
        )
        val bytes = buildBackupBytes(futurePayload)
        val uri = uriFor(bytes)

        val result = repository.restore("test-passphrase".toCharArray(), uri)
        assertTrue("Future schema must be refused: $result", result is RestoreResult.Error)
        val message = (result as RestoreResult.Error).message
        assertTrue("Error message should mention schema/version", message.contains("schema") || message.contains("version") || message.contains("app"))
    }

    @Test
    fun `empty-regimen detection returns true for fresh DB`() = runTest {
        assertTrue("Fresh DB must be detected as empty", repository.isRegimenEmpty())
    }

    @Test
    fun `empty-regimen detection returns false after medication inserted`() = runTest {
        db.medicationDao().insert(med())
        assertTrue("Non-empty DB must not be detected as empty", !repository.isRegimenEmpty())
    }

    @Test
    fun `CSV export produces header and one row per medication`() = runTest {
        db.medicationDao().insert(med("Metformin"))
        db.medicationDao().insert(med("Lisinopril"))

        val (csvUri, csvFile) = outputUri()
        val result = repository.exportCsv(csvUri)
        assertTrue("CSV export must succeed: $result", result is ExportResult.Success)

        val csv = csvFile.readText(Charsets.UTF_8)
        assertTrue("CSV must have a header row", csv.lines()[0].contains("Name"))
        val dataLines = csv.lines().drop(1).filter { it.isNotBlank() }
        assertEquals("CSV must have one row per medication", 2, dataLines.size)
        assertTrue("CSV must contain medication names", csv.contains("Metformin"))
        assertTrue("CSV must contain medication names", csv.contains("Lisinopril"))
    }

    @Test
    fun `restore with all-zeros content (bad file) returns error`() = runTest {
        val badBytes = ByteArray(200) // all zeros — not a valid backup
        val uri = uriFor(badBytes)
        val result = repository.restore("passphrase".toCharArray(), uri)
        assertTrue("All-zeros file must be rejected: $result", result is RestoreResult.Error)
    }
}

