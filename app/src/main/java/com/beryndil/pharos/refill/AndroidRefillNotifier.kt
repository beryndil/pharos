package com.beryndil.pharos.refill

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.beryndil.pharos.R
import com.beryndil.pharos.alarm.AlarmContract

/**
 * [RefillNotifier] implementation that posts low-supply alerts on the dedicated refill channel
 * (spec §2.8, §2.9, Law 1).
 *
 * Channel isolation guarantee: this class creates and uses ONLY [AlarmContract.CHANNEL_REFILL].
 * It never references [AlarmContract.CHANNEL_DOSE_DUE]. The user can disable the refill
 * channel in system notification settings without affecting dose reminders.
 *
 * Notification ids are derived from a stable hash of the medication id so each medication
 * has its own dismissible notification slot.
 */
class AndroidRefillNotifier(private val context: Context) : RefillNotifier {

    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    override fun ensureRefillChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (notificationManager.getNotificationChannel(AlarmContract.CHANNEL_REFILL) != null) return
        val channel = NotificationChannel(
            AlarmContract.CHANNEL_REFILL,
            context.getString(R.string.channel_refill_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = context.getString(R.string.channel_refill_desc)
            enableVibration(false)
        }
        notificationManager.createNotificationChannel(channel)
    }

    override fun postLowSupplyAlert(medicationId: String, medName: String, daysLeft: Int) {
        ensureRefillChannel()

        val title = if (daysLeft == 0) {
            context.getString(R.string.refill_notification_zero_title, medName)
        } else {
            context.getString(R.string.refill_notification_low_title, medName)
        }
        val body = if (daysLeft == 0) {
            context.getString(R.string.refill_notification_zero_body, medName)
        } else {
            context.getString(R.string.refill_notification_low_body, daysLeft, medName)
        }

        val notification = NotificationCompat.Builder(context, AlarmContract.CHANNEL_REFILL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()

        postIfPermitted(notificationIdFor(medicationId), notification)
    }

    override fun cancelLowSupplyAlert(medicationId: String) {
        NotificationManagerCompat.from(context).cancel(notificationIdFor(medicationId))
    }

    private fun postIfPermitted(id: Int, notification: android.app.Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        NotificationManagerCompat.from(context).notify(id, notification)
    }

    companion object {
        /**
         * Derive a stable notification id from the medication id.
         * Offset by [AlarmContract.NOTIFICATION_REFILL_BASE] to avoid collision with dose/test slots.
         */
        fun notificationIdFor(medicationId: String): Int =
            AlarmContract.NOTIFICATION_REFILL_BASE + (medicationId.hashCode() and 0x0FFFFFFF)
    }
}
