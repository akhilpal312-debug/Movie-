package com.jarvis.assistant.device

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/** Channel setup and builders for the two notifications JARVIS is allowed to post. */
object JarvisNotifications {
    const val CHANNEL_SESSION = "jarvis.session"
    const val CHANNEL_REMINDERS = "jarvis.reminders"
    const val CHANNEL_AUTOMATION = "jarvis.automation"

    fun ensureChannels(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val session = NotificationChannel(
            CHANNEL_SESSION,
            "Voice session",
            NotificationManager.IMPORTANCE_LOW,
        ).apply { description = "Shown while JARVIS is listening to you." }
        val reminders = NotificationChannel(
            CHANNEL_REMINDERS,
            "Reminders",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply { description = "Reminders you asked JARVIS to schedule." }
        val automation = NotificationChannel(
            CHANNEL_AUTOMATION,
            "Routines",
            NotificationManager.IMPORTANCE_LOW,
        ).apply { description = "Progress of scheduled routines." }
        manager.createNotificationChannels(listOf(session, reminders, automation))
    }

    fun builder(context: Context, channel: String, title: String, text: String): NotificationCompat.Builder =
        NotificationCompat.Builder(context, channel)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)

    fun canPost(context: Context): Boolean = NotificationManagerCompat.from(context).areNotificationsEnabled()
}

sealed interface ScheduleOutcome {
    data class Scheduled(val exact: Boolean) : ScheduleOutcome
    data object InThePast : ScheduleOutcome
}

/**
 * Schedules strictly local reminders. When exact alarms are not permitted by the
 * user, Android allows an inexact alarm; JARVIS reports which one it used rather than
 * silently promising precision it does not have.
 */
@Singleton
class ReminderScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun schedule(message: String, triggerAtMillis: Long): ScheduleOutcome {
        val now = System.currentTimeMillis()
        if (triggerAtMillis <= now) return ScheduleOutcome.InThePast

        JarvisNotifications.ensureChannels(context)
        val manager = context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
        val requestCode = (message + triggerAtMillis).hashCode() and 0x7FFFFFFF
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            putExtra(ReminderReceiver.EXTRA_MESSAGE, message)
            putExtra(ReminderReceiver.EXTRA_ID, requestCode)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val exactAllowed = android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S ||
            manager.canScheduleExactAlarms()

        return runCatching {
            if (exactAllowed) {
                manager.setExactAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
            } else {
                manager.set(android.app.AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
            }
            ScheduleOutcome.Scheduled(exactAllowed)
        }.getOrElse {
            ScheduleOutcome.InThePast
        }
    }
}

/** Delivers a reminder locally. Nothing about it leaves the device. */
class ReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val message = intent.getStringExtra(EXTRA_MESSAGE)?.take(300) ?: return
        JarvisNotifications.ensureChannels(context)
        if (!JarvisNotifications.canPost(context)) return

        val openApp = context.packageManager.getLaunchIntentForPackage(context.packageName)
        val contentIntent = openApp?.let {
            PendingIntent.getActivity(
                context,
                0,
                it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
        val notification = JarvisNotifications
            .builder(context, JarvisNotifications.CHANNEL_REMINDERS, "Reminder", message)
            .setContentIntent(contentIntent)
            .build()

        NotificationManagerCompat.from(context).notify(
            intent.getIntExtra(EXTRA_ID, REMINDER_ID.getAndIncrement()),
            notification,
        )
    }

    companion object {
        const val EXTRA_MESSAGE = "extra_message"
        const val EXTRA_ID = "extra_id"
        private val REMINDER_ID = AtomicInteger(2000)
    }
}
