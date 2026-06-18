package com.beryndil.pharos.data.regimen.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A non-drug medical supply item (needles, pods, sensors, etc.).
 *
 * Intentionally separate from [MedicationEntity] — supplies have no dose schedule,
 * dose history, or alarm engine involvement. The zero-supply invariant holds: this
 * entity never influences dose reminders.
 *
 * Prescriber and pharmacy data are stored as plain strings (same pattern as
 * [MedicationEntity]) — association by value, not by FK, so deleting a contact
 * does not corrupt supply records.
 */
@Entity(
    tableName = "supplies",
    indices = [Index("status")],
)
data class SupplyEntity(
    @PrimaryKey val id: String,

    val name: String,

    /** Unit label for display, e.g. "sensors", "pods", "needles". */
    val unit: String,

    val prescriberName: String?,
    val prescriberPhone: String?,
    val pharmacyName: String?,
    val pharmacyPhone: String?,

    /**
     * Warn when [SupplyRecordEntity.quantityAfter] drops to or below this value.
     * 0 means no threshold set (no low-supply warning).
     */
    val lowThreshold: Int = 0,

    val notes: String?,

    /** Stored as [SupplyStatus.name]. Only ACTIVE / ENDED. */
    val status: String = SupplyStatus.ACTIVE.name,

    val createdAtEpochMs: Long,
)

enum class SupplyStatus { ACTIVE, ENDED }
