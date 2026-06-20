package com.beryndil.pharos.backup

import kotlinx.serialization.Serializable

/**
 * Top-level schema for the encrypted backup JSON payload (spec §2.12, D4).
 *
 * [schemaVersion] is checked on restore: if the restoring app does not understand the version
 * it rejects the backup rather than attempting a partial import.
 *
 * All entity fields mirror the Room entity definitions exactly so that serialisation is
 * a direct mapping without transformation. Timestamps are UTC epoch-milliseconds (Standards §2).
 */
@Serializable
data class BackupPayload(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    /** UTC epoch-ms when this backup was exported (for display, not used in restore logic). */
    val exportedAtEpochMs: Long,
    val medications: List<MedicationBackup>,
    val schedules: List<ScheduleBackup>,
    val schedulePhases: List<SchedulePhaseBackup>,
    val doseInstances: List<DoseInstanceBackup>,
    val doseTransitions: List<DoseTransitionBackup>,
    val refillRecords: List<RefillRecordBackup>,
    val settings: List<SettingBackup>,
    /** Added schema v2 — default empty so schema v1 backups restore without error. */
    val prescribers: List<PrescriberBackup> = emptyList(),
    val pharmacies: List<PharmacyBackup> = emptyList(),
    val supplies: List<SupplyBackup> = emptyList(),
    val supplyRecords: List<SupplyRecordBackup> = emptyList(),
) {
    companion object {
        const val CURRENT_SCHEMA_VERSION = 2
    }
}

// ── Entity backup DTOs ────────────────────────────────────────────────────────
// Each mirrors the corresponding Room entity's fields verbatim.

@Serializable
data class MedicationBackup(
    val id: String,
    val name: String,
    val rxcui: String?,
    val ingredientsJson: String,
    val strength: String,
    val form: String,
    val doseAmount: String,
    val prescriber: String?,
    /**
     * Prescriber phone added in V1.3-F1 (schema v1 — default null for backups
     * produced before this field existed so older backups remain restorable).
     */
    val prescriberPhone: String? = null,
    val pharmacy: String?,
    /**
     * Pharmacy phone added in V1.3-F1 (same back-compat default as [prescriberPhone]).
     */
    val pharmacyPhone: String? = null,
    val purpose: String?,
    /** Free-text reminder note (v1.4+). Default null for backups from older app versions. */
    val notes: String? = null,
    val isFreeText: Boolean,
    /** Default false for schema v1 backups. */
    val isCritical: Boolean = false,
    /** Default 60 for schema v1 backups. */
    val missWindowMinutes: Int = 60,
    val status: String,
    val startEpochMs: Long,
    val endEpochMs: Long?,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
    /** Dead column retained for schema fidelity. */
    val substituteForMedId: String? = null,
    /** Default null for schema v1 backups. */
    val substituteForDrugName: String? = null,
    /** Default null for schema v1 backups. */
    val substituteNote: String? = null,
    /** Default null for schema v1 backups. */
    val prescriberPractice: String? = null,
    /** Combined prescription partner ID (v1.5.3+). Default null for older backups. */
    val combinedWithMedId: String? = null,
    /** User-typed combined display strength, e.g. "90 mg" (v1.5.3+). Default null for older backups. */
    val combinedDisplayStrength: String? = null,
)

@Serializable
data class ScheduleBackup(
    val id: String,
    val medicationId: String,
    val type: String,
    val scheduledTimesJson: String?,
    val daysOfWeekJson: String?,
    val intervalHours: Int?,
    val intervalAnchorType: String?,
    val windowStartTime: String?,
    val windowEndTime: String?,
    val dailyMaxDoses: Int?,
    val zoneId: String,
    val isActive: Boolean,
    val startEpochMs: Long?,
    val endEpochMs: Long?,
    val createdAtEpochMs: Long,
    /** PRN indication (v1.5.4+). Default null for backups from older app versions. */
    val indication: String? = null,
    /** Every-N-weeks repeat (v1.6+). Default 1 (weekly) for schema v1 backups. */
    val weekInterval: Int = 1,
)

@Serializable
data class SchedulePhaseBackup(
    val id: String,
    val scheduleId: String,
    val phaseOrder: Int,
    val doseDescription: String,
    val durationDays: Int,
    val scheduledTimesJson: String,
)

@Serializable
data class DoseInstanceBackup(
    val id: String,
    val medicationId: String,
    val scheduleId: String,
    val dueEpochMs: Long,
    val windowEndEpochMs: Long?,
    val state: String,
    val takenEpochMs: Long?,
    val skippedEpochMs: Long?,
    val missedEpochMs: Long?,
    val snoozeUntilEpochMs: Long?,
    val createdAtEpochMs: Long,
)

@Serializable
data class DoseTransitionBackup(
    val id: String,
    val doseInstanceId: String,
    val medicationId: String,
    val fromState: String?,
    val toState: String,
    val cause: String,
    val atEpochMs: Long,
)

@Serializable
data class RefillRecordBackup(
    val id: String,
    val medicationId: String,
    val quantityOnHand: Int,
    val quantityUnit: String,
    val refillByEpochMs: Long?,
    val pharmacyPhone: String?,
    val notes: String?,
    val type: String,
    val createdAtEpochMs: Long,
)

@Serializable
data class SettingBackup(
    val key: String,
    val value: String,
    val updatedAtEpochMs: Long,
)

@Serializable
data class PrescriberBackup(
    val id: String,
    val name: String,
    val phone: String?,
    val practice: String? = null,
    val createdAtEpochMs: Long,
)

@Serializable
data class PharmacyBackup(
    val id: String,
    val name: String,
    val phone: String?,
    val createdAtEpochMs: Long,
)

@Serializable
data class SupplyBackup(
    val id: String,
    val name: String,
    val unit: String,
    val prescriberName: String?,
    val prescriberPhone: String?,
    val pharmacyName: String?,
    val pharmacyPhone: String?,
    val lowThreshold: Int,
    val notes: String?,
    val status: String,
    val createdAtEpochMs: Long,
)

@Serializable
data class SupplyRecordBackup(
    val id: String,
    val supplyId: String,
    val quantityDelta: Int,
    val quantityAfter: Int,
    val eventType: String,
    val notes: String?,
    val createdAtEpochMs: Long,
)
