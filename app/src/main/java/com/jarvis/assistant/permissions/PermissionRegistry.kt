package com.jarvis.assistant.permissions

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import com.jarvis.assistant.core.model.PermissionKey

/** One row of the permission dashboard. */
data class PermissionDescriptor(
    val key: PermissionKey,
    val title: String,
    val rationale: String,
    val consequenceIfDenied: String,
    val requiredForOnboarding: Boolean,
)

/**
 * Explanations and destinations for every permission JARVIS can ask for. Android
 * decides whether a permission or special access is granted; this class never
 * assumes a grant and never routes the user somewhere that would bypass a system
 * confirmation screen.
 */
object PermissionRegistry {

    val descriptors: List<PermissionDescriptor> = listOf(
        PermissionDescriptor(
            PermissionKey.MICROPHONE,
            "Microphone",
            "Records only while you hold the voice button, then streams that audio to Gemini Live.",
            "Voice conversation is unavailable; typed commands still work.",
            requiredForOnboarding = true,
        ),
        PermissionDescriptor(
            PermissionKey.NOTIFICATIONS,
            "Notifications",
            "Shows the required ongoing indicator while JARVIS is listening, and delivers your reminders.",
            "Reminders and the listening indicator cannot be shown.",
            requiredForOnboarding = true,
        ),
        PermissionDescriptor(
            PermissionKey.ASSISTANT_ROLE,
            "Default assistant role",
            "Lets the system open JARVIS with the assistant gesture or long-press on the home button.",
            "You can still open the app normally.",
            requiredForOnboarding = false,
        ),
        PermissionDescriptor(
            PermissionKey.EXACT_ALARMS,
            "Exact alarms",
            "Fires your reminders at the exact time you asked for instead of within a window.",
            "Reminders may be delivered a few minutes late.",
            requiredForOnboarding = false,
        ),
        PermissionDescriptor(
            PermissionKey.NOTIFICATION_LISTENER,
            "Notification access",
            "Allows JARVIS to read notifications, only after you switch this on here. Contents stay on device.",
            "JARVIS cannot summarise your notifications.",
            requiredForOnboarding = false,
        ),
        PermissionDescriptor(
            PermissionKey.WRITE_SETTINGS,
            "Modify system settings",
            "Needed to change screen brightness from a voice command.",
            "Brightness commands are refused; the settings screen can still be opened.",
            requiredForOnboarding = false,
        ),
        PermissionDescriptor(
            PermissionKey.DO_NOT_DISTURB,
            "Do Not Disturb access",
            "Needed to switch Do Not Disturb on or off.",
            "DND commands are refused.",
            requiredForOnboarding = false,
        ),
        PermissionDescriptor(
            PermissionKey.CONTACTS,
            "Contacts",
            "Only read when you ask JARVIS to call or message someone. Never uploaded and never cached in the memory database.",
            "Call and message commands are refused.",
            requiredForOnboarding = false,
        ),
        PermissionDescriptor(
            PermissionKey.BATTERY_UNRESTRICTED,
            "Unrestricted battery use",
            "Keeps scheduled routines reliable instead of being deferred by battery optimisation.",
            "Routines may run late when the device is idle.",
            requiredForOnboarding = false,
        ),
    )

    fun descriptor(key: PermissionKey): PermissionDescriptor? = descriptors.firstOrNull { it.key == key }

    /** Deep link to the exact system screen that owns this grant. */
    fun settingsIntent(context: Context, key: PermissionKey): Intent = when (key) {
        PermissionKey.MICROPHONE, PermissionKey.CONTACTS -> Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:${context.packageName}"),
        )
        PermissionKey.NOTIFICATIONS -> Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        }
        PermissionKey.NOTIFICATION_LISTENER ->
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        PermissionKey.WRITE_SETTINGS ->
            Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${context.packageName}"))
        PermissionKey.DO_NOT_DISTURB -> Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
        PermissionKey.EXACT_ALARMS -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}"))
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
        }
        PermissionKey.ASSISTANT_ROLE -> Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)
        PermissionKey.BATTERY_UNRESTRICTED -> Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        PermissionKey.NETWORK -> Intent(Settings.ACTION_WIRELESS_SETTINGS)
    }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** True when the notification listener is bound for this package. */
    fun hasNotificationListenerAccess(context: Context): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
}
