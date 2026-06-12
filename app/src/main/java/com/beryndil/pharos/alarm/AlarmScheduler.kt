package com.beryndil.pharos.alarm

/**
 * The kind of alarm being scheduled. Drives which [android.app.AlarmManager] API is used and
 * how the alarm is recorded for the reliability dashboard (Slice 6, spec §2.13).
 */
enum class AlarmKind {
    /** A dose-due alert. Time-critical → setAlarmClock (Doze-exempt, status-bar visible). */
    DOSE,

    /** A user-initiated "test reminder now" alert. Same time-critical path as [DOSE] (Law 6). */
    TEST,

    /** The daily DST/midnight re-anchor. Maintenance, not user-facing → allow-while-idle. */
    DAILY_ROLLOVER,
}

/**
 * Which delivery mode an alarm was actually scheduled with. Recorded so the reliability
 * dashboard can tell the user whether exact timing is in effect (Law 6 — reliability is visible).
 */
enum class AlarmMode {
    /** Exact delivery (setAlarmClock / setExactAndAllowWhileIdle). */
    EXACT,

    /** Inexact windowed fallback (setWindow) used because exact alarms are unavailable. */
    WINDOWED_FALLBACK,
}

/** A request to (re)schedule a single alarm slot. */
data class AlarmRequest(
    val kind: AlarmKind,
    val triggerAtEpochMs: Long,
    val doseId: String? = null,
    val medName: String? = null,
    val dueEpochMs: Long? = null,
)

/**
 * Abstraction over [android.app.AlarmManager] so the alarm engine is unit-testable with
 * Robolectric's `ShadowAlarmManager` (Standards §10). Implementations must:
 *
 *  - Use a stable [android.app.PendingIntent] request code per [AlarmKind] so a re-schedule
 *    REPLACES the prior alarm (single-fire-and-reschedule, spec §3.4) rather than stacking.
 *  - Gate exact scheduling on [canScheduleExact]; when false, fall back to a windowed alarm —
 *    NEVER drop the reminder (Law 6, spec §3.4 graceful degradation).
 *  - All `PendingIntent`s carry `FLAG_IMMUTABLE` (Standards §3).
 */
interface AlarmScheduler {

    /** True when exact alarms can currently be scheduled (always true below API 31). */
    fun canScheduleExact(): Boolean

    /**
     * Schedule [request]. Returns the [AlarmMode] actually used so the caller can record it.
     * Re-scheduling the same [AlarmKind] replaces the previous alarm for that slot.
     */
    fun schedule(request: AlarmRequest): AlarmMode

    /** Cancel the pending alarm for [kind], if any. */
    fun cancel(kind: AlarmKind)
}
