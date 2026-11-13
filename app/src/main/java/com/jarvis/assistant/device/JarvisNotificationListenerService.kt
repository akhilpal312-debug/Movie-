package com.jarvis.assistant.device

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.jarvis.assistant.settings.PreferencesRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Notification access is optional and controlled by the user in system settings.
 *
 * While connected, JARVIS keeps a small in-memory list so the user can ask "what did I
 * miss?". Contents are never written to disk, never logged, and never part of an AI
 * request. Per-app exclusions and the consent flag are enforced before storage.
 */
@AndroidEntryPoint
class JarvisNotificationListenerService : NotificationListenerService() {

    @Inject
    lateinit var vault: NotificationVault

    @Inject
    lateinit var preferences: PreferencesRepository

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onListenerConnected() {
        super.onListenerConnected()
        vault.enabled = true
        scope.launch {
            preferences.preferences.collect { prefs ->
                vault.consentGranted = prefs.notificationAccessConsent
                vault.blockedPackages = prefs.notificationBlockedPackages
            }
        }
    }

    override fun onListenerDisconnected() {
        vault.enabled = false
        super.onListenerDisconnected()
    }

    override fun onDestroy() {
        scope.cancel()
        vault.enabled = false
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val notification = sbn ?: return
        if (notification.packageName == packageName) return
        if (notification.isOngoing) return

        val extras = notification.notification?.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.take(120)
        val text = (
            extras.getCharSequence(Notification.EXTRA_TEXT)
                ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
            )
            ?.toString()
            ?.take(200)
        if (title.isNullOrBlank() && text.isNullOrBlank()) return

        val appLabel = runCatching {
            val info = packageManager.getApplicationInfo(notification.packageName, 0)
            packageManager.getApplicationLabel(info).toString()
        }.getOrDefault(notification.packageName)

        vault.add(
            LocalNotification(
                packageName = notification.packageName,
                appLabel = appLabel,
                title = title,
                text = text,
                postedAt = notification.postTime,
            ),
        )
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) = Unit
}
