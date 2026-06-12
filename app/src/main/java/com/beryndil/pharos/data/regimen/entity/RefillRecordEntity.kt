package com.beryndil.pharos.data.regimen.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A refill event for a medication (spec §2.9).
 *
 * Rows are INSERT-only. The "current quantity on hand" is the [quantityOnHand] of the
 * most-recent row for a given [medicationId]. Never UPDATE or DELETE a past record.
 */
@Entity(
    tableName = "refill_records",
    foreignKeys = [
        ForeignKey(
            entity = MedicationEntity::class,
            parentColumns = ["id"],
            childColumns = ["medicationId"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [Index("medicationId")],
)
data class RefillRecordEntity(
    @PrimaryKey val id: String,

    val medicationId: String,

    /** Quantity on hand after this event, e.g., 30. */
    val quantityOnHand: Int,

    /** Unit of quantity, e.g., "tablets", "mL". */
    val quantityUnit: String,

    /** Suggested refill-by date as UTC epoch-ms, or null if not set. */
    val refillByEpochMs: Long?,

    /** Optional pharmacy phone number. */
    val pharmacyPhone: String?,

    /** Optional free-text notes for this refill event. */
    val notes: String?,

    /** Type of refill event. Stored as [RefillEventType].name. */
    val type: String,

    val createdAtEpochMs: Long,
)

/** Categorises the reason for a refill record entry. */
enum class RefillEventType {
    /** First count entry when medication is added. */
    INITIAL,
    /** User picked up a refill (count reset or added to). */
    REFILL_PICKUP,
    /** Manual count correction (partial fill, waste, etc.). */
    ADJUSTMENT,
}
