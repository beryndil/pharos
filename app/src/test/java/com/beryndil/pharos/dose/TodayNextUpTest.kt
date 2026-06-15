package com.beryndil.pharos.dose

import com.beryndil.pharos.data.dose.DoseRow
import com.beryndil.pharos.data.regimen.entity.DoseState
import com.beryndil.pharos.dose.ui.MAX_NEXT_UP
import com.beryndil.pharos.dose.ui.NextUpItem
import com.beryndil.pharos.dose.ui.selectNextUp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [selectNextUp] — the "Next up" summary logic (F3).
 *
 * Pure-JVM: [selectNextUp] is a standalone function that maps List<DoseRow> → List<NextUpItem];
 * no coroutines, no Android, no DB required.
 */
class TodayNextUpTest {

    // ── Helpers ───────────────────────────────────────────────────────────

    private fun row(
        id: String,
        medName: String,
        dueEpochMs: Long,
        state: DoseState,
    ) = DoseRow(
        doseId = id,
        medicationId = "med-$id",
        medName = medName,
        strength = "10 mg",
        doseAmount = "",
        dueEpochMs = dueEpochMs,
        state = state,
    )

    // ── Tests ─────────────────────────────────────────────────────────────

    @Test
    fun `empty input produces empty nextUp`() {
        assertTrue(selectNextUp(emptyList()).isEmpty())
    }

    @Test
    fun `only SCHEDULED doses are included`() {
        val doses = listOf(
            row("due",       "A", 1000L, DoseState.DUE),
            row("snoozed",   "B", 2000L, DoseState.SNOOZED),
            row("taken",     "C", 3000L, DoseState.TAKEN),
            row("skipped",   "D", 4000L, DoseState.SKIPPED),
            row("missed",    "E", 5000L, DoseState.MISSED),
            row("scheduled", "F", 6000L, DoseState.SCHEDULED),
        )
        val nextUp = selectNextUp(doses)
        assertEquals(1, nextUp.size)
        assertEquals("F", nextUp[0].medName)
    }

    @Test
    fun `SCHEDULED doses are sorted by dueEpochMs ascending`() {
        val doses = listOf(
            row("s3", "Med-C", 3000L, DoseState.SCHEDULED),
            row("s1", "Med-A", 1000L, DoseState.SCHEDULED),
            row("s2", "Med-B", 2000L, DoseState.SCHEDULED),
        )
        val nextUp = selectNextUp(doses)
        assertEquals(listOf(1000L, 2000L, 3000L), nextUp.map { it.dueEpochMs })
    }

    @Test
    fun `result is limited to MAX_NEXT_UP items`() {
        val doses = (1..MAX_NEXT_UP + 3).map { i ->
            row("s$i", "Med-$i", i * 1000L, DoseState.SCHEDULED)
        }
        val nextUp = selectNextUp(doses)
        assertEquals(MAX_NEXT_UP, nextUp.size)
    }

    @Test
    fun `result includes exactly the earliest MAX_NEXT_UP doses`() {
        val doses = (1..MAX_NEXT_UP + 2).map { i ->
            row("s$i", "Med-$i", i * 1000L, DoseState.SCHEDULED)
        }
        val nextUp = selectNextUp(doses)
        // Should be the first MAX_NEXT_UP by time (1000ms…5000ms when MAX_NEXT_UP=5)
        val expectedTimes = (1..MAX_NEXT_UP).map { it * 1000L }
        assertEquals(expectedTimes, nextUp.map { it.dueEpochMs })
    }

    @Test
    fun `custom max parameter is respected`() {
        val doses = (1..10).map { i -> row("s$i", "M$i", i * 100L, DoseState.SCHEDULED) }
        assertEquals(3, selectNextUp(doses, max = 3).size)
    }

    @Test
    fun `NextUpItem fields are mapped correctly`() {
        val doses = listOf(row("id1", "Lisinopril", 5000L, DoseState.SCHEDULED).copy(strength = "5 mg"))
        val item: NextUpItem = selectNextUp(doses).single()
        assertEquals("id1",        item.doseId)
        assertEquals("Lisinopril", item.medName)
        assertEquals("5 mg",       item.strength)
        assertEquals(5000L,        item.dueEpochMs)
    }

    @Test
    fun `all-actionable list with no SCHEDULED produces empty nextUp`() {
        val doses = listOf(
            row("a", "A", 1000L, DoseState.DUE),
            row("b", "B", 2000L, DoseState.SNOOZED),
        )
        assertTrue(selectNextUp(doses).isEmpty())
    }
}
