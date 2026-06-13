package com.beryndil.pharos.backup

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import com.beryndil.pharos.data.regimen.RegimenDatabase
import com.beryndil.pharos.data.regimen.entity.DoseInstanceEntity
import com.beryndil.pharos.data.regimen.entity.DoseTransitionEntity
import com.beryndil.pharos.data.regimen.entity.MedicationEntity
import com.beryndil.pharos.data.regimen.entity.RefillRecordEntity
import com.beryndil.pharos.data.regimen.entity.ScheduleEntity
import com.beryndil.pharos.data.regimen.entity.SchedulePhaseEntity
import com.beryndil.pharos.data.regimen.entity.SettingEntity
import com.beryndil.pharos.data.regimen.entity.ScheduleType
import com.beryndil.pharos.medication.export.MedListPdfExporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.DataInputStream
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Orchestrates encrypted backup creation, restore, and plaintext export (spec §2.12).
 *
 * Laws enforced:
 *  - Law 2: no paywall; these operations are always available.
 *  - Law 4: backup only writes to a user-chosen URI via SAF — no off-device transmission.
 *  - Law 7: every user always has a free recovery path.
 *  - Law 9: append-only history is fully preserved in the backup; restore replaces all
 *           tables in a single transaction (DECISIONS.md S9-A2: replace-all policy).
 *
 * Crypto: AES-256-GCM with Argon2id key derivation (see [BackupCrypto], [BackupEnvelope]).
 * Passphrase [CharArray]s are zeroed immediately after key derivation inside this class;
 * the derived key is zeroed by [BackupCrypto.encrypt]/[BackupCrypto.decrypt].
 */
