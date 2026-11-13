package com.jarvis.assistant.ai

import com.jarvis.assistant.core.model.ActionType
import com.jarvis.assistant.core.model.AudioStream
import com.jarvis.assistant.core.model.DeviceInfoField
import com.jarvis.assistant.core.model.JarvisAction
import com.jarvis.assistant.core.model.MediaCommand
import com.jarvis.assistant.core.model.SettingsPanel
import com.jarvis.assistant.security.ParseResult
import com.jarvis.assistant.security.RejectionCode
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * LOCAL PRIVACY MODE command parser.
 *
 * Understands a deliberately bounded set of English, Hindi and Hinglish phrasings
 * completely offline. Anything outside that set is rejected instead of guessed, so
 * local mode can never accidentally reach the network or run the wrong action.
 */
@Singleton
class LocalCommandParser @Inject constructor() {

    fun parse(raw: String): ParseResult {
        val text = raw.trim().lowercase(Locale.ROOT)
        if (text.isEmpty()) return reject(RejectionCode.MALFORMED, "Nothing to do")

        settingsIntent(text)?.let { return ParseResult.Parsed(it) }
        openAppIntent(text)?.let { return ParseResult.Parsed(it) }
        volumeIntent(text)?.let { return ParseResult.Parsed(it) }
        brightnessIntent(text)?.let { return ParseResult.Parsed(it) }
        dndIntent(text)?.let { return ParseResult.Parsed(it) }
        alarmIntent(text)?.let { return ParseResult.Parsed(it) }
        timerIntent(text)?.let { return ParseResult.Parsed(it) }
        mediaIntent(text)?.let { return ParseResult.Parsed(it) }
        taskIntent(text)?.let { return ParseResult.Parsed(it) }
        memoryIntent(text)?.let { return ParseResult.Parsed(it) }
        callIntent(text)?.let { return ParseResult.Parsed(it) }
        deviceInfoIntent(text)?.let { return ParseResult.Parsed(it) }

        return reject(
            RejectionCode.UNKNOWN_ACTION,
            "Local mode does not understand that command. Try \"open camera\", \"timer 5 minutes\" or \"battery\".",
        )
    }

    // ---------------------------------------------------------------- settings

    private fun settingsIntent(text: String): JarvisAction? {
        val panel = when {
            text.containsAny("wifi", "wi-fi", "वाई") -> SettingsPanel.WIFI
            text.containsAny("bluetooth", "ब्लूटूथ") -> SettingsPanel.BLUETOOTH
            text.containsAny("battery settings", "battery setting") -> SettingsPanel.BATTERY
            text.containsAny("display", "screen settings") -> SettingsPanel.DISPLAY
            text.containsAny("sound settings", "audio settings") -> SettingsPanel.SOUND
            text.containsAny("dnd settings") -> SettingsPanel.DND
            text.containsAny("location settings", "gps settings", "location") -> SettingsPanel.LOCATION
            text.containsAny("data usage", "mobile data") -> SettingsPanel.DATA_USAGE
            text.containsAny("notification settings") -> SettingsPanel.NOTIFICATIONS
            text.containsAny("airplane", "flight mode") -> SettingsPanel.AIRPLANE
            else -> null
        } ?: return null
        val isSettingsRequest = text.containsAny("setting", "settings", "kholo", "open", "khol do")
        return if (isSettingsRequest) JarvisAction.OpenSettings(panel) else null
    }

    // ------------------------------------------------------------------- apps

    private val openCmd = Regex("^(open|launch|start|chalu karo|kholo|khol do)\\s+(.+)$")
    private val openSuffix = Regex("^(.+?)\\s+(kholo|khol do|open karo|open kro|chalu karo|launch karo)$")

    private fun openAppIntent(text: String): JarvisAction? {
        val candidate = openCmd.find(text)?.groupValues?.get(2)
            ?: openSuffix.find(text)?.groupValues?.get(1)
            ?: return null
        val app = candidate
            .replace(Regex("\\b(app|application|ko)\\b"), " ")
            .trim()
            .trim(',', '.', '!', '?')
        if (app.isBlank()) return null
        return JarvisAction.OpenApp(app)
    }

