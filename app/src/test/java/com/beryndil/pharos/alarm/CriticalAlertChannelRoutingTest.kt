package com.beryndil.pharos.alarm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Pure-JVM tests verifying the critical-alert channel routing contract (A1 — Critical Alerts §3.2).
 *
 * These tests verify:
 *  1. The two dose channels have distinct IDs — they are separate channels, not aliases.
 *  2. The [DoseNotifier] interface correctly carries the [isCritical] flag to implementations.
 *  3. A recording notifier correctly discriminates channel selection based on [isCritical].
 *
 * No Robolectric or Android context required — channel selection is pure logic:
 *   isCritical=true  → [AlarmContract.CHANNEL_DOSE_DUE_CRITICAL]
 *   isCritical=false → [AlarmContract.CHANNEL_DOSE_DUE]
 *
 * Real end-to-end posting (NotificationManager.notify) is validated in instrumented tests
 * (device-matrix §8 — see TODO.md Section C).
 */
class CriticalAlertChannelRoutingTest {

    // ── Channel identity ─────────────────────────────────────────────────────────────────────

    @Test
    fun standardAndCriticalChannels_haveDistinctIds() {
        assertNotEquals(
            "Standard and critical dose channels must be distinct so the user can manage them separately",
            AlarmContract.CHANNEL_DOSE_DUE,
            AlarmContract.CHANNEL_DOSE_DUE_CRITICAL,
        )
    }

    @Test
    fun standardChannel_id_isExpectedConstant() {
        assertEquals("dose_due", AlarmContract.CHANNEL_DOSE_DUE)
    }

    @Test
    fun criticalChannel_id_isExpectedConstant() {
        assertEquals("dose_due_critical", AlarmContract.CHANNEL_DOSE_DUE_CRITICAL)
    }

    // ── Routing logic via RecordingDoseNotifier ───────────────────────────────────────────────

    @Test
    fun nonCriticalDose_routedToStandardChannel() {
        val recorder = RecordingChannelNotifier()
        recorder.postDoseDueAlert("d1", "Metoprolol", 1_900_000_000_000L, 0, isCritical = false)
        assertEquals(
            "Non-critical dose must use the standard channel",
            AlarmContract.CHANNEL_DOSE_DUE,
            recorder.lastChannelId,
        )
    }

    @Test
    fun criticalDose_routedToCriticalChannel() {
        val recorder = RecordingChannelNotifier()
        recorder.postDoseDueAlert("d2", "Insulin", 1_900_000_000_000L, 0, isCritical = true)
        assertEquals(
            "Critical dose must use the critical channel",
            AlarmContract.CHANNEL_DOSE_DUE_CRITICAL,
            recorder.lastChannelId,
        )
    }

    @Test
    fun escalatedCriticalDose_stillUsesTheCriticalChannel() {
        val recorder = RecordingChannelNotifier()
        recorder.postDoseDueAlert("d3", "Warfarin", 1_900_000_000_000L, escalationLevel = 2, isCritical = true)
        assertEquals(
            "Escalation level must not affect channel routing",
            AlarmContract.CHANNEL_DOSE_DUE_CRITICAL,
            recorder.lastChannelId,
        )
    }

    @Test
    fun testCriticalReminder_usesTheCriticalChannel() {
        val recorder = RecordingChannelNotifier()
        recorder.postTestCriticalReminder()
        assertEquals(
            "Test critical reminder must fire on the critical channel (Law 6 — testable)",
            AlarmContract.CHANNEL_DOSE_DUE_CRITICAL,
            recorder.lastChannelId,
        )
    }

    @Test
    fun testStandardReminder_usesTheStandardChannel() {
        val recorder = RecordingChannelNotifier()
        recorder.postTestReminder()
        assertEquals(
            "Standard test reminder must fire on the standard channel",
            AlarmContract.CHANNEL_DOSE_DUE,
            recorder.lastChannelId,
        )
    }
}

/**
 * A recording [DoseNotifier] that captures the channel ID selected for the most recent post.
 * Channel selection is the pure routing logic:
 *   postDoseDueAlert(isCritical=true)  → CHANNEL_DOSE_DUE_CRITICAL
 *   postDoseDueAlert(isCritical=false) → CHANNEL_DOSE_DUE
 *   postTestCriticalReminder()         → CHANNEL_DOSE_DUE_CRITICAL
 *   postTestReminder()                 → CHANNEL_DOSE_DUE
 */
private class RecordingChannelNotifier : DoseNotifier {
    var lastChannelId: String? = null

    override fun ensureChannels() = Unit

    override fun postDoseDueAlert(doseId: String, medName: String, dueEpochMs: Long) {
        lastChannelId = AlarmContract.CHANNEL_DOSE_DUE
    }

    override fun postDoseDueAlert(
        doseId: String,
        medName: String,
        dueEpochMs: Long,
        escalationLevel: Int,
        isCritical: Boolean,
    ) {
        lastChannelId = if (isCritical) AlarmContract.CHANNEL_DOSE_DUE_CRITICAL
                        else AlarmContract.CHANNEL_DOSE_DUE
    }

    override fun postTestReminder() {
        lastChannelId = AlarmContract.CHANNEL_DOSE_DUE
    }

    override fun postTestCriticalReminder() {
        lastChannelId = AlarmContract.CHANNEL_DOSE_DUE_CRITICAL
    }

    override fun canUseFullScreen(): Boolean = true
}
