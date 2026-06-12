package com.beryndil.pharos.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.beryndil.pharos.MainActivity

/**
 * [AlarmScheduler] backed by the platform [AlarmManager] (spec §3.4, Standards §3).
 *
 * Delivery-mode policy:
 *  - [AlarmKind.DOSE] / [AlarmKind.TEST]: `setAlarmClock()` when exact alarms are available.
 *    It carries `FLAG_WAKE_FROM_IDLE`, is exempt from Doze coalescing, and shows in the status
 *    bar (Standards §3). When exact is unavailable, `setWindow()` — degrade timing, never drop.
 *  - [AlarmKind.DAILY_ROLLOVER]: `setExactAndAllowWhileIdle()` (maintenance re-anchor; no need
 *    for the status-bar alarm-clock affordance). Falls back to `setWindow()` likewise.
 *
 * All `PendingIntent`s use `FLAG_IMMUTABLE or FLAG_UPDATE_CURRENT` and a stable request code per
 * [AlarmKind] so a re-schedule replaces the prior alarm (single-fire-and-reschedule).
 */
class AndroidAlarmScheduler(private val context: Context) : AlarmScheduler {

    private val alarmManager: AlarmManager =
        context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    /** Windowed-fallback window length: 10 minutes (degraded timing, still reliable). */
    private val fallbackWindowMs = 10L * 60L * 1000L

    override fun canScheduleExact(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            alarmManager.canScheduleExactAlarms()
        } else {
            true
        }

    override fun schedule(request: AlarmRequest): AlarmMode {
        val operation = operationIntent(request)
        val exact = canScheduleExact()

        return when (request.kind) {
            AlarmKind.DOSE, AlarmKind.TEST -> {
                if (exact) {
                    val info = AlarmManager.AlarmClockInfo(request.triggerAtEpochMs, showIntent())
                    alarmManager.setAlarmClock(info, operation)
                    AlarmMode.EXACT
                } else {
                    alarmManager.setWindow(
                        AlarmManager.RTC_WAKEUP,
                        request.triggerAtEpochMs,
                        fallbackWindowMs,
                        operation,
                    )
                    AlarmMode.WINDOWED_FALLBACK
                }
            }

            AlarmKind.DAILY_ROLLOVER -> {
                if (exact) {
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        request.triggerAtEpochMs,
                        operation,
                    )
                    AlarmMode.EXACT
                } else {
                    alarmManager.setWindow(
                        AlarmManager.RTC_WAKEUP,
                        request.triggerAtEpochMs,
                        fallbackWindowMs,
                        operation,
                    )
                    AlarmMode.WINDOWED_FALLBACK
                }
            }
        }
    }

    override fun cancel(kind: AlarmKind) {
        val pi = PendingIntent.getBroadcast(
            context,
            requestCode(kind),
            broadcastIntent(actionFor(kind)),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        alarmManager.cancel(pi)
        pi.cancel()
    }

    // ── internals ─────────────────────────────────────────────────────────────────

    private fun operationIntent(request: AlarmRequest): PendingIntent {
        val intent = broadcastIntent(actionFor(request.kind)).apply {
            request.doseId?.let { putExtra(AlarmContract.EXTRA_DOSE_ID, it) }
            request.medName?.let { putExtra(AlarmContract.EXTRA_MED_NAME, it) }
            request.dueEpochMs?.let { putExtra(AlarmContract.EXTRA_DUE_EPOCH_MS, it) }
        }
        return PendingIntent.getBroadcast(
            context,
            requestCode(request.kind),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun broadcastIntent(action: String): Intent =
        Intent(context, AlarmReceiver::class.java).setAction(action)

    /** PendingIntent launched when the user taps the status-bar alarm-clock affordance. */
    private fun showIntent(): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(
            context,
            AlarmContract.REQUEST_SHOW_INTENT,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun actionFor(kind: AlarmKind): String = when (kind) {
        AlarmKind.DOSE -> AlarmContract.ACTION_DOSE_DUE
        AlarmKind.TEST -> AlarmContract.ACTION_TEST_REMINDER
        AlarmKind.DAILY_ROLLOVER -> AlarmContract.ACTION_DAILY_ROLLOVER
    }

    private fun requestCode(kind: AlarmKind): Int = when (kind) {
        AlarmKind.DOSE -> AlarmContract.REQUEST_DOSE
        AlarmKind.TEST -> AlarmContract.REQUEST_TEST
        AlarmKind.DAILY_ROLLOVER -> AlarmContract.REQUEST_DAILY_ROLLOVER
    }
}
