package com.beryndil.pharos.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.beryndil.pharos.BuildConfig
import com.beryndil.pharos.PharosApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Receives the Take / Snooze / Skip actions tapped on the dose-due notification and drives the
 * real state transition through the dose state machine (spec §2.6, §2.8). Declared
 * `exported="false"` — only our own notification-action [android.app.PendingIntent]s target it.
 *
 * DB work runs off the main thread under `goAsync()` (Standards §3). An illegal transition (e.g. a
 * double tap after the dose was already taken) is caught and ignored — the user-facing action is
 * idempotent, not a crash (Standards §1: log without PHI, recover).
 */
class DoseActionReceiver : BroadcastReceiver() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val doseId = intent.getStringExtra(AlarmContract.EXTRA_DOSE_ID) ?: return
        val app = context.applicationContext as PharosApplication
        val stateMachine = app.appContainer.doseStateMachine
        val nowMs = System.currentTimeMillis()

        val pending = goAsync()
        scope.launch {
            try {
                when (action) {
                    AlarmContract.ACTION_USER_TAKEN -> stateMachine.onTaken(doseId, nowMs)
                    AlarmContract.ACTION_USER_SNOOZE -> stateMachine.onSnooze(doseId, nowMs)
                    AlarmContract.ACTION_USER_SKIP -> stateMachine.onSkip(doseId, nowMs)
                }
            } catch (t: Throwable) {
                if (BuildConfig.DEBUG) Log.w(TAG, "dose action $action ignored", t)
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val TAG = "DoseActionReceiver"
    }
}