class BackupRepository(
    private val db: RegimenDatabase,
    private val context: Context,
    /**
     * Shared PDF renderer — also used by the Today-screen "Email to doctor" action.
     * Injected so both callers share the same instance from AppContainer.
     */
    val pdfExporter: MedListPdfExporter = MedListPdfExporter(db),
    /**
     * Called after a restore completes successfully, while still on the IO dispatcher.
     * Use this to re-arm exact alarms and re-enqueue WorkManager jobs keyed to the restored
     * regimen (items A2-1 / A2-2). Injected as a lambda rather than a direct dependency on
     * [com.beryndil.pharos.alarm.AlarmCoordinator] to avoid a cyclic dependency
     * (BackupRepository → AlarmCoordinator → ScheduleRepository → …).
     */
    private val onRestoreComplete: suspend () -> Unit = {},
) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    // -- Public API ------------------------------------------------------------

    /**
     * Create an encrypted backup at [outputUri] (obtained via SAF ACTION_CREATE_DOCUMENT).
     *
     * The backup contains the full user regimen: medications, schedules, dose history
     * (append-only, all transitions), refill records, and settings.
     *
     * @param passphrase User-supplied passphrase. Zeroed by this function after key derivation.
     * @param outputUri SAF URI for the destination (Downloads, Drive, etc.).
     * @param exportedAtEpochMs Override for the "exported at" timestamp (injectable for tests).
     */
    suspend fun createBackup(
        passphrase: CharArray,
        outputUri: Uri,
        exportedAtEpochMs: Long = Instant.now().toEpochMilli(),
    ): BackupResult = withContext(Dispatchers.IO) {
        try {
            val payload = readAllData(exportedAtEpochMs)
            val plaintextBytes = json.encodeToString(payload).toByteArray(Charsets.UTF_8)

            val salt = BackupCrypto.generateSalt()
            val nonce = BackupCrypto.generateNonce()
            val envelope = BackupEnvelope(
                salt = salt,
                nonce = nonce,
                contentLen = plaintextBytes.size.toLong(),
            )
            val aad = envelope.toBytes()
            val key = BackupCrypto.deriveKey(passphrase, salt)
            passphrase.fill('\u0000')

            val ciphertext = BackupCrypto.encrypt(plaintextBytes, key, nonce, aad)
            // key already zeroed by BackupCrypto.encrypt

            context.contentResolver.openOutputStream(outputUri)?.use { stream ->
                stream.write(aad)
                stream.write(ciphertext)
                stream.flush()
            } ?: return@withContext BackupResult.Error("Could not open output file.")

            BackupResult.Success
        } catch (e: Exception) {
            BackupResult.Error("Backup failed: ${e.message}")
        }
    }

    /**
     * Restore from an encrypted backup at [inputUri].
     *
     * Steps:
     *  1. Read + validate the envelope header (magic, version, KDF).
     *  2. Derive the key from [passphrase] + stored salt.
     *  3. Decrypt — throws [InvalidBackupException] on auth-tag failure (wrong passphrase /
     *     tampered file) WITHOUT exposing any plaintext.
     *  4. Validate the JSON payload's schema version.
     *  5. Replace all tables atomically via [withTransaction] (DECISIONS.md S9-A2).
     *
     * REJECT policy: if ANY validation step fails the database is left untouched.
     * The [InvalidBackupException] message is suitable for display to the user.
     *
     * @param passphrase User-supplied passphrase. Zeroed by this function after key derivation.
     */
    suspend fun restore(
        passphrase: CharArray,
        inputUri: Uri,
    ): RestoreResult = withContext(Dispatchers.IO) {
        try {
            val (envelope, rawHeader, ciphertext) = readBackupFile(inputUri)

            val key = BackupCrypto.deriveKey(passphrase, envelope.salt)
            passphrase.fill('\u0000')

            // Decrypt — AEADBadTagException thrown here on wrong passphrase / tampered data.
            val plaintext = try {
                BackupCrypto.decrypt(ciphertext, key, envelope.nonce, rawHeader)
            } catch (e: javax.crypto.AEADBadTagException) {
                throw InvalidBackupException(
                    "Wrong passphrase, or the file is corrupt. No data was imported.",
                    e,
                )
            }
            // key already zeroed by BackupCrypto.decrypt

            val payload = try {
                json.decodeFromString<BackupPayload>(plaintext.toString(Charsets.UTF_8))
            } catch (e: Exception) {
                throw InvalidBackupException(
                    "Backup contents are invalid. The file may be corrupt.",
                    e,
                )
            }

            if (payload.schemaVersion > BackupPayload.CURRENT_SCHEMA_VERSION) {
                throw InvalidBackupException(
                    "This backup was created with a newer version of the app (schema v${payload.schemaVersion}). " +
                        "Update the app to restore it.",
                )
            }

            importPayload(payload)
            onRestoreComplete()
            RestoreResult.Success(
                medicationCount = payload.medications.size,
            )
        } catch (e: InvalidBackupException) {
            RestoreResult.Error(e.message ?: "Restore failed.")
        } catch (e: Exception) {
            RestoreResult.Error("Restore failed: ${e.message}")
        }
    }

    /**
     * Export a human-readable PDF medication list to [outputUri].
     *
     * The PDF is NOT encrypted — it is the "printable / exportable list" (spec §2.12).
     * Rendering is delegated to [MedListPdfExporter] which is also shared with the
     * Today-screen "Email to doctor" action.
     */
    suspend fun exportPdf(
        outputUri: Uri,
        exportedAtEpochMs: Long = Instant.now().toEpochMilli(),
    ): ExportResult = withContext(Dispatchers.IO) {
        try {
            context.contentResolver.openOutputStream(outputUri)?.use { stream ->
                pdfExporter.writeTo(stream, exportedAtEpochMs)
            } ?: return@withContext ExportResult.Error("Could not open output file.")
            ExportResult.Success
        } catch (e: Exception) {
            ExportResult.Error("PDF export failed: ${e.message}")
        }
    }

    /**
     * Export a CSV medication list to [outputUri].
     *
     * NOT encrypted (spec §2.12: plaintext shareable list, distinct from the encrypted backup).
     * Columns: Name, Strength, Form, Schedule, Prescriber, Pharmacy, Status, Refill By
     */
    suspend fun exportCsv(outputUri: Uri): ExportResult = withContext(Dispatchers.IO) {
        try {
            val medications = db.medicationDao().getAll()
            val schedules = db.scheduleDao().getAll()
            val refills = db.refillRecordDao().getAll()

            val scheduleMap = schedules.groupBy { it.medicationId }
            val latestRefill = refills.groupBy { it.medicationId }
                .mapValues { (_, records) -> records.maxByOrNull { it.createdAtEpochMs } }

            val dateFmt = DateTimeFormatter.ISO_LOCAL_DATE.withZone(ZoneId.systemDefault())

            val sb = StringBuilder()
            sb.appendLine("Name,Strength,Form,Schedule,Prescriber,Pharmacy,Status,\"Refill By\"")
            for (med in medications) {
                val schedule = scheduleMap[med.id]
                    ?.filter { it.isActive }
                    ?.joinToString("; ") { it.toDisplayString() }
                    ?: ""
                val refillBy = latestRefill[med.id]?.refillByEpochMs?.let {
                    dateFmt.format(Instant.ofEpochMilli(it))
                } ?: ""
                sb.appendLine(
                    listOf(
                        med.name,
                        med.strength,
                        med.form,
                        schedule,
                        med.prescriber ?: "",
                        med.pharmacy ?: "",
                        med.status,
                        refillBy,
                    ).joinToString(",") { "\"${it.replace("\"", "\"\"")}\"" },
                )
            }

            context.contentResolver.openOutputStream(outputUri)?.use { stream ->
                stream.write(sb.toString().toByteArray(Charsets.UTF_8))
            } ?: return@withContext ExportResult.Error("Could not open output file.")

            ExportResult.Success
        } catch (e: Exception) {
            ExportResult.Error("CSV export failed: ${e.message}")
        }
    }

    /**
     * Returns true when the regimen has no medications (post-wipe or first install).
     * Used to surface the "Restore from backup" offer in the empty medications state (spec §2.12).
     */
    suspend fun isRegimenEmpty(): Boolean = withContext(Dispatchers.IO) {
        db.restoreDao().countMedications() == 0
    }

    // -- Private helpers -------------------------------------------------------

    private suspend fun readAllData(exportedAtEpochMs: Long): BackupPayload {
        val medications = db.medicationDao().getAll().map { it.toBackup() }
        val schedules = db.scheduleDao().getAll().map { it.toBackup() }
        val phases = db.schedulePhaseDao().getAll().map { it.toBackup() }
        val instances = db.doseInstanceDao().getAll().map { it.toBackup() }
        val transitions = db.doseTransitionDao().getAll().map { it.toBackup() }
        val refills = db.refillRecordDao().getAll().map { it.toBackup() }
        val settings = db.settingDao().getAll().map { it.toBackup() }

        return BackupPayload(
            exportedAtEpochMs = exportedAtEpochMs,
            medications = medications,
            schedules = schedules,
            schedulePhases = phases,
            doseInstances = instances,
            doseTransitions = transitions,
            refillRecords = refills,
            settings = settings,
        )
    }

    /** Read the raw backup file into (envelope, rawHeader bytes, ciphertext). */
    private fun readBackupFile(uri: Uri): Triple<BackupEnvelope, ByteArray, ByteArray> {
        val stream = context.contentResolver.openInputStream(uri)
            ?: throw InvalidBackupException("Could not open the backup file.")

        return stream.use { input ->
            val din = DataInputStream(input)
            val (envelope, rawHeader) = BackupEnvelope.readFrom(din)

            // Ciphertext = plaintext + 16-byte GCM tag
            val ciphertextLen = envelope.contentLen + 16
            if (ciphertextLen > BackupEnvelope.MAX_PLAINTEXT_LEN + 16) {
                throw InvalidBackupException("Backup file is too large to be valid.")
            }
            val ciphertext = ByteArray(ciphertextLen.toInt())
            try {
                din.readFully(ciphertext)
            } catch (e: IOException) {
                throw InvalidBackupException(
                    "Backup file is truncated or corrupt. No data was imported.",
                    e,
                )
            }
            Triple(envelope, rawHeader, ciphertext)
        }
    }

    /**
     * Import [payload] into the database, replacing all existing data atomically.
     *
     * Restore policy: replace-all (DECISIONS.md S9-A2).
     * The entire operation is wrapped in a single Room [withTransaction] call so that
     * either the full import succeeds or the database is left completely unchanged.
     */
    private suspend fun importPayload(payload: BackupPayload) {
        val restoreDao = db.restoreDao()
        db.withTransaction {
            // Delete in FK-safe order (child tables first)
            restoreDao.clearDoseTransitions()
            restoreDao.clearDoseInstances()
            restoreDao.clearSchedulePhases()
            restoreDao.clearSchedules()
            restoreDao.clearRefillRecords()
            restoreDao.clearMedications()
            restoreDao.clearSettings()

            // Insert in FK-safe order (parent tables first)
            restoreDao.insertMedications(payload.medications.map { it.toEntity() })
            restoreDao.insertSchedules(payload.schedules.map { it.toEntity() })
            restoreDao.insertSchedulePhases(payload.schedulePhases.map { it.toEntity() })
            restoreDao.insertDoseInstances(payload.doseInstances.map { it.toEntity() })
            restoreDao.insertDoseTransitions(payload.doseTransitions.map { it.toEntity() })
            restoreDao.insertRefillRecords(payload.refillRecords.map { it.toEntity() })
            restoreDao.insertSettings(payload.settings.map { it.toEntity() })
        }
    }

    // -- Entity ↔ backup DTO mappers -------------------------------------------

    private fun MedicationEntity.toBackup() = MedicationBackup(
        id = id, name = name, rxcui = rxcui, ingredientsJson = ingredientsJson,
        strength = strength, form = form, doseAmount = doseAmount, prescriber = prescriber,
        prescriberPhone = prescriberPhone, pharmacy = pharmacy, pharmacyPhone = pharmacyPhone,
        purpose = purpose, isFreeText = isFreeText, status = status,
        startEpochMs = startEpochMs, endEpochMs = endEpochMs,
        createdAtEpochMs = createdAtEpochMs, updatedAtEpochMs = updatedAtEpochMs,
    )

    private fun MedicationBackup.toEntity() = MedicationEntity(
        id = id, name = name, rxcui = rxcui, ingredientsJson = ingredientsJson,
        strength = strength, form = form, doseAmount = doseAmount, prescriber = prescriber,
        prescriberPhone = prescriberPhone, pharmacy = pharmacy, pharmacyPhone = pharmacyPhone,
        purpose = purpose, isFreeText = isFreeText, status = status,
        startEpochMs = startEpochMs, endEpochMs = endEpochMs,
        createdAtEpochMs = createdAtEpochMs, updatedAtEpochMs = updatedAtEpochMs,
    )

    private fun ScheduleEntity.toBackup() = ScheduleBackup(
        id = id, medicationId = medicationId, type = type,
        scheduledTimesJson = scheduledTimesJson, daysOfWeekJson = daysOfWeekJson,
        intervalHours = intervalHours, intervalAnchorType = intervalAnchorType,
        windowStartTime = windowStartTime, windowEndTime = windowEndTime,
        dailyMaxDoses = dailyMaxDoses, zoneId = zoneId, isActive = isActive,
        startEpochMs = startEpochMs, endEpochMs = endEpochMs,
        createdAtEpochMs = createdAtEpochMs,
    )

    private fun ScheduleBackup.toEntity() = ScheduleEntity(
        id = id, medicationId = medicationId, type = type,
        scheduledTimesJson = scheduledTimesJson, daysOfWeekJson = daysOfWeekJson,
        intervalHours = intervalHours, intervalAnchorType = intervalAnchorType,
        windowStartTime = windowStartTime, windowEndTime = windowEndTime,
        dailyMaxDoses = dailyMaxDoses, zoneId = zoneId, isActive = isActive,
        startEpochMs = startEpochMs, endEpochMs = endEpochMs,
        createdAtEpochMs = createdAtEpochMs,
    )

    private fun SchedulePhaseEntity.toBackup() = SchedulePhaseBackup(
        id = id, scheduleId = scheduleId, phaseOrder = phaseOrder,
        doseDescription = doseDescription, durationDays = durationDays,
        scheduledTimesJson = scheduledTimesJson,
    )

    private fun SchedulePhaseBackup.toEntity() = SchedulePhaseEntity(
        id = id, scheduleId = scheduleId, phaseOrder = phaseOrder,
        doseDescription = doseDescription, durationDays = durationDays,
        scheduledTimesJson = scheduledTimesJson,
    )

    private fun DoseInstanceEntity.toBackup() = DoseInstanceBackup(
        id = id, medicationId = medicationId, scheduleId = scheduleId,
        dueEpochMs = dueEpochMs, windowEndEpochMs = windowEndEpochMs, state = state,
        takenEpochMs = takenEpochMs, skippedEpochMs = skippedEpochMs,
        missedEpochMs = missedEpochMs, snoozeUntilEpochMs = snoozeUntilEpochMs,
        createdAtEpochMs = createdAtEpochMs,
    )

    private fun DoseInstanceBackup.toEntity() = DoseInstanceEntity(
        id = id, medicationId = medicationId, scheduleId = scheduleId,
        dueEpochMs = dueEpochMs, windowEndEpochMs = windowEndEpochMs, state = state,
        takenEpochMs = takenEpochMs, skippedEpochMs = skippedEpochMs,
        missedEpochMs = missedEpochMs, snoozeUntilEpochMs = snoozeUntilEpochMs,
        createdAtEpochMs = createdAtEpochMs,
    )

    private fun DoseTransitionEntity.toBackup() = DoseTransitionBackup(
        id = id, doseInstanceId = doseInstanceId, medicationId = medicationId,
        fromState = fromState, toState = toState, cause = cause, atEpochMs = atEpochMs,
    )

    private fun DoseTransitionBackup.toEntity() = DoseTransitionEntity(
        id = id, doseInstanceId = doseInstanceId, medicationId = medicationId,
        fromState = fromState, toState = toState, cause = cause, atEpochMs = atEpochMs,
    )

    private fun RefillRecordEntity.toBackup() = RefillRecordBackup(
        id = id, medicationId = medicationId, quantityOnHand = quantityOnHand,
        quantityUnit = quantityUnit, refillByEpochMs = refillByEpochMs,
        pharmacyPhone = pharmacyPhone, notes = notes, type = type,
        createdAtEpochMs = createdAtEpochMs,
    )

    private fun RefillRecordBackup.toEntity() = RefillRecordEntity(
        id = id, medicationId = medicationId, quantityOnHand = quantityOnHand,
        quantityUnit = quantityUnit, refillByEpochMs = refillByEpochMs,
        pharmacyPhone = pharmacyPhone, notes = notes, type = type,
        createdAtEpochMs = createdAtEpochMs,
    )

    private fun SettingEntity.toBackup() = SettingBackup(
        key = key, value = value, updatedAtEpochMs = updatedAtEpochMs,
    )

    private fun SettingBackup.toEntity() = SettingEntity(
        key = key, value = value, updatedAtEpochMs = updatedAtEpochMs,
    )

    private fun ScheduleEntity.toDisplayString(): String = when (
        runCatching { ScheduleType.valueOf(type) }.getOrElse { ScheduleType.FIXED_DAILY }
    ) {
        ScheduleType.FIXED_DAILY -> "Daily"
        ScheduleType.DAYS_OF_WEEK -> "Selected days"
        ScheduleType.INTERVAL -> intervalHours?.let { "Every ${it}h" } ?: "Interval"
        ScheduleType.DOSE_WINDOW -> "Time window"
        ScheduleType.PRN -> "As needed"
        ScheduleType.TEMPORARY -> "Course"
        ScheduleType.TAPER -> "Taper"
    }
}

// -- Result types --------------------------------------------------------------

sealed interface BackupResult {
    data object Success : BackupResult
    data class Error(val message: String) : BackupResult
}

sealed interface RestoreResult {
    data class Success(val medicationCount: Int) : RestoreResult
    data class Error(val message: String) : RestoreResult
}

sealed interface ExportResult {
    data object Success : ExportResult
    data class Error(val message: String) : ExportResult
}
