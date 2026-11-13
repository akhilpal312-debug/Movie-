package com.jarvis.assistant.device

import android.content.Context
import android.provider.Settings
import com.jarvis.assistant.core.model.ExecutionResult
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BrightnessController @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun setBrightness(percent: Int): ExecutionResult {
        if (!Settings.System.canWrite(context)) {
            return ExecutionResult.Unsupported(
                "Screen brightness needs the Modify system settings access, which is not granted.",
            )
        }
        val clamped = percent.coerceIn(0, 100)
        // Keep a floor so the screen never becomes unreadable at 0%.
        val value = (clamped * 255 / 100).coerceAtLeast(8)
        return runCatching {
            Settings.System.putInt(
                context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL,
            )
            Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, value)
            ExecutionResult.Success("Brightness set to $clamped%.")
        }.getOrElse {
            ExecutionResult.Failed("Android refused the brightness change.")
        }
    }
}
