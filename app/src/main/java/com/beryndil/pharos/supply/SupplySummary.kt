package com.beryndil.pharos.supply

import androidx.compose.runtime.Immutable

/**
 * Derived read model for a single supply item.
 *
 * [quantityOnHand] is null only when no [SupplyRecordEntity] has been written yet (item was
 * just added without an initial count). [isLowSupply] is always false when threshold is 0.
 */
@Immutable
data class SupplySummary(
    val supplyId: String,
    val supplyName: String,
    val unit: String,
    val quantityOnHand: Int?,
    val lowThreshold: Int,
    val noRecordYet: Boolean,
    val isLowSupply: Boolean,
    val prescriberName: String?,
    val prescriberPhone: String?,
    val pharmacyName: String?,
    val pharmacyPhone: String?,
    val notes: String?,
    val status: String,
)
