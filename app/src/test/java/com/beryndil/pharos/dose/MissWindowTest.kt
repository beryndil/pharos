package com.beryndil.pharos.dose

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure unit tests for the D2 miss-window math (DECISIONS.md D2). No Android, no Robolectric.
 */
class MissWindowTest {

    private val due = 1_900_000_000_000L
    private val hour = 60L * 60L * 1000L

    @Test
    fun fixedDose_noNextDose_isSixtyMinuteGrace() {
        val close = MissWindow.closeEpochMs(
            dueEpochMs = due,
            isWindowed = false,
            windowEndEpochMs = due + hour, // ignored for non-windowed
            nextScheduledDueEpochMs = null,
        )
        assertEquals(due + MissWindow.GRACE_MS, close)
    }

    @Test
    fun fixedDose_nextDoseBeforeGrace_capsAtNextDose() {
        val nextDue = due + 30L * 60L * 1000L // 30 min later — before the 60-min grace
        val close = MissWindow.closeEpochMs(
            dueEpochMs = due,
            isWindowed = false,
            windowEndEpochMs = null,
            nextScheduledDueEpochMs = nextDue,
        )
        assertEquals("D2: whichever comes first → the next dose", nextDue, close)
    }

    @Test
    fun fixedDose_nextDoseAfterGrace_usesGrace() {
        val nextDue = due + 4L * hour
        val close = MissWindow.closeEpochMs(
            dueEpochMs = due,
            isWindowed = false,
            windowEndEpochMs = null,
            nextScheduledDueEpochMs = nextDue,
        )
        assertEquals(due + MissWindow.GRACE_MS, close)
    }

    @Test
    fun windowedDose_usesWindowEnd_notSixtyMinuteGrace() {
        val windowEnd = due + 2L * hour // a 2-hour window — longer than the 60-min grace
        val close = MissWindow.closeEpochMs(
            dueEpochMs = due,
            isWindowed = true,
            windowEndEpochMs = windowEnd,
            nextScheduledDueEpochMs = null,
        )
        assertEquals("Windowed dose miss window = window end", windowEnd, close)
    }

    @Test
    fun windowedDose_cappedByNextDose() {
        val windowEnd = due + 5L * hour
        val nextDue = due + 3L * hour
        val close = MissWindow.closeEpochMs(
            dueEpochMs = due,
            isWindowed = true,
            windowEndEpochMs = windowEnd,
            nextScheduledDueEpochMs = nextDue,
        )
        assertEquals(nextDue, close)
    }

    // ── Per-medication grace (G1) ─────────────────────────────────────────

    @Test
    fun customGrace_tightWindow_15min_usesCustomGrace() {
        val graceMs = 15L * 60L * 1000L // 15 minutes
        val close = MissWindow.closeEpochMs(
            dueEpochMs = due,
            isWindowed = false,
            windowEndEpochMs = null,
            nextScheduledDueEpochMs = null,
            graceLengthMs = graceMs,
        )
        assertEquals("Tight grace: miss window = due + 15 min", due + graceMs, close)
    }

    @Test
    fun customGrace_looseWindow_180min_usesCustomGrace() {
        val graceMs = 180L * 60L * 1000L // 3 hours
        val close = MissWindow.closeEpochMs(
            dueEpochMs = due,
            isWindowed = false,
            windowEndEpochMs = null,
            nextScheduledDueEpochMs = null,
            graceLengthMs = graceMs,
        )
        assertEquals("Loose grace: miss window = due + 3 h", due + graceMs, close)
    }

    @Test
    fun customGrace_tightWindow_nextDoseCapsTighter() {
        // 15-min grace but next dose at 10 min → whichever first caps at 10 min
        val graceMs = 15L * 60L * 1000L
        val nextDue = due + 10L * 60L * 1000L
        val close = MissWindow.closeEpochMs(
            dueEpochMs = due,
            isWindowed = false,
            windowEndEpochMs = null,
            nextScheduledDueEpochMs = nextDue,
            graceLengthMs = graceMs,
        )
        assertEquals("Next dose at 10 min caps before 15-min grace", nextDue, close)
    }

    @Test
    fun customGrace_windowedDose_graceIgnored_windowEndUsed() {
        // For windowed doses the grace param is irrelevant — window end always wins.
        val windowEnd = due + hour
        val graceMs = 15L * 60L * 1000L // shorter than the window, should be ignored
        val close = MissWindow.closeEpochMs(
            dueEpochMs = due,
            isWindowed = true,
            windowEndEpochMs = windowEnd,
            nextScheduledDueEpochMs = null,
            graceLengthMs = graceMs,
        )
        assertEquals("Windowed dose always uses windowEnd, not graceLengthMs", windowEnd, close)
    }

    @Test
    fun defaultGrace_matchesSixtyMinuteConstant() {
        // Calling without graceLengthMs must match the explicit GRACE_MS behaviour.
        val withDefault = MissWindow.closeEpochMs(
            dueEpochMs = due,
            isWindowed = false,
            windowEndEpochMs = null,
            nextScheduledDueEpochMs = null,
        )
        val withExplicit = MissWindow.closeEpochMs(
            dueEpochMs = due,
            isWindowed = false,
            windowEndEpochMs = null,
            nextScheduledDueEpochMs = null,
            graceLengthMs = MissWindow.GRACE_MS,
        )
        assertEquals("Default graceLengthMs must equal GRACE_MS", withExplicit, withDefault)
    }
}
