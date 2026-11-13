package com.jarvis.assistant.device

import javax.inject.Inject
import javax.inject.Singleton

data class LocalNotification(
    val packageName: String,
    val appLabel: String,
    val title: String?,
    val text: String?,
    val postedAt: Long,
)

/**
 * In-memory notification buffer.
 *
 * Notification contents are among the most private data on a phone, so JARVIS keeps only
 * a short bounded window in memory, never persists it, never logs it, and never includes
 * it in a Gemini request.
 *
 * Three independent gates apply before anything is stored:
 *  1. the system listener must be connected ([enabled]);
 *  2. the user must have recorded notification consent ([consentGranted]);
 *  3. the app package must not be excluded ([blockedPackages]).
 */
@Singleton
class NotificationVault @Inject constructor() {

    private val items = ArrayDeque<LocalNotification>()

    @Volatile
    var enabled: Boolean = false

    @Volatile
    var consentGranted: Boolean = false

    @Volatile
    var blockedPackages: Set<String> = emptySet()

    @Synchronized
    fun add(notification: LocalNotification) {
        if (!enabled || !consentGranted) return
        if (notification.packageName in blockedPackages) return
        items.addFirst(notification)
        while (items.size > MAX_ITEMS) items.removeLast()
    }

    @Synchronized
    fun recent(limit: Int): List<LocalNotification> = items.take(limit.coerceIn(1, MAX_ITEMS))

    /** Distinct apps seen recently, for the per-app selection in Permission Manager. */
    @Synchronized
    fun recentApps(limit: Int = 12): List<Pair<String, String>> =
        items.map { it.packageName to it.appLabel }
            .distinctBy { it.first }
            .take(limit)

    @Synchronized
    fun clear() = items.clear()

    private companion object {
        const val MAX_ITEMS = 20
    }
}
