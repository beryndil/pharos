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

    /**
     * Optional prescriber phone number (V1.3-F1). Stored alongside [prescriber] for display
     * and ACTION_DIAL affordances. Kept separate so name and phone can be updated independently.
     */
    val prescriberPhone: String? = null,

    /**
     * Optional prescriber practice or clinic name (V1.3-F1 extension). Stored alongside
     * [prescriber] and [prescriberPhone] for display in the details screen and Saved Contacts.
     */
    val prescriberPractice: String? = null,

    /**
     * Pharmacy name. The original field held "name or phone hint"; going forward this stores the
     * pharmacy NAME only — the dedicated [pharmacyPhone] column carries the phone number. Existing
     * data is back-compat (migration v4\u2192v5 adds the phone columns with DEFAULT NULL).
     */
    val pharmacy: String?,

    /**
     * Optional pharmacy phone number (V1.3-F1). Stored separately from [pharmacy] (the name)
     * so the refill-detail call affordance and autocomplete fill-on-pick can surface it cleanly.
     */
    val pharmacyPhone: String? = null,

    /**
     * ID of another [MedicationEntity] that this medication substitutes for (V1.3-F2).
     * Reference framing only — the app records the link; it never instructs the user to
     * "take B instead of A" (Law 3). Null = no substitution link.
     *
     * Intentionally NOT a Room foreign key: the referenced med may be ended or deleted while
     * the link note is still valuable to the user. Application code must handle null-lookup
     * gracefully (show nothing, never crash).
     */
    /** Dead column — superseded by [substituteForDrugName] in v9. Kept in schema to avoid migration complexity. */
    val substituteForMedId: String? = null,

    /**
     * Name of the drug this medication substitutes for (e.g. "Flomax" for tamsulosin).
     * Free-text, searched against the local drug reference DB at entry time. Reference only
     * (Law 3 — never advice). Null = no substitution link.
     */
    val substituteForDrugName: String? = null,

    /**
     * Optional free-text note the user attached to the substitution link, e.g.
     * "switched from brand to generic — doctor's recommendation 2024-01-15".
     * Pure reference; never advice (Law 3). Null when no note is set.
     */
    val substituteNote: String? = null,

    /** Optional purpose note in the user's own words. */
    val purpose: String?,

    /** Free-text reminder note in the user's own words, e.g., "take with food". */
    val notes: String? = null,

    /**
     * True if this medication was entered as free-text without RxNorm resolution.
     * Free-text meds get reminders and refill tracking but no duplicate/interaction/label
     * reference (spec §2.11). The app displays a plain notice when [isFreeText] is true.
     */
    val isFreeText: Boolean,

    /**
     * True when the user has designated this medication as critical, meaning its dose alerts
     * should break through silent mode and Do Not Disturb (spec §FEATURE_critical_alerts §3.1).
     * Default false — the vast majority of medications are non-critical.
     */
    val isCritical: Boolean = false,

    /**
     * Grace period in minutes before a DUE dose transitions to MISSED (spec §2.6, DECISIONS.md D2).
     * This is the per-medication counterpart to the global 60-minute default — the user can
     * tighten it for a critical med or loosen it for a low-stakes one. Must be in [5, 360].
     * Default 60 (matches [com.beryndil.pharos.dose.MissWindow.GRACE_MS]).
     */
    val missWindowMinutes: Int = 60,

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
    TABLET, CAPLET, CAPSULE, LIQUID, INJECTION, INHALER, PATCH, DROPS, CREAM, OTHER
}
