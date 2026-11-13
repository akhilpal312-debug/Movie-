package com.jarvis.assistant.core.model

/**
 * Risk is always derived from the *local* action catalog. Any `risk_level` an AI
 * model returns is ignored on purpose, so a compromised or confused model can
 * never downgrade the guardrails that protect destructive actions.
 */
enum class RiskLevel {
    /** Reversible, non-destructive, no private data leaves the device. */
    LOW,

    /** Changes device or user data; allowed after a configured authorisation step. */
    MEDIUM,

    /** Touches private data or communicates on the user's behalf; always confirmed. */
    HIGH,
}

/** Every action JARVIS can execute. Anything not in this list cannot run. */
enum class ActionType(val wire: String, val defaultRisk: RiskLevel) {
    OPEN_APP("OPEN_APP", RiskLevel.LOW),
    OPEN_SETTINGS("OPEN_SETTINGS", RiskLevel.LOW),
    SET_VOLUME("SET_VOLUME", RiskLevel.LOW),
    ADJUST_VOLUME("ADJUST_VOLUME", RiskLevel.LOW),
    SET_BRIGHTNESS("SET_BRIGHTNESS", RiskLevel.MEDIUM),
    SET_DND("SET_DND", RiskLevel.MEDIUM),
    CREATE_ALARM("CREATE_ALARM", RiskLevel.LOW),
    START_TIMER("START_TIMER", RiskLevel.LOW),
    CREATE_REMINDER("CREATE_REMINDER", RiskLevel.LOW),
    ADD_TASK("ADD_TASK", RiskLevel.LOW),
    COMPLETE_TASK("COMPLETE_TASK", RiskLevel.MEDIUM),
    LIST_TASKS("LIST_TASKS", RiskLevel.LOW),
    DEVICE_INFO("DEVICE_INFO", RiskLevel.LOW),
    MEDIA_CONTROL("MEDIA_CONTROL", RiskLevel.LOW),
    READ_NOTIFICATIONS("READ_NOTIFICATIONS", RiskLevel.HIGH),
    FIND_CONTACT("FIND_CONTACT", RiskLevel.MEDIUM),
    CALL_CONTACT("CALL_CONTACT", RiskLevel.HIGH),
    COMPOSE_MESSAGE("COMPOSE_MESSAGE", RiskLevel.HIGH),
    CREATE_ROUTINE("CREATE_ROUTINE", RiskLevel.MEDIUM),
    RUN_ROUTINE("RUN_ROUTINE", RiskLevel.MEDIUM),
    REMEMBER("REMEMBER", RiskLevel.LOW),
    RECALL("RECALL", RiskLevel.LOW),
    ;

    companion object {
        fun fromWire(value: String): ActionType? =
            entries.firstOrNull { it.wire.equals(value.trim(), ignoreCase = true) }
    }
}

enum class SettingsPanel(val key: String) {
    WIFI("WIFI"),
    BLUETOOTH("BLUETOOTH"),
    DATA_USAGE("DATA_USAGE"),
    BATTERY("BATTERY"),
    DISPLAY("DISPLAY"),
    SOUND("SOUND"),
    DND("DND"),
    LOCATION("LOCATION"),
    NOTIFICATIONS("NOTIFICATIONS"),
    AIRPLANE("AIRPLANE"),
    APPS("APPS"),
    PRIVACY("PRIVACY"),
    ;

    companion object {
        fun fromKey(value: String): SettingsPanel? =
            entries.firstOrNull { it.key.equals(value.trim(), ignoreCase = true) }
    }
}

enum class AudioStream(val key: String) {
    MUSIC("MUSIC"),
    RING("RING"),
    ALARM("ALARM"),
    NOTIFICATION("NOTIFICATION"),
    CALL("CALL"),
    SYSTEM("SYSTEM"),
    ;

    companion object {
        fun fromKey(value: String?): AudioStream =
            entries.firstOrNull { it.key.equals(value?.trim(), ignoreCase = true) } ?: MUSIC
    }
}

enum class MediaCommand(val key: String) {
    TOGGLE("TOGGLE"),
    PLAY("PLAY"),
    PAUSE("PAUSE"),
    NEXT("NEXT"),
    PREVIOUS("PREVIOUS"),
    STOP("STOP"),
    ;

