package com.jarvis.assistant.security

import com.jarvis.assistant.core.model.ActionType
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/** Declarative parameter description, used for validation and for the model schema. */
data class ParamSpec(
    val name: String,
    val description: String,
    val jsonType: String = "STRING",
    val required: Boolean = true,
    val enumValues: List<String>? = null,
    val minimum: Int? = null,
    val maximum: Int? = null,
)

data class ActionSpec(
    val type: ActionType,
    val description: String,
    val params: List<ParamSpec> = emptyList(),
)

/**
 * The complete list of actions JARVIS will accept, with the exact parameter names
 * the model may use. This list is both the validation contract and the definition
 * published to Gemini as function declarations, so the two cannot drift apart.
 */
object ActionSchema {

    val specs: List<ActionSpec> = listOf(
        ActionSpec(
            ActionType.OPEN_APP,
            "Open an installed application by the name the user said (e.g. whatsapp, camera, settings).",
            listOf(ParamSpec("app", "Application name or label as spoken by the user.", maximum = 80)),
        ),
        ActionSpec(
            ActionType.OPEN_SETTINGS,
            "Open a system settings panel such as Wi-Fi, Bluetooth, battery or display.",
            listOf(
                ParamSpec(
                    "panel", "Settings panel to open.", enumValues = listOf(
                        "WIFI", "BLUETOOTH", "DATA_USAGE", "BATTERY", "DISPLAY", "SOUND",
                        "DND", "LOCATION", "NOTIFICATIONS", "AIRPLANE", "APPS", "PRIVACY",
                    ),
                ),
            ),
        ),
        ActionSpec(
            ActionType.SET_VOLUME,
            "Set a volume stream to an absolute percentage from 0 to 100.",
            listOf(
                ParamSpec("percent", "Target volume percentage.", jsonType = "INTEGER", minimum = 0, maximum = 100),
                ParamSpec(
                    "stream", "Audio stream, defaults to MUSIC.", required = false,
                    enumValues = listOf("MUSIC", "RING", "ALARM", "NOTIFICATION", "CALL", "SYSTEM"),
                ),
            ),
        ),
        ActionSpec(
            ActionType.ADJUST_VOLUME,
            "Change a volume stream by a relative amount, for example louder by 10 percent.",
            listOf(
                ParamSpec("step", "Relative change in percentage points, negative is quieter.", jsonType = "INTEGER", minimum = -100, maximum = 100),
                ParamSpec(
                    "stream", "Audio stream, defaults to MUSIC.", required = false,
                    enumValues = listOf("MUSIC", "RING", "ALARM", "NOTIFICATION", "CALL", "SYSTEM"),
                ),
            ),
        ),
        ActionSpec(
            ActionType.SET_BRIGHTNESS,
            "Set screen brightness from 0 to 100 percent. Requires the write-settings special access.",
            listOf(ParamSpec("percent", "Brightness percentage.", jsonType = "INTEGER", minimum = 0, maximum = 100)),
        ),
        ActionSpec(
            ActionType.SET_DND,
            "Turn Do Not Disturb on or off. Requires notification policy access.",
            listOf(ParamSpec("enabled", "True to enable Do Not Disturb.", jsonType = "BOOLEAN")),
        ),
        ActionSpec(
            ActionType.CREATE_ALARM,
            "Create a system alarm through the clock app.",
            listOf(
                ParamSpec("hour", "Hour in 24-hour time.", jsonType = "INTEGER", minimum = 0, maximum = 23),
                ParamSpec("minute", "Minute.", jsonType = "INTEGER", minimum = 0, maximum = 59),
                ParamSpec("label", "Optional alarm label.", required = false, maximum = 80),
            ),
        ),
        ActionSpec(
            ActionType.START_TIMER,
            "Start a countdown timer.",
            listOf(
                ParamSpec("seconds", "Timer duration in seconds.", jsonType = "INTEGER", minimum = 1, maximum = 86400),
                ParamSpec("label", "Optional timer label.", required = false, maximum = 80),
            ),
        ),
        ActionSpec(
            ActionType.CREATE_REMINDER,
            "Schedule a local reminder notification at a specific time.",
            listOf(
                ParamSpec("message", "What to remind the user about.", maximum = 200),
                ParamSpec("epoch_ms", "Absolute trigger time in epoch milliseconds.", jsonType = "INTEGER"),
            ),
        ),
        ActionSpec(
            ActionType.ADD_TASK,
            "Add an item to the user's local task list.",
            listOf(
                ParamSpec("title", "Task title.", maximum = 160),
                ParamSpec("due_epoch_ms", "Optional due time in epoch milliseconds.", jsonType = "INTEGER", required = false),
            ),
        ),
        ActionSpec(
            ActionType.COMPLETE_TASK,
            "Mark a matching task complete. Completed tasks leave the active list.",
            listOf(ParamSpec("query", "Words from the task title.", maximum = 160)),
        ),
        ActionSpec(ActionType.LIST_TASKS, "Read back the active task list."),
        ActionSpec(
            ActionType.DEVICE_INFO,
            "Read battery, storage, memory, network or model information from this device.",
            listOf(
                ParamSpec(
                    "field", "Information to read, defaults to SUMMARY.", required = false,
                    enumValues = listOf("BATTERY", "STORAGE", "MEMORY", "NETWORK", "MODEL", "SUMMARY"),
                ),
            ),
        ),
        ActionSpec(
            ActionType.MEDIA_CONTROL,
            "Control the currently active media session.",
            listOf(
                ParamSpec(
                    "command", "Playback command.",
                    enumValues = listOf("TOGGLE", "PLAY", "PAUSE", "NEXT", "PREVIOUS", "STOP"),
                ),
            ),
        ),
        ActionSpec(
            ActionType.READ_NOTIFICATIONS,
            "Read recent notifications from the device. Contents stay on device and are only summarised to the user.",
            listOf(
                ParamSpec("limit", "How many notifications to read, 1 to 10.", jsonType = "INTEGER", minimum = 1, maximum = 10, required = false),
            ),
        ),
        ActionSpec(
            ActionType.FIND_CONTACT,
            "Look up a contact on this device. The contact database never leaves the device.",
            listOf(ParamSpec("query", "Name or phone fragment to search for.", maximum = 80)),
        ),
        ActionSpec(
            ActionType.CALL_CONTACT,
            "Prepare a call to a contact by opening the dialer. Always confirmed by the user.",
            listOf(ParamSpec("contact", "Contact name to call.", maximum = 80)),
        ),
        ActionSpec(
            ActionType.COMPOSE_MESSAGE,
            "Open the messaging composer for a contact with a drafted body. Always confirmed and never sent automatically.",
            listOf(
                ParamSpec("contact", "Recipient contact name.", maximum = 80),
                ParamSpec("body", "Draft message body.", maximum = 500),
            ),
        ),
        ActionSpec(
            ActionType.CREATE_ROUTINE,
            "Create a scheduled routine that runs a list of steps at a time each day.",
            listOf(
                ParamSpec("name", "Routine name.", maximum = 60),
                ParamSpec("hour", "Hour of day in 24-hour time.", jsonType = "INTEGER", minimum = 0, maximum = 23),
                ParamSpec("minute", "Minute of hour.", jsonType = "INTEGER", minimum = 0, maximum = 59),
                ParamSpec("steps", "Ordered list of step descriptions.", jsonType = "ARRAY"),
            ),
        ),
        ActionSpec(
            ActionType.RUN_ROUTINE,
            "Run a stored routine once, now.",
            listOf(ParamSpec("name", "Routine name.", maximum = 60)),
        ),
        ActionSpec(
            ActionType.REMEMBER,
            "Store a user preference or fact in the local encrypted memory.",
            listOf(ParamSpec("text", "The fact to remember, phrased in the user's words.", maximum = 300)),
        ),
        ActionSpec(
            ActionType.RECALL,
            "Search the user's local memory for stored facts.",
            listOf(ParamSpec("query", "Words to search for.", maximum = 120)),
        ),
    )

