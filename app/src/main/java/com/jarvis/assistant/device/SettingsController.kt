package com.jarvis.assistant.device

import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.jarvis.assistant.core.model.ExecutionResult
import com.jarvis.assistant.core.model.SettingsPanel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Opens system settings panels. JARVIS never toggles Wi-Fi, Bluetooth or mobile data
 * itself: those are protected settings that a normal app cannot change, and Android
 * only allows opening the panel where the user makes the choice.
 */
@Singleton
class SettingsController @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun open(panel: SettingsPanel): ExecutionResult {
        val intent = intentFor(panel)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val resolved = intent.resolveActivity(context.packageManager)
            ?: return ExecutionResult.Unsupported(
                "This device has no settings screen for ${panel.name.lowercase().replace('_', ' ')}.",
            )
        return runCatching {
            context.startActivity(intent)
            ExecutionResult.Success("Opened ${panel.name.lowercase().replace('_', ' ')} settings.")
        }.getOrElse {
            ExecutionResult.Failed("Could not open that settings panel.")
        }
    }

    private fun intentFor(panel: SettingsPanel): Intent = when (panel) {
        SettingsPanel.WIFI -> Intent(Settings.ACTION_WIFI_SETTINGS)
        SettingsPanel.BLUETOOTH -> Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
        SettingsPanel.DATA_USAGE -> Intent(Settings.ACTION_DATA_USAGE_SETTINGS)
        SettingsPanel.BATTERY -> Intent(Intent.ACTION_POWER_USAGE_SUMMARY)
        SettingsPanel.DISPLAY -> Intent(Settings.ACTION_DISPLAY_SETTINGS)
        SettingsPanel.SOUND -> Intent(Settings.ACTION_SOUND_SETTINGS)
        SettingsPanel.DND -> Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
        SettingsPanel.LOCATION -> Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
        SettingsPanel.NOTIFICATIONS -> Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        }
        SettingsPanel.AIRPLANE -> Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS)
        SettingsPanel.APPS -> Intent(Settings.ACTION_APPLICATION_SETTINGS)
        SettingsPanel.PRIVACY -> Intent(Settings.ACTION_PRIVACY_SETTINGS)
    }
}