    // ----------------------------------------------------------------- volume

    private fun volumeIntent(text: String): JarvisAction? {
        if (text.containsAny("mute", "mute karo")) return JarvisAction.SetVolume(AudioStream.MUSIC, 0)
        if (!text.containsAny("volume", "awaaz", "awaz", "sound", "आवाज़")) return null
        Regex("(?:volume|awaaz|awaz|sound)\\D{0,10}(\\d{1,3})").find(text)?.let { match ->
            val percent = match.groupValues[1].toIntOrNull() ?: return null
            if (percent !in 0..100) return null
            return JarvisAction.SetVolume(AudioStream.MUSIC, percent)
        }
        return when {
            text.containsAny("volume up", "louder", "badhao", "badha do") -> JarvisAction.AdjustVolume(AudioStream.MUSIC, 10)
            text.containsAny("volume down", "quieter", "kam karo", "kam kar do") -> JarvisAction.AdjustVolume(AudioStream.MUSIC, -10)
            else -> null
        }
    }

    private fun brightnessIntent(text: String): JarvisAction? {
        if (!text.containsAny("brightness", "screen light", "roshni")) return null
        val percent = Regex("(\\d{1,3})").find(text)?.groupValues?.get(1)?.toIntOrNull() ?: return null
        if (percent !in 0..100) return null
        return JarvisAction.SetBrightness(percent)
    }

    private fun dndIntent(text: String): JarvisAction? {
        if (!text.containsAny("do not disturb", "dnd", "silent mode")) return null
        return when {
            text.containsAny("off", "band", "disable") -> JarvisAction.SetDoNotDisturb(false)
            text.containsAny("on", "chalu", "enable", "lagao") -> JarvisAction.SetDoNotDisturb(true)
            else -> null
        }
    }

    // ----------------------------------------------------------------- alarms

    private fun alarmIntent(text: String): JarvisAction? {
        if (!text.containsAny("alarm", "अलार्म")) return null
        val colon = Regex("(\\d{1,2})[:.]?(\\d{2})?").find(text) ?: return null
        var hour = colon.groupValues[1].toIntOrNull() ?: return null
        val minute = colon.groupValues[2].toIntOrNull() ?: 0
        if (text.containsAny("pm", "shaam", "sham", "raat", "evening") && hour in 1..11) hour += 12
        if (text.containsAny("am", "subah", "morning") && hour == 12) hour = 0
        if (hour !in 0..23 || minute !in 0..59) return null
        return JarvisAction.CreateAlarm(hour, minute, label = null)
    }

    private fun timerIntent(text: String): JarvisAction? {
        if (!text.containsAny("timer", "टाइमर")) return null
        val match = Regex("(\\d{1,4})\\s*(second|sec|minute|min|hour|ghanta|ghante)").find(text) ?: return null
        val amount = match.groupValues[1].toIntOrNull() ?: return null
        val seconds = when {
            match.groupValues[2].startsWith("h") || match.groupValues[2].startsWith("ghant") -> amount * 3600
            match.groupValues[2].startsWith("m") -> amount * 60
            else -> amount
        }
        if (seconds !in 1..86_400) return null
        return JarvisAction.StartTimer(seconds, label = null)
    }

    // ------------------------------------------------------------------ media

    private fun mediaIntent(text: String): JarvisAction? {
        if (text.containsAny("pause", "rok do", "band karo")) {
            if (text.containsAny("music", "song", "gana", "media", "pause")) {
                return JarvisAction.MediaControl(MediaCommand.PAUSE)
            }
        }
        if (text.containsAny("next song", "next track", "agla gana", "skip")) {
            return JarvisAction.MediaControl(MediaCommand.NEXT)
        }
        if (text.containsAny("previous song", "previous track", "pichla gana")) {
            return JarvisAction.MediaControl(MediaCommand.PREVIOUS)
        }
        if (text.containsAny("play music", "resume music", "gana chalao", "music chalao", "play song")) {
            return JarvisAction.MediaControl(MediaCommand.PLAY)
        }
        return null
    }

    // ------------------------------------------------------------------ tasks

