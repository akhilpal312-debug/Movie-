package com.jarvis.assistant.device

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.AlarmClock
import com.jarvis.assistant.core.model.ExecutionResult
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Uses the public AlarmClock and telephony intents. JARVIS never calls or messages
 * anyone itself: it hands the number and draft to the user's own app, so the final
 * action stays in the user's hands and needs no call/SMS permission.
 */
@Singleton
class SystemIntentsController @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun createAlarm(hour: Int, minute: Int, label: String?): ExecutionResult {
        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, hour)
            putExtra(AlarmClock.EXTRA_MINUTES, minute)
            // Show the clock app so the user sees exactly what was created.
            putExtra(AlarmClock.EXTRA_SKIP_UI, false)
            label?.let { putExtra(AlarmClock.EXTRA_MESSAGE, it) }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return launch(intent, "Alarm set for %02d:%02d.".format(hour, minute), "clock app")
    }

    fun startTimer(seconds: Int, label: String?): ExecutionResult {
        val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
            putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            putExtra(AlarmClock.EXTRA_SKIP_UI, false)
            label?.let { putExtra(AlarmClock.EXTRA_MESSAGE, it) }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val readable = when {
            seconds % 3600 == 0 -> "${seconds / 3600} hour"
            seconds % 60 == 0 -> "${seconds / 60} minute"
            else -> "$seconds second"
        }
        return launch(intent, "Timer started for $readable.", "clock app")
    }

    fun dial(number: String, contactName: String): ExecutionResult {
        val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:${sanitizeNumber(number)}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return launch(intent, "Dialer opened for $contactName. Press call to connect.", "dialer")
    }

    fun composeMessage(number: String, body: String, contactName: String): ExecutionResult {
        val uri = Uri.parse("smsto:${sanitizeNumber(number)}")
        val intent = Intent(Intent.ACTION_SENDTO, uri).apply {
            putExtra("sms_body", body)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val resolved = intent.resolveActivity(context.packageManager)
            ?: return ExecutionResult.Unsupported("This device has no SMS app installed.")
        return runCatching {
            context.startActivity(intent)
            ExecutionResult.Success("Draft for $contactName is ready. Nothing has been sent.")
        }.getOrElse { ExecutionResult.Failed("Could not open the messaging app.") }
    }

    private fun launch(intent: Intent, successMessage: String, target: String): ExecutionResult {
        intent.resolveActivity(context.packageManager)
            ?: return ExecutionResult.Unsupported("This device has no $target available.")
        return runCatching {
            context.startActivity(intent)
            ExecutionResult.Success(successMessage)
        }.getOrElse { ExecutionResult.Failed("Could not open the $target.") }
    }

    /** Keeps only characters valid in a tel: URI so nothing can be smuggled through. */
    private fun sanitizeNumber(raw: String): String =
        raw.filter { it.isDigit() || it == '+' || it == '*' || it == '#' || it == ',' }
}