    companion object {
        fun fromKey(value: String): MediaCommand? =
            entries.firstOrNull { it.key.equals(value.trim(), ignoreCase = true) }
    }
}

enum class DeviceInfoField(val key: String) {
    BATTERY("BATTERY"),
    STORAGE("STORAGE"),
    MEMORY("MEMORY"),
    NETWORK("NETWORK"),
    MODEL("MODEL"),
    SUMMARY("SUMMARY"),
    ;

    companion object {
        fun fromKey(value: String?): DeviceInfoField =
            entries.firstOrNull { it.key.equals(value?.trim(), ignoreCase = true) } ?: SUMMARY
    }
}

/**
 * The only shape in which an AI model may ask JARVIS to do something.
 *
 * Parameters are typed and validated locally before any Android API is touched.
 * Application lookups take a user-facing label rather than a package name so the
 * installed-app inventory is never sent to the cloud.
 */
sealed interface JarvisAction {
    val type: ActionType
    val riskLevel: RiskLevel get() = type.defaultRisk

    data class OpenApp(val appQuery: String) : JarvisAction {
        override val type = ActionType.OPEN_APP
    }

    data class OpenSettings(val panel: SettingsPanel) : JarvisAction {
        override val type = ActionType.OPEN_SETTINGS
    }

    data class SetVolume(val stream: AudioStream, val percent: Int) : JarvisAction {
        override val type = ActionType.SET_VOLUME
    }

    data class AdjustVolume(val stream: AudioStream, val stepPercent: Int) : JarvisAction {
        override val type = ActionType.ADJUST_VOLUME
    }

    data class SetBrightness(val percent: Int) : JarvisAction {
        override val type = ActionType.SET_BRIGHTNESS
    }

    data class SetDoNotDisturb(val enabled: Boolean) : JarvisAction {
        override val type = ActionType.SET_DND
    }

    data class CreateAlarm(val hour: Int, val minute: Int, val label: String?) : JarvisAction {
        override val type = ActionType.CREATE_ALARM
    }

    data class StartTimer(val durationSeconds: Int, val label: String?) : JarvisAction {
        override val type = ActionType.START_TIMER
    }

    data class CreateReminder(val message: String, val triggerAtMillis: Long) : JarvisAction {
        override val type = ActionType.CREATE_REMINDER
    }

    data class AddTask(val title: String, val dueAtMillis: Long?) : JarvisAction {
        override val type = ActionType.ADD_TASK
    }

    data class CompleteTask(val query: String) : JarvisAction {
        override val type = ActionType.COMPLETE_TASK
    }

    data object ListTasks : JarvisAction {
        override val type = ActionType.LIST_TASKS
    }

    data class DeviceInfo(val field: DeviceInfoField) : JarvisAction {
        override val type = ActionType.DEVICE_INFO
    }

    data class MediaControl(val command: MediaCommand) : JarvisAction {
        override val type = ActionType.MEDIA_CONTROL
    }

    data class ReadNotifications(val limit: Int) : JarvisAction {
        override val type = ActionType.READ_NOTIFICATIONS
    }

    data class FindContact(val query: String) : JarvisAction {
        override val type = ActionType.FIND_CONTACT
    }

    data class CallContact(val contactQuery: String) : JarvisAction {
        override val type = ActionType.CALL_CONTACT
    }

    data class ComposeMessage(val contactQuery: String, val body: String) : JarvisAction {
        override val type = ActionType.COMPOSE_MESSAGE
    }

    data class CreateRoutine(
        val name: String,
        val triggerHour: Int,
        val triggerMinute: Int,
        val steps: List<String>,
    ) : JarvisAction {
        override val type = ActionType.CREATE_ROUTINE
    }

    data class RunRoutine(val name: String) : JarvisAction {
        override val type = ActionType.RUN_ROUTINE
    }

    data class Remember(val text: String) : JarvisAction {
        override val type = ActionType.REMEMBER
    }

    data class Recall(val query: String) : JarvisAction {
        override val type = ActionType.RECALL
    }
}

/** Where a request originated. Voice and automation have different trust levels. */
enum class ActionSource {
    VOICE_GEMINI,
    TEXT_GEMINI,
    LOCAL_PARSER,
    AUTOMATION,
    UI,
}
