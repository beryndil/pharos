package com.beryndil.pharos.dose

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.beryndil.pharos.alarm.AlarmContract
import com.beryndil.pharos.alarm.AlarmReceiver

/**
 * Schedules the dose state machine's two per-dose timed transitions so they fire even when the app
 * is closed (spec §2.6 / §2.8, Standards §3):
 *
 *  - **Miss check** — the hard D2 deadline. At the miss-window-close instant the dose becomes
 *    MISSED if still DUE/SNOOZED.
 *  - **Re-alert** — the escalating re-alert (DUE) or snooze-wake (SNOOZED) tick. Returns a snoozed
 *    dose to DUE and raises alert intensity until the dose is acted on or the window closes.
 *
 * This is additive to the Slice 4 alarm engine — it does not touch the single-fire-and-reschedule
 * dose alarm. Per-dose [PendingIntent] request codes are derived from the dose id so two doses
 * that are DUE at once (e.g. two meds at 08:00) get independent alarms (Law 3 independence).
 */
interface DoseTransitionScheduler {

    /** Schedule the hard miss-window deadline for [doseId] at [triggerAtEpochMs]. */
    fun scheduleMissCheck(doseId: String, triggerAtEpochMs: Long)

    /** Schedule the next re-alert/snooze-wake for [doseId] at [triggerAtEpochMs]. */
    fun scheduleReAlert(doseId: String, triggerAtEpochMs: Long)

    /** Cancel both timed transitions for [doseId] (the dose reached a terminal/handled state). */
    fun cancelTimers(doseId: String)
}

/**
 * [DoseTransitionScheduler] over the platform [AlarmManager]. Mirrors the Slice 4 delivery policy:
 * exact when permitted (`setExactAndAllowWhileIdle` — these are maintenance ticks, not the
 * status-bar alarm-clock), windowed fallback otherwise — never drop a safety-critical transition.
 *
 * All `PendingIntent`s use `FLAG_IMMUTABLE or FLAG_UPDATE_CURRENT` (Standards §3).
 */
class AndroidDoseTransitionScheduler(private val context: Context) : DoseTransitionScheduler {

    private val alarmManager: AlarmManager =
        context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    private val fallbackWindowMs = 5L * 60L * 1000L

    override fun scheduleMissCheck(doseId: String, triggerAtEpochMs: Long) {
        schedule(AlarmContract.ACTION_DOSE_MISS_CHECK, doseId, triggerAtEpochMs)
    }

    override fun scheduleReAlert(doseId: String, triggerAtEpochMs: Long) {
        schedule(AlarmContract.ACTION_DOSE_REALERT, doseId, triggerAtEpochMs)
    }

    override fun cancelTimers(doseId: String) {
        cancel(AlarmContract.ACTION_DOSE_MISS_CHECK, doseId)
        cancel(AlarmContract.ACTION_DOSE_REALERT, doseId)
    }

    private fun schedule(action: String, doseId: String, triggerAtEpochMs: Long) {
        val operation = operationIntent(action, doseId)
        if (canScheduleExact()) {
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                triggerAtEpochMs,
                operation,
            )
        } else {
            alarmManager.setWindow(
                AlarmManager.RTC_WAKEUP,
                triggerAtEpochMs,
                fallbackWindowMs,
                operation,
            )
        }
    }

    private fun cancel(action: String, doseId: String) {
        val pi = PendingIntent.getBroadcast(
            context,
            requestCode(action, doseId),
            broadcastIntent(action, doseId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        alarmManager.cancel(pi)
        pi.cancel()
    }

    private fun canScheduleExact(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            alarmManager.canScheduleExactAlarms()
        } else {
            true
        }

    private fun operationIntent(action: String, doseId: String): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            requestCode(action, doseId),
            broadcastIntent(action, doseId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private fun broadcastIntent(action: String, doseId: String): Intent =
        Intent(context, AlarmReceiver::class.java)
            .setAction(action)
            .putExtra(AlarmContract.EXTRA_DOSE_ID, doseId)

    /**
     * Stable per-(dose, purpose) request code. The action differentiates miss-check from re-alert
     * (PendingIntent identity includes the action), so the dose hash alone keys the slot. Masked
     * non-negative and offset clear of the fixed 4001–4005 Slice 4 slots.
     */
    private fun requestCode(action: String, doseId: String): Int {
        val base = (doseId.hashCode() and 0x3FFFFFFF) or 0x40000000
        return if (action == AlarmContract.ACTION_DOSE_MISS_CHECK) base else base xor 0x20000000
    }
}
