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
}