    private val byType: Map<ActionType, ActionSpec> = specs.associateBy { it.type }

    fun specFor(type: ActionType): ActionSpec? = byType[type]

    /** Function name published to Gemini, e.g. `open_app`. */
    fun functionName(type: ActionType): String = type.wire.lowercase(Locale.ROOT)

    fun typeForFunctionName(name: String): ActionType? =
        ActionType.entries.firstOrNull { functionName(it).equals(name.trim(), ignoreCase = true) }

    /** JSON array of function declarations for the Live API setup message. */
    fun functionDeclarations(): JSONArray {
        val declarations = JSONArray()
        specs.forEach { spec ->
            val properties = JSONObject()
            val required = JSONArray()
            spec.params.forEach { param ->
                val property = JSONObject()
                    .put("type", param.jsonType)
                    .put("description", param.description)
                param.enumValues?.let { property.put("enum", JSONArray(it)) }
                if (param.jsonType == "INTEGER") {
                    param.minimum?.let { property.put("minimum", it) }
                    param.maximum?.let { property.put("maximum", it) }
                }
                if (param.jsonType == "STRING") {
                    param.maximum?.let { property.put("maxLength", it) }
                }
                properties.put(param.name, property)
                if (param.required) required.put(param.name)
            }
            val parameters = JSONObject()
                .put("type", "OBJECT")
                .put("properties", properties)
            if (required.length() > 0) parameters.put("required", required)

            declarations.put(
                JSONObject()
                    .put("name", functionName(spec.type))
                    .put("description", spec.description)
                    .put("parameters", parameters),
            )
        }
        return declarations
    }
}
