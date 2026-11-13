package com.jarvis.assistant.security

import com.jarvis.assistant.core.model.ActionType
import com.jarvis.assistant.core.model.AudioStream
import com.jarvis.assistant.core.model.DeviceInfoField
import com.jarvis.assistant.core.model.JarvisAction
import com.jarvis.assistant.core.model.MediaCommand
import com.jarvis.assistant.core.model.SettingsPanel
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

sealed interface ParseResult {
    data class Parsed(val action: JarvisAction) : ParseResult
    data class Rejected(val code: RejectionCode, val reason: String) : ParseResult
}

enum class RejectionCode {
    MALFORMED,
    UNKNOWN_ACTION,
    INVALID_PARAMETER,
    MISSING_PARAMETER,
    TOO_LARGE,
}

/**
 * Converts untrusted model output into a typed [JarvisAction].
 *
 * Everything here fails closed: unknown actions, extra-typed parameters, control
 * characters or out-of-range numbers are rejected rather than coerced. The model
 * can only ever produce one of the actions in [ActionSchema].
 */
@Singleton
class ActionParser @Inject constructor() {

    /**
     * Parses the `{"action": "...", "parameters": {...}}` envelope described in the
     * product contract. `risk_level` and `requires_confirmation` fields are
     * deliberately ignored: risk is recomputed locally from the catalog.
     */
    fun parseEnvelope(raw: String): ParseResult {
        if (raw.isBlank()) return reject(RejectionCode.MALFORMED, "Empty command")
        val json = runCatching { JSONObject(raw) }.getOrElse {
            return reject(RejectionCode.MALFORMED, "Command is not a JSON object")
        }
        val actionName = json.optString("action").orEmpty()
        if (actionName.isBlank()) return reject(RejectionCode.MISSING_PARAMETER, "Missing action")
        val type = ActionType.fromWire(actionName)
            ?: ActionSchema.typeForFunctionName(actionName)?.takeIf { it != null }
            ?: return reject(RejectionCode.UNKNOWN_ACTION, "Unsupported action: ${sanitizeForMessage(actionName)}")
        val parameters = json.optJSONObject("parameters") ?: JSONObject()
        return parse(type, parameters)
    }

    /** Parses a Live API tool call: `name` + flattened `args`. */
    fun parseFunctionCall(name: String, args: JSONObject): ParseResult {
        val type = ActionSchema.typeForFunctionName(name)
            ?: return reject(RejectionCode.UNKNOWN_ACTION, "Unsupported tool: ${sanitizeForMessage(name)}")
        return parse(type, args)
    }

    private fun parse(type: ActionType, parameters: JSONObject): ParseResult {
        val spec = ActionSchema.specFor(type)
            ?: return reject(RejectionCode.UNKNOWN_ACTION, "Action has no local specification")

        // Only declared parameters are read; anything else is ignored, not executed.
        val values = mutableMapOf<String, Any?>()
        for (param in spec.params) {
            val present = parameters.has(param.name) && !parameters.isNull(param.name)
            if (!present) {
                if (param.required) {
                    return reject(RejectionCode.MISSING_PARAMETER, "Missing parameter: ${param.name}")
                }
                continue
            }
            val value: Any? = when (param.jsonType) {
                "INTEGER" -> when (val number = parameters.opt(param.name)) {
                    is Int -> number
                    is Long -> number.toInt()
                    is Double -> if (number % 1.0 == 0.0) number.toInt() else null
                    is String -> number.trim().toIntOrNull()
                    else -> null
                }
                "BOOLEAN" -> when (val bool = parameters.opt(param.name)) {
                    is Boolean -> bool
                    is String -> bool.lowercase().let { if (it == "true") true else if (it == "false") false else null }
                    else -> null
                }
                "ARRAY" -> {
                    val array = parameters.optJSONArray(param.name)
                        ?: return reject(RejectionCode.INVALID_PARAMETER, "${param.name} must be an array")
                    (0 until array.length()).mapNotNull { index -> array.optString(index).takeIf(String::isNotBlank) }
                }
                else -> {
                    val text = parameters.opt(param.name)
                    if (text is String) text else null
                }
            } ?: return reject(RejectionCode.INVALID_PARAMETER, "Bad value for ${param.name}")

            if (value is Int) {
                param.minimum?.let { min ->
                    if (value < min) return reject(RejectionCode.INVALID_PARAMETER, "${param.name} below minimum")
                }
                param.maximum?.let { max ->
                    if (value > max) return reject(RejectionCode.INVALID_PARAMETER, "${param.name} above maximum")
                }
            }
            if (value is String) {
                val problem = validateText(value)
                if (problem != null) return reject(problem, "Invalid text in ${param.name}")
                param.maximum?.let { cap ->
                    if (value.length > cap) {
                        return reject(RejectionCode.TOO_LARGE, "${param.name} is longer than $cap characters")
                    }
                }
                param.enumValues?.let { allowed ->
                    if (allowed.none { it.equals(value.trim(), ignoreCase = true) }) {
                        return reject(RejectionCode.INVALID_PARAMETER, "${param.name} must be one of ${allowed.joinToString()}")
                    }
                }
            }
            values[param.name] = value
        }

        val action = build(type, values)
            ?: return reject(RejectionCode.INVALID_PARAMETER, "Could not build ${type.wire} from the given parameters")
        return ParseResult.Parsed(action)
    }

