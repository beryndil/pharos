package com.beryndil.pharos.dose

/**
 * Pure miss-window math (DECISIONS.md D2). No Android, no I/O — unit-testable on the plain JVM.
 *
 * D2: a DUE dose becomes MISSED at **60 minutes after the due time OR the start of the same
 * medication's next scheduled dose, whichever comes first**. For a windowed dose the miss window
 * is the **window end**.
 */
object MissWindow {

    /** Default grace after the due time for fixed-time doses: 60 minutes (DECISIONS.md D2). */
    const val GRACE_MS = 60L * 60L * 1000L

    /**
     * The instant (UTC epoch-ms) at which this dose transitions to MISSED if not acted on.
     *
     * @param dueEpochMs the dose's scheduled due time.
     * @param isWindowed true when the dose belongs to a DOSE_WINDOW schedule.
     * @param windowEndEpochMs the window-close instant (for windowed doses); may be null.
     * @param nextScheduledDueEpochMs the same med's next SCHEDULED dose strictly after this one,
     *   or null if this is the last dose.
     * @param graceLengthMs per-medication grace duration in milliseconds; defaults to [GRACE_MS]
     *   (60 min). For windowed doses this parameter is ignored — the window end is always used.
     */
    fun closeEpochMs(
        dueEpochMs: Long,
        isWindowed: Boolean,
        windowEndEpochMs: Long?,
        nextScheduledDueEpochMs: Long?,
        graceLengthMs: Long = GRACE_MS,
    ): Long {
        // Windowed: the miss window is the window end. Fixed-time: per-med grace (default 60 min).
        val base = if (isWindowed && windowEndEpochMs != null) {
            windowEndEpochMs
        } else {
            dueEpochMs + graceLengthMs
        }
        // …OR the next scheduled dose of the same med, whichever comes first.
        return nextScheduledDueEpochMs?.let { minOf(base, it) } ?: base
    }
}