    private fun taskIntent(text: String): JarvisAction? {
        when {
            text.containsAny("list tasks", "show tasks", "my tasks", "task list", "tasks batao") ->
                return JarvisAction.ListTasks
            text.containsAny("add task", "naya task", "task add karo") -> {
                val title = text
                    .replace(Regex("\\b(add task|naya task|task add karo|task add)\\b"), "")
                    .trim()
                if (title.isBlank()) return null
                return JarvisAction.AddTask(title, null)
            }
            text.containsAny("complete task", "task done", "task khatam") -> {
                val query = text
                    .replace(Regex("\\b(complete task|task done|task khatam|karo|kar do)\\b"), "")
                    .trim()
                if (query.isBlank()) return null
                return JarvisAction.CompleteTask(query)
            }
        }
        return null
    }

    // ----------------------------------------------------------------- memory

    private fun memoryIntent(text: String): JarvisAction? {
        Regex("^(?:remember|yaad rakho)\\s+(.+)$").find(text)?.let { match ->
            val fact = match.groupValues[1].trim()
            if (fact.isNotEmpty()) return JarvisAction.Remember(fact)
        }
        Regex("^(?:recall|what do you remember about|yaad kya hai)\\s+(.+)$").find(text)?.let { match ->
            val query = match.groupValues[1].trim()
            if (query.isNotEmpty()) return JarvisAction.Recall(query)
        }
        return null
    }

    // -------------------------------------------------------------- contacts

    private fun callIntent(text: String): JarvisAction? {
        Regex("^(?:call|phone)\\s+(.+)$").find(text)?.let { match ->
            val name = match.groupValues[1].trim()
            if (name.isNotEmpty()) return JarvisAction.CallContact(name)
        }
        Regex("^(.+?)\\s+ko call karo$").find(text)?.let { match ->
            val name = match.groupValues[1].trim()
            if (name.isNotEmpty()) return JarvisAction.CallContact(name)
        }
        return null
    }

    // ---------------------------------------------------------- device status

    private fun deviceInfoIntent(text: String): JarvisAction? = when {
        text.containsAny("battery", "charge", "बैटरी") -> JarvisAction.DeviceInfo(DeviceInfoField.BATTERY)
        text.containsAny("storage", "space", "jagah") -> JarvisAction.DeviceInfo(DeviceInfoField.STORAGE)
        text.containsAny("memory", "ram") -> JarvisAction.DeviceInfo(DeviceInfoField.MEMORY)
        text.containsAny("network", "internet", "connection") -> JarvisAction.DeviceInfo(DeviceInfoField.NETWORK)
        text.containsAny("device model", "android version", "phone model") -> JarvisAction.DeviceInfo(DeviceInfoField.MODEL)
        text.containsAny("device status", "device info", "phone status") -> JarvisAction.DeviceInfo(DeviceInfoField.SUMMARY)
        else -> null
    }

    private fun String.containsAny(vararg needles: String): Boolean = needles.any { contains(it) }

    private fun reject(code: RejectionCode, reason: String) = ParseResult.Rejected(code, reason)

    /** Exposed for the UI hint chip so local mode advertises only what it can do. */
    val supportedExamples: List<String> = listOf(
        "open camera",
        "whatsapp kholo",
        "set volume to 40",
        "timer 10 minutes",
        "alarm 7:30",
        "battery batao",
        "add task pay rent",
        "yaad rakho I prefer filter coffee",
    )

    val supportedActionTypes: Set<ActionType> = setOf(
        ActionType.OPEN_APP,
        ActionType.OPEN_SETTINGS,
        ActionType.SET_VOLUME,
        ActionType.ADJUST_VOLUME,
        ActionType.SET_BRIGHTNESS,
        ActionType.SET_DND,
        ActionType.CREATE_ALARM,
        ActionType.START_TIMER,
        ActionType.MEDIA_CONTROL,
        ActionType.ADD_TASK,
        ActionType.COMPLETE_TASK,
        ActionType.LIST_TASKS,
        ActionType.REMEMBER,
        ActionType.RECALL,
        ActionType.CALL_CONTACT,
        ActionType.DEVICE_INFO,
    )
}
