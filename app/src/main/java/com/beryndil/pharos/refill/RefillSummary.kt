package com.beryndil.pharos.refill

/**
 * Domain projection of a medication's current refill state (spec §2.9).
 *
 * Derived by [RefillRepository] from the latest [com.beryndil.pharos.data.regimen.entity.RefillRecordEntity]
 * and the medication's active schedule.
 *
 * Zero-supply invariant (spec §2.9, Law 1): when [quantityOnHand] == 0 or [noSupplyOnRecord] is true,
 * this summary describes the refill state ONLY. The dose state machine and alarm engine are
 * architecturally isolated from this class — a zero supply count can NEVER suppress a dose reminder.
 */
data class RefillSummary(
    val medicationId: String,
    val medicationName: String,

    /**
     * Current quantity on hand, derived from the latest refill record.
     * Null if no refill record has been created yet.
     */
    val quantityOnHand: Int?,

    /** Unit of quantity (e.g., "tablets", "mL"). Null if no record exists. */
    val quantityUnit: String?,

    /**
     * Doses per day derived from the active schedule.
     * Null for PRN medications (depletes unpredictably — spec §2.9) and when no active
     * schedule is found.
     */
    val dosesPerDay: Double?,

    /**
     * Floor(quantityOnHand / dosesPerDay). Null when either input is null or
     * dosesPerDay <= 0. Zero when supply is on record but count is 0.
     */
    val daysUntilEmpty: Int?,

    /**
     * True when no refill record has ever been created for this medication.
     * Distinct from [quantityOnHand] == 0 (which means the user recorded an empty supply).
     */
    val noSupplyOnRecord: Boolean,

    /**
     * True when [quantityOnHand] is not null and [quantityOnHand] == 0.
     * Displayed as a separate flag ("No supply on record") in the UI per spec §2.9.
     */
    val supplyIsZero: Boolean,

    /** Suggested or user-confirmed refill-by date as UTC epoch-ms, or null if not set. */
    val refillByEpochMs: Long?,

    /** Optional pharmacy phone from the latest refill record (or medication entity). */
    val pharmacyPhone: String?,

    /** True when this medication's schedule type is PRN. */
    val isPrn: Boolean,

    /**
     * True when [daysUntilEmpty] is not null and [daysUntilEmpty] < [LOW_SUPPLY_THRESHOLD_DAYS].
     * Used to drive the low-supply UI banner and the WorkManager low-supply alert.
     */
    val isLowSupply: Boolean,
) {

    companion object {
        /** Below this many days of supply, a low-supply alert is triggered (spec §2.9). */
        const val LOW_SUPPLY_THRESHOLD_DAYS = 7
    }
}
