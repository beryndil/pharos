package com.beryndil.pharos.data.regimen.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * An append-only event log for a [SupplyEntity].
 *
 * Each row records a signed [quantityDelta] (positive = gain, negative = usage) and
 * a [quantityAfter] snapshot so current on-hand count is always O(1) via [SupplyDao.getLatest].
 * Never UPDATE or DELETE a past record.
 */
@Entity(
    tableName = "supply_records",
    foreignKeys = [
        ForeignKey(
            entity = SupplyEntity::class,
            parentColumns = ["id"],
            childColumns = ["supplyId"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [Index("supplyId")],
)
data class SupplyRecordEntity(
    @PrimaryKey val id: String,

    val supplyId: String,

    /** Positive for restocks/initial counts; negative for usage. */
    val quantityDelta: Int,

    /** Running total on-hand after this event. */
    val quantityAfter: Int,

    /** Stored as [SupplyEventType.name]. */
    val eventType: String,

    val notes: String?,

    val createdAtEpochMs: Long,
)

enum class SupplyEventType {
    INITIAL,
    RESTOCK,
    USAGE,
    ADJUSTMENT,
}
