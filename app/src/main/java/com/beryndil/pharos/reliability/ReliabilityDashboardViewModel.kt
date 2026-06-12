package com.beryndil.pharos.reliability

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.os.PowerManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.beryndil.pharos.alarm.AlarmMode
import com.beryndil.pharos.alarm.SettingsReliabilityLog
import com.beryndil.pharos.data.regimen.dao.SettingDao
import com.beryndil.pharos.data.regimen.entity.SettingEntity
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.util.Locale

/**
 * Drives the reliability dashboard screen (spec §2.13, Law 6).
 *
 * Architecture notes:
 *  - Permission checks ([canScheduleExact], [isIgnoringBatteryOpt], [isNotificationGranted],
 *    [canUseFullScreenIntent]) are injected as lambdas. This keeps the ViewModel fully testable
 *    with plain JUnit: tests supply `{ true }` / `{ false }` without any Android context.
 *  - Checks are snapshotted once at ViewModel construction. Because Compose Navigation recreates
 *    the ViewModel each time the user navigates TO this screen, the snapshot is always fresh on
 *    arrival (DECISIONS.md S6-A2).
 *  - Settings data (last-alarm, next-alarm, alarm-mode, boot-receiver, drug-db) is a live
 *    [kotlinx.coroutines.flow.Flow] from [SettingDao.observeAll] so it updates when the alarm
 *    engine writes new facts (e.g., after a dose fires while the user is viewing the dashboard).
 *  - No third-party SDK, no off-device reporting (Laws 4 & 5).
 *
 * Drug-DB version keys ([KEY_DRUGREF_VERSION], [KEY_DRUGREF_UPDATED_AT]) are written by the
 * Slice 8 CDN pipeline. Until then they are absent and the dashboard shows the bundled defaults.
 */
class ReliabilityDashboardViewModel(
    settingDao: SettingDao,
    private val canScheduleExact: () -> Boolean,
    private val isIgnoringBatteryOpt: () -> Boolean,
    private val isNotificationGranted: () -> Boolean,
    private val canUseFullScreenIntent: () -> Boolean,
    private val oemName: String = Build.MANUFACTURER,
) : ViewModel() {

    // Snapshotted once at construction (see kdoc). The VM is recreated on each navigation.
    private val exactOk: Boolean = canScheduleExact()
    private val batteryOk: Boolean = isIgnoringBatteryOpt()
    private val notifOk: Boolean = isNotificationGranted()
    private val fullscreenOk: Boolean = canUseFullScreenIntent()

    val uiState: StateFlow<ReliabilityDashboardUiState> = settingDao.observeAll()
        .map { settings -> buildState(settings.associateBy { it.key }) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000L),
            initialValue = buildState(emptyMap()),
        )

    private fun buildState(map: Map<String, SettingEntity>): ReliabilityDashboardUiState {
        val isKillerOem = isKillerOem(oemName)
        return ReliabilityDashboardUiState(
            exactAlarm = if (exactOk) {
                DashboardPermissionItem(ItemStatus.OK)
            } else {
                DashboardPermissionItem(
                    status = ItemStatus.RISKY,
                    fixAction = FixAction.ExactAlarmSettings,
                )
            },
            batteryOptimization = if (batteryOk) {
                DashboardPermissionItem(ItemStatus.OK)
            } else {
                DashboardPermissionItem(
                    status = ItemStatus.RISKY,
                    fixAction = FixAction.BatterySettings,
                )
            },
            backgroundAutoStart = if (isKillerOem) {
                DashboardPermissionItem(
                    status = ItemStatus.RISKY,
                    fixAction = FixAction.OpenUrl(DKMA_URL),
                )
            } else {
                DashboardPermissionItem(ItemStatus.OK)
            },
            notification = if (notifOk) {
                DashboardPermissionItem(ItemStatus.OK)
            } else {
                DashboardPermissionItem(
                    status = ItemStatus.RISKY,
                    fixAction = FixAction.NotificationSettings,
                )
            },
            fullScreenIntent = if (fullscreenOk) {
                DashboardPermissionItem(ItemStatus.OK)
            } else {
                DashboardPermissionItem(
                    status = ItemStatus.RISKY,
                    fixAction = FixAction.FullScreenIntentSettings,
                )
            },
            lastAlarmFiredEpochMs = map[SettingsReliabilityLog.KEY_LAST_FIRED_AT]
                ?.value?.toLongOrNull()?.takeIf { it > 0L },
            nextAlarmEpochMs = map[SettingsReliabilityLog.KEY_NEXT_ALARM_AT]
                ?.value?.toLongOrNull()?.takeIf { it > 0L },
            alarmMode = map[SettingsReliabilityLog.KEY_ALARM_MODE]
                ?.value?.let { runCatching { AlarmMode.valueOf(it) }.getOrNull() },
            bootReceiverLastTrigger = map[SettingsReliabilityLog.KEY_LAST_REREGISTRATION]
                ?.value?.takeIf { it.isNotEmpty() },
            bootReceiverLastTriggerEpochMs = map[SettingsReliabilityLog.KEY_LAST_REREGISTRATION_AT]
                ?.value?.toLongOrNull(),
            drugDbVersion = map[KEY_DRUGREF_VERSION]?.value,
            drugDbUpdatedAtEpochMs = map[KEY_DRUGREF_UPDATED_AT]?.value?.toLongOrNull(),
            oemName = oemName,
        )
    }

    companion object {
        /** Setting key written by the Slice 8 CDN pipeline (drug-DB version string). */
        const val KEY_DRUGREF_VERSION = "drugref.version"

        /** Setting key written by the Slice 8 CDN pipeline (epoch-ms of last successful swap). */
        const val KEY_DRUGREF_UPDATED_AT = "drugref.updated_at"

        private const val DKMA_URL = "https://dontkillmyapp.com"

        /**
         * Returns true for the four OEM brands with known aggressive background-process
         * restrictions (D5). Must match [OnboardingViewModel.isKillerOem] — kept in sync via
         * the shared [KILLER_OEM_SET] constant.
         */
        internal fun isKillerOem(name: String): Boolean =
            name.lowercase(Locale.ROOT) in KILLER_OEM_SET

        internal val KILLER_OEM_SET = setOf("xiaomi", "oppo", "vivo", "honor")

        /**
         * Production factory. Captures [applicationContext] (not Activity context — no leak) to
         * check permission state via system services. All lambda captures are application-scoped.
         */
        fun factory(
            settingDao: SettingDao,
            applicationContext: Context,
        ) = viewModelFactory {
            initializer {
                val am = applicationContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                val pm = applicationContext.getSystemService(Context.POWER_SERVICE) as PowerManager
                val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE)
                    as NotificationManager

                ReliabilityDashboardViewModel(
                    settingDao = settingDao,
                    canScheduleExact = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            am.canScheduleExactAlarms()
                        } else {
                            true
                        }
                    },
                    isIgnoringBatteryOpt = {
                        pm.isIgnoringBatteryOptimizations(applicationContext.packageName)
                    },
                    isNotificationGranted = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            ContextCompat.checkSelfPermission(
                                applicationContext,
                                Manifest.permission.POST_NOTIFICATIONS,
                            ) == PackageManager.PERMISSION_GRANTED
                        } else {
                            true
                        }
                    },
                    canUseFullScreenIntent = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                            nm.canUseFullScreenIntent()
                        } else {
                            true
                        }
                    },
                )
            }
        }
    }
}
