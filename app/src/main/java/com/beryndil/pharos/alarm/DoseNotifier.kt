package com.beryndil.pharos.alarm

/**
 * Posts the dose-due alert when an alarm fires (spec §2.8, §3.4). This slice builds the
 * full-screen-intent plumbing and the channel; the SACRED-channel rules and the
 * DUE/SNOOZE/TAKEN actions are Slice 5 (the dose state machine) — see [DoseActionHandler]
 * for the seam they plug into.
 */
interface DoseNotifier {

    /** Create notification channels. Idempotent; safe to call on every app start. */
    fun ensureChannels()

    /**
     * Post the full-screen dose-due alert for [doseId] (the [DueAlertActivity] takes over the
     * screen). [medName] is the display name; [dueEpochMs] the scheduled instant.
     */
    fun postDoseDueAlert(doseId: String, medName: String, dueEpochMs: Long)

    /** Post the "test reminder" alert (Law 6 — every alarm is testable). */
    fun postTestReminder()

    /** True when full-screen intents may be used (Android 14 gates this at runtime). */
    fun canUseFullScreen(): Boolean
}

/**
 * Seam for the Slice 5 dose state machine. The [DueAlertActivity] and dose-action notification
 * buttons call into this; Slice 4 ships a no-op default so the alert is functional (it shows and
 * can be dismissed) without prejudging the state-transition rules (D2/D3, escalation, sacred
 * channel) that Slice 5 owns.
 */
interface DoseActionHandler {
    suspend fun onTaken(doseId: String, atEpochMs: Long)
    suspend fun onSnooze(doseId: String, atEpochMs: Long)
    suspend fun onSkip(doseId: String, atEpochMs: Long)

    /** Slice 4 placeholder: records nothing. Slice 5 replaces with the real state machine. */
    object NoOp : DoseActionHandler {
        override suspend fun onTaken(doseId: String, atEpochMs: Long) = Unit
        override suspend fun onSnooze(doseId: String, atEpochMs: Long) = Unit
        override suspend fun onSkip(doseId: String, atEpochMs: Long) = Unit
    }
}
