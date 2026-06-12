package com.beryndil.pharos.data.regimen.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Represents a medication the user is tracking.
 *
 * This row MAY be updated (e.g., adding a prescriber after the fact). It is NEVER deleted —
 * ending a medication sets [status] = [MedicationStatus.ENDED]. The append-only invariant
 * lives in the associated [DoseInstanceEntity] and [ScheduleEntity] tables.
 *
 * Timestamps are stored as UTC epoch-milliseconds (Standards §2: store instants, not strings).
 */
@Entity(tableName = "medications")
data class MedicationEntity(
    /** UUID assigned at creation. Never reused. */
    @PrimaryKey val id: String,

    /** Display name as confirmed by the user (or free-text if [isFreeText] = true). */
    val name: String,

    /** RxNorm RxCUI for the resolved product, or null for free-text fallback meds. */
    val rxcui: String?,

    /**
     * JSON array of active ingredient RxCUIs for duplicate-detection.
     * Format: `["161","5640"]` (RxCUI strings).
     * Empty array for free-text meds that could not be resolved.
     */
    val ingredientsJson: String,

    /** Dose strength as a display string, e.g., "25 mg". Required (spec §2.3). */
    val strength: String,

    /** Medication form. Stored as [MedicationForm].name for schema legibility. */
    val form: String,

    /** Dose amount per administration, e.g., "1 tablet", "5 mL". */
    val doseAmount: String,

    /** Optional prescriber name (no PHI rules — user's own words). */
    val prescriber: String?,

    /** Optional pharmacy name or phone hint. */
    val pharmacy: String?,

    /** Optional purpose note in the user's own words. */
    val purpose: String?,

    /**
     * True if this medication was entered as free-text without RxNorm resolution.
     * Free-text meds get reminders and refill tracking but no duplicate/interaction/label
     * reference (spec §2.11). The app displays a plain notice when [isFreeText] is true.
     */
    val isFreeText: Boolean,

    /** ACTIVE, PAUSED, or ENDED. Stored as [MedicationStatus].name. */
    val status: String,

    /** Schedule start date as UTC epoch-ms. */
    val startEpochMs: Long,

    /** Schedule end date as UTC epoch-ms, or null for ongoing. */
    val endEpochMs: Long?,

    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
)

/** Lifecycle status of a medication in the user's regimen. */
enum class MedicationStatus { ACTIVE, PAUSED, ENDED }

/** Physical form factor of a medication dose. Must include "other" per spec §2.3. */
enum class MedicationForm {
    TABLET, CAPSULE, LIQUID, INJECTION, INHALER, PATCH, DROPS, CREAM, OTHER
}
