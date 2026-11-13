package com.jarvis.assistant

import android.app.Application
import com.jarvis.assistant.device.JarvisNotifications
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class JarvisApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        // Notification channels are created up front so the first reminder or session
        // indicator never fails silently on Android 8+.
        JarvisNotifications.ensureChannels(this)
    }
}
