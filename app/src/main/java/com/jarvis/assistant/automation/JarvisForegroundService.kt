package com.jarvis.assistant.automation

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.content.ContextCompat
import com.jarvis.assistant.device.JarvisNotifications

/**
 * Short-lived foreground service used while a routine is executing, so the user can
 * see that JARVIS is working in the background instead of it happening invisibly.
 * It never captures audio or reads data; it only shows the progress notification.
 */
class JarvisForegroundService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val name = intent?.getStringExtra(EXTRA_LABEL)
        val text = if (name.isNullOrBlank()) "Running your schedule" else "Running \"$name\""
        val notification = JarvisNotifications
            .builder(this, JarvisNotifications.CHANNEL_AUTOMATION, "JARVIS", text)
            .setOngoing(true)
            .setAutoCancel(false)
            .build()
        startForeground(NOTIFICATION_ID, notification)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    companion object {
        private const val NOTIFICATION_ID = 4711
        private const val EXTRA_LABEL = "extra_label"

        fun begin(context: Context, label: String?) {
            val intent = Intent(context, JarvisForegroundService::class.java)
                .putExtra(EXTRA_LABEL, label)
            ContextCompat.startForegroundService(context, intent)
        }

        fun end(context: Context) {
            context.stopService(Intent(context, JarvisForegroundService::class.java))
        }
    }
}
