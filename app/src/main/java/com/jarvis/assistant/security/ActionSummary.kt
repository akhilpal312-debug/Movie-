package com.jarvis.assistant.security

import com.jarvis.assistant.core.model.JarvisAction
import java.security.MessageDigest

/**
 * Descriptions used in confirmation sheets, spoken replies and the audit log.
 * They intentionally contain no private data beyond what the user just asked for.
 */
object ActionSummary {

    fun describe(action: JarvisAction): String = when (action) {
        is JarvisAction.OpenApp -> "Open ${action.appQuery}"
        is JarvisAction.OpenSettings -> "Open ${panelName(action.panel.key)} settings"
        is JarvisAction.SetVolume -> "Set ${action.stream.key.lowercase()} volume to ${action.percent}%"
        is JarvisAction.AdjustVolume ->
            if (action.stepPercent >= 0) {
                "Turn ${action.stream.key.lowercase()} volume up by ${action.stepPercent}%"
            } else {
                "Turn ${action.stream.key.lowercase()} volume down by ${-action.stepPercent}%"
            }
        is JarvisAction.SetBrightness -> "Set brightness to ${action.percent}%"
        is JarvisAction.SetDoNotDisturb ->
            if (action.enabled) "Turn Do Not Disturb on" else "Turn Do Not Disturb off"
        is JarvisAction.CreateAlarm ->
            "Set an alarm for %02d:%02d".format(action.hour, action.minute) +
                (action.label?.let { " ($it)" } ?: "")
        is JarvisAction.StartTimer -> "Start a ${formatDuration(action.durationSeconds)} timer"
        is JarvisAction.CreateReminder -> "Remind me: ${action.message}"
        is JarvisAction.AddTask -> "Add task: ${action.title}"
        is JarvisAction.CompleteTask -> "Mark task done: ${action.query}"
        JarvisAction.ListTasks -> "List my tasks"
        is JarvisAction.DeviceInfo -> "Read ${action.field.key.lowercase()} information"
        is JarvisAction.MediaControl -> "Media ${action.command.key.lowercase()}"
        is JarvisAction.ReadNotifications -> "Read my last ${action.limit} notifications"
        is JarvisAction.FindContact -> "Find contact: ${action.query}"
        is JarvisAction.CallContact -> "Call ${action.contactQuery}"
        is JarvisAction.ComposeMessage -> "Draft a message to ${action.contactQuery}"
        is JarvisAction.CreateRoutine ->
            "Schedule \"${action.name}\" daily at %02d:%02d".format(action.triggerHour, action.triggerMinute)
        is JarvisAction.RunRoutine -> "Run routine \"${action.name}\""
        is JarvisAction.Remember -> "Remember: ${action.text}"
        is JarvisAction.Recall -> "Search memory for: ${action.query}"
    }

    /**
     * Stable identifier of the exact action instance the user approved.
     * Recomputed by the executor before running, so a mutated action is refused.
     */
    fun fingerprint(action: JarvisAction): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(action.toString().toByteArray())
        return digest.joinToString("") { "%02x".format(it) }.take(32)
    }

    private fun panelName(key: String): String = when (key) {
        "WIFI" -> "Wi-Fi"
        "BLUETOOTH" -> "Bluetooth"
        "DATA_USAGE" -> "data usage"
        "DND" -> "Do Not Disturb"
        "APPS" -> "apps"
        else -> key.lowercase()
    }

    private fun formatDuration(seconds: Int): String = when {
        seconds % 3600 == 0 -> "${seconds / 3600} hour${if (seconds == 3600) "" else "s"}"
        seconds % 60 == 0 -> "${seconds / 60} minute${if (seconds == 60) "" else "s"}"
        else -> "$seconds second${if (seconds == 1) "" else "s"}"
    }
}
