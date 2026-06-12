package com.beryndil.pharos.alarm

/**
 * Stable string/int constants shared by the alarm engine components (spec §3.4).
 *
 * These are the wire contract between [AndroidAlarmScheduler] (which builds the
 * [android.app.PendingIntent]s), [AlarmReceiver] (which receives the fired alarms), and
 * [AlarmReRegistrationReceiver] (which receives system broadcasts). Keeping them in one place
 * prevents the classic "alarm fires but the receiver filters it out because the action string
 * drifted" silent failure.
 */
object AlarmContract {

    // ── Actions for our own fired alarms (delivered to AlarmReceiver) ─────────────
    const val ACTION_DOSE_DUE = "com.beryndil.pharos.action.DOSE_DUE"
    const val ACTION_TEST_REMINDER = "com.beryndil.pharos.action.TEST_REMINDER"
    const val ACTION_DAILY_ROLLOVER = "com.beryndil.pharos.action.DAILY_ROLLOVER"

    // ── Slice 5 dose state machine: per-dose timed transitions (delivered to AlarmReceiver) ──
    /** The escalating re-alert / snooze-wake tick for a DUE or SNOOZED dose (spec §2.6, §2.8). */
    const val ACTION_DOSE_REALERT = "com.beryndil.pharos.action.DOSE_REALERT"

    /** The hard miss-window deadline: fire MISSED even if the app is closed (D2). */
    const val ACTION_DOSE_MISS_CHECK = "com.beryndil.pharos.action.DOSE_MISS_CHECK"

    // ── Slice 5: user dose actions from the notification (delivered to DoseActionReceiver) ──
    const val ACTION_USER_TAKEN = "com.beryndil.pharos.action.USER_TAKEN"
    const val ACTION_USER_SNOOZE = "com.beryndil.pharos.action.USER_SNOOZE"
    const val ACTION_USER_SKIP = "com.beryndil.pharos.action.USER_SKIP"

    /** Escalation level carried on a re-alert so the notifier can raise intensity (spec §2.8). */
    const val EXTRA_ESCALATION_LEVEL = "com.beryndil.pharos.extra.ESCALATION_LEVEL"

    // ── Intent extras ─────────────────────────────────────────────────────────────
    const val EXTRA_DOSE_ID = "com.beryndil.pharos.extra.DOSE_ID"
    const val EXTRA_MED_NAME = "com.beryndil.pharos.extra.MED_NAME"
    const val EXTRA_DUE_EPOCH_MS = "com.beryndil.pharos.extra.DUE_EPOCH_MS"

    // ── PendingIntent request codes (one stable slot per alarm purpose) ──────────────
    // Single-fire-and-reschedule keeps at most ONE pending dose alarm, so a constant request
    // code per slot means a re-schedule REPLACES the prior alarm rather than stacking.
    const val REQUEST_DOSE = 4001
    const val REQUEST_TEST = 4002
    const val REQUEST_DAILY_ROLLOVER = 4003
    const val REQUEST_FULL_SCREEN = 4004
    const val REQUEST_SHOW_INTENT = 4005

    // ── System action delivered on API 31+ when exact-alarm permission changes ──────
    // Hard-coded because AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED is
    // only resolvable at API 31; the literal is stable and safe to filter on older OS versions.
    const val ACTION_EXACT_ALARM_PERMISSION_CHANGED =
        "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED"

    // ── Notification channel ids ────────────────────────────────────────────────────
    // The dose channel is sacred (Law 1): dose-due alerts ONLY. Full sacred-channel semantics
    // (escalation, no-other-content enforcement) are Slice 5; this slice creates it so the
    // full-screen DUE alert has a channel to post on.
    const val CHANNEL_DOSE_DUE = "dose_due"

    /** Notification id for the active dose-due alert. One slot — the active DUE alert. */
    const val NOTIFICATION_DOSE_DUE = 5001
    const val NOTIFICATION_TEST = 5002
}