    @Suppress("CyclomaticComplexMethod")
    private fun build(type: ActionType, values: Map<String, Any?>): JarvisAction? {
        return when (type) {
        ActionType.OPEN_APP -> JarvisAction.OpenApp(values.text("app") ?: return null)
        ActionType.OPEN_SETTINGS -> JarvisAction.OpenSettings(
            SettingsPanel.fromKey(values.text("panel") ?: return null) ?: return null,
        )
        ActionType.SET_VOLUME -> JarvisAction.SetVolume(
            stream = AudioStream.fromKey(values.text("stream")),
            percent = values.int("percent") ?: return null,
        )
        ActionType.ADJUST_VOLUME -> JarvisAction.AdjustVolume(
            stream = AudioStream.fromKey(values.text("stream")),
            stepPercent = values.int("step") ?: return null,
        )
        ActionType.SET_BRIGHTNESS -> JarvisAction.SetBrightness(values.int("percent") ?: return null)
        ActionType.SET_DND -> JarvisAction.SetDoNotDisturb(values.bool("enabled") ?: return null)
        ActionType.CREATE_ALARM -> JarvisAction.CreateAlarm(
            hour = values.int("hour") ?: return null,
            minute = values.int("minute") ?: return null,
            label = values.text("label"),
        )
        ActionType.START_TIMER -> JarvisAction.StartTimer(
            durationSeconds = values.int("seconds") ?: return null,
            label = values.text("label"),
        )
        ActionType.CREATE_REMINDER -> JarvisAction.CreateReminder(
            message = values.text("message") ?: return null,
            triggerAtMillis = (values["epoch_ms"] as? Int)?.toLong() ?: return null,
        )
        ActionType.ADD_TASK -> JarvisAction.AddTask(
            title = values.text("title") ?: return null,
            dueAtMillis = (values["due_epoch_ms"] as? Int)?.toLong(),
        )
        ActionType.COMPLETE_TASK -> JarvisAction.CompleteTask(values.text("query") ?: return null)
        ActionType.LIST_TASKS -> JarvisAction.ListTasks
        ActionType.DEVICE_INFO -> JarvisAction.DeviceInfo(DeviceInfoField.fromKey(values.text("field")))
        ActionType.MEDIA_CONTROL -> JarvisAction.MediaControl(
            MediaCommand.fromKey(values.text("command") ?: return null) ?: return null,
        )
        ActionType.READ_NOTIFICATIONS -> JarvisAction.ReadNotifications(values.int("limit") ?: 5)
        ActionType.FIND_CONTACT -> JarvisAction.FindContact(values.text("query") ?: return null)
        ActionType.CALL_CONTACT -> JarvisAction.CallContact(values.text("contact") ?: return null)
        ActionType.COMPOSE_MESSAGE -> JarvisAction.ComposeMessage(
            contactQuery = values.text("contact") ?: return null,
            body = values.text("body") ?: return null,
        )
        ActionType.CREATE_ROUTINE -> {
            val steps = (values["steps"] as? List<*>)?.mapNotNull { (it as? String)?.trim()?.takeIf(String::isNotEmpty) }
                ?: return null
            if (steps.isEmpty() || steps.size > 10) return null
            JarvisAction.CreateRoutine(
                name = values.text("name") ?: return null,
                triggerHour = values.int("hour") ?: return null,
                triggerMinute = values.int("minute") ?: return null,
                steps = steps,
            )
        }
        ActionType.RUN_ROUTINE -> JarvisAction.RunRoutine(values.text("name") ?: return null)
        ActionType.REMEMBER -> JarvisAction.Remember(values.text("text") ?: return null)
        ActionType.RECALL -> JarvisAction.Recall(values.text("query") ?: return null)
        }
    }

    /** Rejects control characters, shell metacharacters and absurd lengths. */
    private fun validateText(value: String): RejectionCode? {
        if (value.length > 500) return RejectionCode.TOO_LARGE
        if (value.any { it.isISOControl() }) return RejectionCode.INVALID_PARAMETER
        if (SHELL_METACHARACTERS.any { value.contains(it) }) return RejectionCode.INVALID_PARAMETER
        return null
    }

    private fun sanitizeForMessage(value: String): String =
        value.filter { it.isLetterOrDigit() || it == '_' || it == '-' || it == ' ' }.take(60)

    private fun reject(code: RejectionCode, reason: String) = ParseResult.Rejected(code, reason)

    private fun Map<String, Any?>.text(key: String): String? =
        (this[key] as? String)?.trim()?.takeIf(String::isNotEmpty)

    private fun Map<String, Any?>.int(key: String): Int? = this[key] as? Int

    private fun Map<String, Any?>.bool(key: String): Boolean? = this[key] as? Boolean

    private companion object {
        /**
         * No action needs shell syntax. Blocking these characters keeps a hostile
         * model response from being smuggled into an intent extra or a filename.
         */
        val SHELL_METACHARACTERS = listOf("`", "\$((", "|", ";", "&&", ">", "<")
    }
}
