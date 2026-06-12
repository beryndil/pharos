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
    fun doseChannel_isHighImportance_andTheOnlyChannel() {
        notifier.ensureChannels()

        val channel = notificationManager.getNotificationChannel(AlarmContract.CHANNEL_DOSE_DUE)
        assertNotNull("dose channel must exist", channel)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, channel!!.importance)
        assertTrue("dose channel bypasses Do Not Disturb (safety-critical)", channel.canBypassDnd())

        // Exactly one channel — the sacred dose channel. No marketing/refill channel here.
        assertEquals(1, notificationManager.notificationChannels.size)
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
