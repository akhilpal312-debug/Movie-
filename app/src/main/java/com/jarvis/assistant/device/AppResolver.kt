package com.jarvis.assistant.device

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

data class AppEntry(val label: String, val packageName: String)

/**
 * Application lookup.
 *
 * The model is only ever told the name the user spoke; JARVIS resolves it here and
 * keeps the installed-app inventory on the device. That is why no action accepts a
 * package name from the cloud.
 */
@Singleton
class AppResolver @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    @Volatile
    private var cached: List<AppEntry> = emptyList()

    @Volatile
    private var cachedAt = 0L

    fun launchableApps(forceReload: Boolean = false): List<AppEntry> {
        val fresh = System.currentTimeMillis() - cachedAt < 60_000
        if (!forceReload && fresh && cached.isNotEmpty()) return cached

        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved: List<ResolveInfo> = if (android.os.Build.VERSION.SDK_INT >= 33) {
            context.packageManager.queryIntentActivities(
                intent,
                PackageManager.ResolveInfoFlags.of(0L),
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.queryIntentActivities(intent, 0)
        }

        cached = resolved
            .mapNotNull { info ->
                val activity = info.activityInfo ?: return@mapNotNull null
                val label = info.loadLabel(context.packageManager).toString()
                if (label.isBlank()) null else AppEntry(label, activity.packageName)
            }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase(Locale.ROOT) }
        cachedAt = System.currentTimeMillis()
        return cached
    }

    /** Best local match for a spoken app name; null means "not installed". */
    fun resolve(query: String): AppEntry? {
        val needle = query.trim().lowercase(Locale.ROOT)
        if (needle.isEmpty()) return null
        val apps = launchableApps()

        apps.firstOrNull { it.packageName.equals(needle, ignoreCase = true) }?.let { return it }
        apps.firstOrNull { it.label.lowercase(Locale.ROOT) == needle }?.let { return it }
        apps.firstOrNull { it.label.lowercase(Locale.ROOT).startsWith(needle) }?.let { return it }
        apps.firstOrNull { it.label.lowercase(Locale.ROOT).contains(needle) }?.let { return it }
        // "whatsapp business" style queries: require every word to appear.
        val words = needle.split(' ').filter { it.length > 1 }
        return apps.firstOrNull { app ->
            val label = app.label.lowercase(Locale.ROOT)
            words.isNotEmpty() && words.all { label.contains(it) }
        }
    }

    fun launch(packageName: String): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching {
            context.startActivity(intent)
            true
        }.getOrDefault(false)
    }

    fun openAppSettings(packageName: String): Boolean = runCatching {
        context.startActivity(
            Intent(
                android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                android.net.Uri.parse("package:$packageName"),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        true
    }.getOrDefault(false)
}
