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

    /**
     * Critical dose channel (A1): for medications marked isCritical=true. Sacred (Law 1 —
     * dose reminders only). Bypasses DND and uses alarm-usage AudioAttributes so it sounds
     * at alarm volume in silent/vibrate. Created correctly once at app start; never silently
     * recreated (bypass-DND only takes effect on first creation per the Android channel model).
     */
    const val CHANNEL_DOSE_DUE_CRITICAL = "dose_due_critical"

    /**
     * Separate, separately-disableable refill / low-supply channel (spec §2.8, §2.9, Law 1).
     * The user can silence this channel without touching dose reminders.
     * Nothing from the dose reminder path may post here; nothing from the refill path
     * may post on [CHANNEL_DOSE_DUE].
     */
    const val CHANNEL_REFILL = "refill"

    /**
     * Legacy constant kept for tests. The active dose-due tray slot is now per-dose-instance
     * (A3 — multi-due notifications). Use [notificationIdForDose] at call sites.
     */
    const val NOTIFICATION_DOSE_DUE = 5001
    const val NOTIFICATION_TEST = 5002
    /** Notification id for the critical-channel test reminder (Law 6 — testable). */
    const val NOTIFICATION_TEST_CRITICAL = 5003

    /**
     * Base id for per-dose-instance due-alert notifications (A3 — multi-due fix).
     * Each dose instance gets its own stable tray slot:
     *   notificationIdForDose(doseId) = NOTIFICATION_DOSE_DUE_BASE + (doseId.hashCode & 0x0FFFFFFF)
     * Range chosen to avoid collision with the test slots and refill slots.
     */
    const val NOTIFICATION_DOSE_DUE_BASE = 7000

    /**
     * Derive a stable notification id from a dose instance id (A3).
     * Each concurrently DUE dose instance appears as its own tray entry so the user can act
     * on both independently (tap Taken on one without dismissing the other).
     */
    fun notificationIdForDose(doseId: String): Int =
        NOTIFICATION_DOSE_DUE_BASE + (doseId.hashCode() and 0x0FFFFFFF)

    /**
     * Base id for per-medication refill/low-supply notifications (spec §2.9).
     * Each medication gets its own stable slot: NOTIFICATION_REFILL_BASE + (medId.hashCode & 0x0FFFFFFF).
     * Range chosen to avoid collision with the dose and test slots above.
     */
    const val NOTIFICATION_REFILL_BASE = 6000
}
