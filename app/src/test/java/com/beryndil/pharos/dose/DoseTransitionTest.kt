package com.beryndil.pharos.dose

import com.beryndil.pharos.data.regimen.entity.DoseState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The legal-transition table (spec §2.6). Pure unit test. */
class DoseTransitionTest {

    @Test
    fun legalTransitions_perSpecDiagram() {
        assertTrue(DoseTransition.isLegal(DoseState.SCHEDULED, DoseState.DUE))
        assertTrue(DoseTransition.isLegal(DoseState.DUE, DoseState.TAKEN))
        assertTrue(DoseTransition.isLegal(DoseState.DUE, DoseState.SNOOZED))
        assertTrue(DoseTransition.isLegal(DoseState.DUE, DoseState.SKIPPED))
        assertTrue(DoseTransition.isLegal(DoseState.DUE, DoseState.MISSED))
        assertTrue(DoseTransition.isLegal(DoseState.SNOOZED, DoseState.DUE))
        assertTrue(DoseTransition.isLegal(DoseState.SNOOZED, DoseState.MISSED))
        // Pragmatic additions (DECISIONS.md S5-A1): a snoozed dose stays user-actionable.
        assertTrue(DoseTransition.isLegal(DoseState.SNOOZED, DoseState.TAKEN))
        assertTrue(DoseTransition.isLegal(DoseState.SNOOZED, DoseState.SKIPPED))
    }

    @Test
    fun illegalTransitions_areRejected() {
        // Terminal states never transition.
        assertFalse(DoseTransition.isLegal(DoseState.TAKEN, DoseState.DUE))
        assertFalse(DoseTransition.isLegal(DoseState.SKIPPED, DoseState.DUE))
        assertFalse(DoseTransition.isLegal(DoseState.MISSED, DoseState.DUE))
        assertFalse(DoseTransition.isLegal(DoseState.TAKEN, DoseState.MISSED))
        // No skipping the alarm-fired step.
        assertFalse(DoseTransition.isLegal(DoseState.SCHEDULED, DoseState.TAKEN))
        assertFalse(DoseTransition.isLegal(DoseState.SCHEDULED, DoseState.MISSED))
        // No going backwards.
        assertFalse(DoseTransition.isLegal(DoseState.DUE, DoseState.SCHEDULED))
    }
}
