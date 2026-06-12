package com.beryndil.pharos.reliability

import com.beryndil.pharos.alarm.AlarmMode

/**
 * Whether a reliability item is healthy or needs attention.
 *
 * Every risky item is paired with icon + text in the UI (never color alone — Law 10 / Standards §8).
 */
enum class ItemStatus { OK, RISKY }

/**
 * The action to take when the user taps "Fix" on a risky item. Handled in the composable layer
 * so the ViewModel stays free of [android.content.Intent] / [android.content.Context] references.
 */
sealed class FixAction {
    /** Open the system exact-alarm scheduling permission screen. API 31+ only. */
    object ExactAlarmSettings : FixAction()

    /** Open the system battery optimization exemption screen for this app. */
    object BatterySettings : FixAction()

    /** Open the system per-app notification settings. */
    object NotificationSettings : FixAction()

    /** Open the system full-screen-intent permission screen for this app. API 34+ only. */
    object FullScreenIntentSettings : FixAction()

    /**
     * Open the system Do Not Disturb policy access settings (A1 — Critical Alerts).
     * Required to grant the critical dose channel the ability to bypass DND.
     */
    object DndPolicySettings : FixAction()

    /** Open a URL (e.g. dontkillmyapp.com) in the browser. */
    data class OpenUrl(val url: String) : FixAction()
}

/**
 * One reliability permission item as a pair of [status] + optional [fixAction].
 * [fixAction] is non-null if and only if [status] == [ItemStatus.RISKY] and a fix is available.
 */
data class DashboardPermissionItem(
    val status: ItemStatus,
    val fixAction: FixAction? = null,
)

/**
 * Full UI state for the reliability dashboard (spec §2.13, Law 6).
 *
 * This is the on-device analytics substitute (Laws 4 & 5): no third-party SDK, no off-device
 * reporting — the dashboard shows what the engine is actually doing, in plain language.
 *
 * Timestamps are stored as epoch-ms (null = never recorded). The composable converts them to
 * locale-aware display strings via [android.text.format.DateFormat] (Standards §7).
 *
 * [oemName] is forwarded so the composable can show the OEM-specific hint alongside the
 * auto-start item (DECISIONS.md D5 / S6-A2).
 */
data class ReliabilityDashboardUiState(
    val exactAlarm: DashboardPermissionItem = DashboardPermissionItem(ItemStatus.OK),
    val batteryOptimization: DashboardPermissionItem = DashboardPermissionItem(ItemStatus.OK),
    val backgroundAutoStart: DashboardPermissionItem = DashboardPermissionItem(ItemStatus.OK),
    val notification: DashboardPermissionItem = DashboardPermissionItem(ItemStatus.OK),
    val fullScreenIntent: DashboardPermissionItem = DashboardPermissionItem(ItemStatus.OK),
    /**
     * DND policy access status (A1 — Critical Alerts). Risky when any active medication has
     * isCritical=true AND ACCESS_NOTIFICATION_POLICY is not granted.
     */
    val dndAccess: DashboardPermissionItem = DashboardPermissionItem(ItemStatus.OK),
    /**
     * Names of active medications with isCritical=true. Shown in the reliability dashboard
     * so the user can confirm which meds are on the critical path (Law 6).
     */
    val criticalMedNames: List<String> = emptyList(),
    val lastAlarmFiredEpochMs: Long? = null,
    val nextAlarmEpochMs: Long? = null,
    val alarmMode: AlarmMode? = null,
    val bootReceiverLastTrigger: String? = null,
    val bootReceiverLastTriggerEpochMs: Long? = null,
    val drugDbVersion: String? = null,
    val drugDbUpdatedAtEpochMs: Long? = null,
    val oemName: String = "",
)
