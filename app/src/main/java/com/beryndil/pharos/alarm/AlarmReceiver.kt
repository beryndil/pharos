package com.beryndil.pharos.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.beryndil.pharos.PharosApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Receives our own fired alarms (dose-due, test reminder, daily rollover) and hands them to the
 * [AlarmCoordinator] (spec §3.4, Standards §3). Declared `exported="false"` — only our own
 * [android.app.PendingIntent]s, delivered by AlarmManager, target it.
 *
 * DB work runs off the main thread under `goAsync()` (Standards §3: a receiver that touches the
 * DB inside `onReceive` must dispatch off the main thread and respect the ~10s window).
 */
class AlarmReceiver : BroadcastReceiver() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val app = context.applicationContext as PharosApplication
        val coordinator = app.appContainer.alarmCoordinator
        val doseStateMachine = app.appContainer.doseStateMachine
        val doseId = intent.getStringExtra(AlarmContract.EXTRA_DOSE_ID)
        val pending = goAsync()
        scope.launch {
            try {
                when (action) {
                    AlarmContract.ACTION_DOSE_DUE -> {
                        if (doseId != null) coordinator.onDoseAlarmFired(doseId)
                    }

                    AlarmContract.ACTION_TEST_REMINDER -> coordinator.fireTestReminder()

                    AlarmContract.ACTION_DAILY_ROLLOVER -> coordinator.onDailyRollover()

                    // Slice 5 dose state machine timed transitions (D2/D3, escalation).
                    AlarmContract.ACTION_DOSE_REALERT -> {
                        if (doseId != null) doseStateMachine.onReAlert(doseId)
                    }

                    AlarmContract.ACTION_DOSE_MISS_CHECK -> {
                        if (doseId != null) doseStateMachine.onMissCheck(doseId)
                    }
                }
            } catch (t: Throwable) {
                // Never silently swallow (Standards §1): log without PHI, then recover by leaving
                // the engine to re-arm on the next trigger. Re-throwing would crash a system-driven
                // broadcast for no user benefit. action is a constant string — no PHI.
                Log.e(TAG, "alarm handling failed for $action", t)
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val TAG = "AlarmReceiver"
    }
}
