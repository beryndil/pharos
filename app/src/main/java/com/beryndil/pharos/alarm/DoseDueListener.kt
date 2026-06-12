package com.beryndil.pharos.alarm

/**
 * Seam notified by [AlarmCoordinator] when a dose enters DUE (its exact alarm fired). Slice 4
 * ships a [NoOp] default so the alarm engine is self-contained; Slice 5's dose state machine
 * implements it to arm the D2 miss-window deadline and the escalation re-alerts.
 *
 * Keeping this separate from [DoseActionHandler] (user actions) means the coordinator depends only
 * on a tiny lifecycle hook, not on the whole state machine.
 */
interface DoseDueListener {

    /** A dose just transitioned SCHEDULED → DUE at [firedAtEpochMs]. */
    suspend fun onEnteredDue(doseId: String, firedAtEpochMs: Long)

    /** Slice 4 placeholder. Slice 5 supplies the real state machine. */
    object NoOp : DoseDueListener {
        override suspend fun onEnteredDue(doseId: String, firedAtEpochMs: Long) = Unit
    }
}
