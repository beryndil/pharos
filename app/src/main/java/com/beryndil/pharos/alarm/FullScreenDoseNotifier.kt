package com.beryndil.pharos.alarm

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.beryndil.pharos.R

/**
 * [DoseNotifier] that posts a high-importance, full-screen-intent notification on the dose
 * channel and launches [DueAlertActivity] (spec §2.8, §3.4; Standards §3, §4).
 *
 * The dose channel is created at [IMPORTANCE_HIGH]; importance cannot be raised later, so it is
 * set correctly from the start (Standards §4). Full sacred-channel enforcement is Slice 5.
 *
 * Degradation rule: if full-screen intents are gated off (Android 14) or POST_NOTIFICATIONS is
 * not granted, the alarm has still fired — we post a heads-up notification rather than dropping
 * anything (Law 6). When notifications are blocked entirely the system drops the post; that fact
 * is surfaced in the reliability dashboard (Slice 6).
 */
class FullScreenDoseNotifier(private val context: Context) : DoseNotifier {

    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    override fun ensureChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (notificationManager.getNotificationChannel(AlarmContract.CHANNEL_DOSE_DUE) != null) {
            return
        }
        val channel = NotificationChannel(
            AlarmContract.CHANNEL_DOSE_DUE,
            context.getString(R.string.channel_dose_due_name),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = context.getString(R.string.channel_dose_due_desc)
            setBypassDnd(true)
            enableLights(true)
            enableVibration(true)
        }
        notificationManager.createNotificationChannel(channel)
    }

    override fun postDoseDueAlert(doseId: String, medName: String, dueEpochMs: Long) {
        postDoseDueAlert(doseId, medName, dueEpochMs, escalationLevel = 0)
    }

    override fun postDoseDueAlert(
        doseId: String,
        medName: String,
        dueEpochMs: Long,
        escalationLevel: Int,
    ) {
        ensureChannels()

        val fullScreenIntent = Intent(context, DueAlertActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(AlarmContract.EXTRA_DOSE_ID, doseId)
            putExtra(AlarmContract.EXTRA_MED_NAME, medName)
            putExtra(AlarmContract.EXTRA_DUE_EPOCH_MS, dueEpochMs)
        }
        val fullScreenPi = PendingIntent.getActivity(
            context,
            AlarmContract.REQUEST_FULL_SCREEN,
            fullScreenIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val notification = NotificationCompat.Builder(context, AlarmContract.CHANNEL_DOSE_DUE)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(context.getString(R.string.dose_due_notification_title))
            .setContentText(medName)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setOngoing(true)
            .setAutoCancel(false)
            // Escalation (spec §2.8): each re-alert sounds again rather than alerting only once.
            .setOnlyAlertOnce(false)
            .setFullScreenIntent(fullScreenPi, true)
            .setContentIntent(fullScreenPi)
            .addAction(0, context.getString(R.string.dose_action_taken), actionPi(doseId, AlarmContract.ACTION_USER_TAKEN))
            .addAction(0, context.getString(R.string.dose_action_snooze), actionPi(doseId, AlarmContract.ACTION_USER_SNOOZE))
            .addAction(0, context.getString(R.string.dose_action_skip), actionPi(doseId, AlarmContract.ACTION_USER_SKIP))
            .build()

        post(AlarmContract.NOTIFICATION_DOSE_DUE, notification)
    }

    override fun cancelDoseAlert(doseId: String) {
        NotificationManagerCompat.from(context).cancel(AlarmContract.NOTIFICATION_DOSE_DUE)
    }

    /** A notification-action [PendingIntent] routed to [DoseActionReceiver] carrying the dose id. */
    private fun actionPi(doseId: String, action: String): PendingIntent {
        val intent = Intent(context, DoseActionReceiver::class.java)
            .setAction(action)
            .putExtra(AlarmContract.EXTRA_DOSE_ID, doseId)
        return PendingIntent.getBroadcast(
            context,
            (doseId.hashCode() and 0x0FFFFFFF) xor action.hashCode(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    override fun postTestReminder() {
        ensureChannels()
        val notification = NotificationCompat.Builder(context, AlarmContract.CHANNEL_DOSE_DUE)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(context.getString(R.string.test_reminder_title))
            .setContentText(context.getString(R.string.test_reminder_body))
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setAutoCancel(true)
            .build()
        post(AlarmContract.NOTIFICATION_TEST, notification)
    }

    override fun canUseFullScreen(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            notificationManager.canUseFullScreenIntent()
        } else {
            true
        }

    private fun post(id: Int, notification: android.app.Notification) {
        // Inline POST_NOTIFICATIONS guard (API 33+). The check is inline (not a helper) so lint's
        // permission flow analysis recognizes it. When not granted, the OS would drop the post;
        // the alarm still fired and a dose alert's full-screen intent still launches the activity.
        // This gap is surfaced in the reliability dashboard (Slice 6) — we never throw here.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        NotificationManagerCompat.from(context).notify(id, notification)
    }
}
