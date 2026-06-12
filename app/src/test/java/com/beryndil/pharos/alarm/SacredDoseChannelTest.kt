package com.beryndil.pharos.alarm

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * The dose channel is sacred (Law 1, spec §2.8): it carries dose-due alerts ONLY, at
 * IMPORTANCE_HIGH, and is the single channel this notifier creates. Refill/marketing/re-engagement
 * never post here (those get their own channels in later slices).
 */
@RunWith(RobolectricTestRunner::class)
class SacredDoseChannelTest {

    private lateinit var context: Context
    private lateinit var notificationManager: NotificationManager
    private lateinit var notifier: FullScreenDoseNotifier

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        notifier = FullScreenDoseNotifier(context)
    }

    @Test
    fun bothDoseChannels_areHighImportance_andNoOtherChannelIs() {
        notifier.ensureChannels()

        // Standard dose channel must exist at IMPORTANCE_HIGH.
        val standard = notificationManager.getNotificationChannel(AlarmContract.CHANNEL_DOSE_DUE)
        assertNotNull("standard dose channel must exist", standard)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, standard!!.importance)
        assertTrue("standard dose channel bypasses DND", standard.canBypassDnd())

        // Critical dose channel must exist at IMPORTANCE_HIGH (A1 — Critical Alerts).
        val critical = notificationManager.getNotificationChannel(AlarmContract.CHANNEL_DOSE_DUE_CRITICAL)
        assertNotNull("critical dose channel must exist", critical)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, critical!!.importance)
        assertTrue("critical dose channel bypasses DND", critical.canBypassDnd())

        // Only the two dose channels (sacred Law 1) are IMPORTANCE_HIGH.
        // Refill / other channels must be at lower importance so they can be silenced without
        // silencing dose reminders.
        val highImportanceChannels = notificationManager.notificationChannels
            .filter { it.importance >= NotificationManager.IMPORTANCE_HIGH }
        val highIds = highImportanceChannels.map { it.id }.toSet()
        assertEquals(
            "Only the standard and critical dose channels must be IMPORTANCE_HIGH",
            setOf(AlarmContract.CHANNEL_DOSE_DUE, AlarmContract.CHANNEL_DOSE_DUE_CRITICAL),
            highIds,
        )
    }

    @Test
    fun doseDueAlert_postsOnTheSacredDoseChannel() {
        notifier.postDoseDueAlert("dose-1", "Metoprolol", 1_900_000_000_000L)

        val active = shadowOf(notificationManager).activeNotifications
        assertEquals(1, active.size)
        assertEquals(
            "the DUE alert must post on the sacred dose channel",
            AlarmContract.CHANNEL_DOSE_DUE,
            active.first().notification.channelId,
        )
    }

    @Test
    fun cancelDoseAlert_clearsTheActiveDoseNotification() {
        notifier.postDoseDueAlert("dose-1", "Metoprolol", 1_900_000_000_000L)
        notifier.cancelDoseAlert("dose-1")

        assertTrue(shadowOf(notificationManager).activeNotifications.isEmpty())
    }
}
