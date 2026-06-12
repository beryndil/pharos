package com.beryndil.pharos.refill

/**
 * Posts low-supply and zero-supply notifications on the REFILL channel (spec §2.8, §2.9).
 *
 * Law 1 invariant: implementations MUST post exclusively on [com.beryndil.pharos.alarm.AlarmContract.CHANNEL_REFILL],
 * never on [com.beryndil.pharos.alarm.AlarmContract.CHANNEL_DOSE_DUE].
 *
 * The user can silence the refill channel independently without affecting dose reminders.
 */
interface RefillNotifier {

    /** Create the refill notification channel if it doesn't exist yet. */
    fun ensureRefillChannel()

    /**
     * Post a low-supply alert for a medication.
     *
     * @param medicationId Stable medication id (used to generate a stable notification id).
     * @param medName Display name for the notification content.
     * @param daysLeft Computed days of supply remaining (may be 0).
     */
    fun postLowSupplyAlert(medicationId: String, medName: String, daysLeft: Int)

    /** Dismiss any active low-supply alert for this medication. */
    fun cancelLowSupplyAlert(medicationId: String)
}
