package com.jarvis.assistant.assistant

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Assistant role integration.
 *
 * Android owns this decision. JARVIS can only *ask* the system to show the role
 * request screen; it cannot become the default assistant by being installed, and it
 * never claims otherwise.
 */
@Singleton
class AssistantRoleManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    val isSupported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    fun isHeld(): Boolean {
        if (!isSupported) return false
        val roleManager = roleManager() ?: return false
        return roleManager.isRoleHeld(RoleManager.ROLE_ASSISTANT)
    }

    fun isAvailable(): Boolean {
        if (!isSupported) return false
        val roleManager = roleManager() ?: return false
        return roleManager.isRoleAvailable(RoleManager.ROLE_ASSISTANT)
    }

    /**
     * Intent for the system role-request dialog, or null when the platform has no
     * RoleManager assistant role (Android 9 and below).
     */
    fun requestRoleIntent(): Intent? {
        if (!isSupported || !isAvailable()) return null
        val roleManager = roleManager() ?: return null
        if (isHeld()) return null
        return roleManager.createRequestRoleIntent(RoleManager.ROLE_ASSISTANT)
    }

    /** Fallback route on older devices: the system's own assistant selection screen. */
    fun openAssistantSettings(): Boolean = runCatching {
        context.startActivity(
            Intent(Settings.ACTION_VOICE_INPUT_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        true
    }.getOrDefault(false)

    private fun roleManager(): RoleManager? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            context.getSystemService(RoleManager::class.java)
        } else {
            null
        }
}
