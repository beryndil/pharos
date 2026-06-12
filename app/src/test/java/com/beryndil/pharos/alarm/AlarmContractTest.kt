package com.beryndil.pharos.alarm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [AlarmContract] helper functions (A3 — multi-due notifications).
 */
class AlarmContractTest {

    // ── notificationIdForDose (A3-3) ─────────────────────────────────────────────────────────

    @Test
    fun notificationIdForDose_sameIdForSameDoseId() {
        val doseId = "dose-abc-123"
        assertEquals(
            AlarmContract.notificationIdForDose(doseId),
            AlarmContract.notificationIdForDose(doseId),
        )
    }

    @Test
    fun notificationIdForDose_differentIdsForDifferentDoseIds() {
        val id1 = AlarmContract.notificationIdForDose("dose-aaa-001")
        val id2 = AlarmContract.notificationIdForDose("dose-bbb-002")
        assertNotEquals(
            "Two different dose IDs must produce different notification IDs",
            id1, id2,
        )
    }

    @Test
    fun notificationIdForDose_allIdsAtOrAboveBase() {
        // Verify the derived ID is always ≥ NOTIFICATION_DOSE_DUE_BASE (no under-run).
        val sampleIds = listOf(
            "dose-1",
            "dose-2",
            "00000000-0000-0000-0000-000000000000",
            "ffffffff-ffff-ffff-ffff-ffffffffffff",
        )
        for (doseId in sampleIds) {
            val id = AlarmContract.notificationIdForDose(doseId)
            assertTrue(
                "notificationIdForDose($doseId) = $id must be >= ${AlarmContract.NOTIFICATION_DOSE_DUE_BASE}",
                id >= AlarmContract.NOTIFICATION_DOSE_DUE_BASE,
            )
        }
    }

    @Test
    fun notificationIdForDose_doesNotCollideWithTestOrRefillSlots() {
        // The dose-due base (7000) must not overlap with NOTIFICATION_TEST (5002),
        // NOTIFICATION_TEST_CRITICAL (5003), or the refill base (6000).
        val sampleId = AlarmContract.notificationIdForDose("some-dose")
        assertTrue(sampleId != AlarmContract.NOTIFICATION_TEST)
        assertTrue(sampleId != AlarmContract.NOTIFICATION_TEST_CRITICAL)
        assertTrue(sampleId >= AlarmContract.NOTIFICATION_DOSE_DUE_BASE)
    }
}
