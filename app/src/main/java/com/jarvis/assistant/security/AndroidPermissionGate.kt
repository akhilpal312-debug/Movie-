package com.jarvis.assistant.security

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.jarvis.assistant.core.model.PermissionKey
import com.jarvis.assistant.permissions.PermissionRegistry
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads the real grant state from Android. Nothing here caches or assumes a grant:
 * a permission revoked in system settings takes effect on the next action.
 */
@Singleton
class AndroidPermissionGate @Inject constructor(
    @ApplicationContext private val context: Context,
) : PermissionGate {

    override fun isGranted(key: PermissionKey): Boolean = when (key) {
        PermissionKey.MICROPHONE -> hasRuntime(Manifest.permission.RECORD_AUDIO)
        PermissionKey.CONTACTS -> hasRuntime(Manifest.permission.READ_CONTACTS)
        PermissionKey.NOTIFICATIONS -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            hasRuntime(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            true
        }
        PermissionKey.NETWORK -> true
        PermissionKey.NOTIFICATION_LISTENER -> PermissionRegistry.hasNotificationListenerAccess(context)
        PermissionKey.WRITE_SETTINGS -> Settings.System.canWrite(context)
        PermissionKey.DO_NOT_DISTURB -> notificationManager().isNotificationPolicyAccessGranted
        PermissionKey.EXACT_ALARMS -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            alarmManager().canScheduleExactAlarms()
        } else {
            true
        }
        PermissionKey.ASSISTANT_ROLE -> hasAssistantRole()
        PermissionKey.BATTERY_UNRESTRICTED ->
            powerManager().isIgnoringBatteryOptimizations(context.packageName)
    }

    private fun hasRuntime(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private fun hasAssistantRole(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        val roleManager = context.getSystemService(android.app.role.RoleManager::class.java) ?: return false
        return roleManager.isRoleHeld(android.app.role.RoleManager.ROLE_ASSISTANT)
    }

    private fun notificationManager(): NotificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun alarmManager(): AlarmManager =
        context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    private fun powerManager(): PowerManager =
        context.getSystemService(Context.POWER_SERVICE) as PowerManager

    @Suppress("unused")
    private fun devicePolicyManager(): DevicePolicyManager? =
        context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
}
