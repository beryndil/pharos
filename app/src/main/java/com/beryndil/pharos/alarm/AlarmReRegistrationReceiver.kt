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
 * Rebuilds the pending dose alarm after any event that can invalidate it (spec §3.4, Standards
 * §2, §3): device boot, app update/reinstall, manual time change, timezone change, the daily
 * date rollover, and exact-alarm permission changes.
 *
 * All of these are protected system broadcasts (only the OS can send them), so `exported="true"`
 * is safe — a malicious app cannot forge them.
 *
 * Note on locked-boot: the regimen DB is credential-encrypted (PHI), not device-encrypted, so it
 * is unreadable before the user unlocks. Re-registration therefore happens at BOOT_COMPLETED
 * (post-unlock), not LOCKED_BOOT_COMPLETED — see DECISIONS.md S4-A2.
 */
class AlarmReRegistrationReceiver : BroadcastReceiver() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val app = context.applicationContext as PharosApplication
        val coordinator = app.appContainer.alarmCoordinator
        app.appContainer.doseNotifier.ensureChannels()

        val pending = goAsync()
        scope.launch {
            try {
                coordinator.onReRegistration(action)
            } catch (t: Throwable) {
                // action is a constant string — no PHI.
                Log.e(TAG, "re-registration failed for $action", t)
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val TAG = "AlarmReReg"
    }
}
