package com.jarvis.assistant.security

import com.google.common.truth.Truth.assertThat
import com.jarvis.assistant.core.model.ActionType
import com.jarvis.assistant.core.model.AudioStream
import com.jarvis.assistant.core.model.JarvisAction
import com.jarvis.assistant.core.model.MediaCommand
import com.jarvis.assistant.core.model.RiskLevel
import com.jarvis.assistant.core.model.SettingsPanel
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test

class ActionParserTest {

    private val parser = ActionParser()

    @Test
    fun `parses the documented envelope`() {
        val result = parser.parseEnvelope(
            """{"action":"OPEN_APP","parameters":{"app":"com.android.settings"},
               "risk_level":"LOW","requires_confirmation":false}""",
        )

        assertThat(result).isInstanceOf(ParseResult.Parsed::class.java)
        val action = (result as ParseResult.Parsed).action
        assertThat(action).isEqualTo(JarvisAction.OpenApp("com.android.settings"))
        assertThat(action.riskLevel).isEqualTo(RiskLevel.LOW)
    }

    @Test
    fun `ignores a model-supplied risk level so risk cannot be downgraded`() {
        val result = parser.parseEnvelope(
            """{"action":"CALL_CONTACT","parameters":{"contact":"Mom"},
               "risk_level":"LOW","requires_confirmation":false}""",
        )

        val action = (result as ParseResult.Parsed).action
        assertThat(action.type).isEqualTo(ActionType.CALL_CONTACT)
        // The payload claimed LOW; the local catalog still says HIGH.
        assertThat(action.riskLevel).isEqualTo(RiskLevel.HIGH)
    }

    @Test
    fun `parses live tool calls`() {
        val result = parser.parseFunctionCall("set_volume", JSONObject().put("percent", 40))

        val action = (result as ParseResult.Parsed).action
        assertThat(action).isEqualTo(JarvisAction.SetVolume(AudioStream.MUSIC, 40))
    }

    @Test
    fun `parses string numbers because models sometimes send them`() {
        val result = parser.parseFunctionCall("set_volume", JSONObject().put("percent", "55"))

        val action = (result as ParseResult.Parsed).action
        assertThat(action).isEqualTo(JarvisAction.SetVolume(AudioStream.MUSIC, 55))
    }

    @Test
    fun `rejects unknown actions`() {
        val result = parser.parseEnvelope("""{"action":"RUN_SHELL","parameters":{"cmd":"rm -rf /"}}""")

        assertThat(result).isInstanceOf(ParseResult.Rejected::class.java)
        assertThat((result as ParseResult.Rejected).code).isEqualTo(RejectionCode.UNKNOWN_ACTION)
    }

    @Test
    fun `rejects unknown tool names`() {
        val result = parser.parseFunctionCall("exec_shell", JSONObject())

        assertThat((result as ParseResult.Rejected).code).isEqualTo(RejectionCode.UNKNOWN_ACTION)
    }

    @Test
    fun `rejects shell metacharacters in text parameters`() {
        for (payload in listOf("camera; rm -rf /", "settings && reboot", "app | tee /sdcard/x", "`id`")) {
            val result = parser.parseFunctionCall("open_app", JSONObject().put("app", payload))
            assertThat(result).isInstanceOf(ParseResult.Rejected::class.java)
            assertThat((result as ParseResult.Rejected).code).isEqualTo(RejectionCode.INVALID_PARAMETER)
        }
    }

    @Test
    fun `rejects control characters`() {
        val result = parser.parseFunctionCall("open_app", JSONObject().put("app", "camera\nsettings"))
        assertThat((result as ParseResult.Rejected).code).isEqualTo(RejectionCode.INVALID_PARAMETER)
    }

    @Test
    fun `rejects out of range numbers`() {
        assertThat(parser.parseFunctionCall("set_volume", JSONObject().put("percent", 140)))
            .isInstanceOf(ParseResult.Rejected::class.java)
        assertThat(parser.parseFunctionCall("create_alarm", JSONObject().put("hour", 25).put("minute", 0)))
            .isInstanceOf(ParseResult.Rejected::class.java)
        assertThat(parser.parseFunctionCall("set_brightness", JSONObject().put("percent", -5)))
            .isInstanceOf(ParseResult.Rejected::class.java)
    }

    @Test
    fun `rejects missing required parameters`() {
        val result = parser.parseFunctionCall("create_alarm", JSONObject().put("hour", 7))
        assertThat((result as ParseResult.Rejected).code).isEqualTo(RejectionCode.MISSING_PARAMETER)
    }

    @Test
    fun `rejects unsupported enum values`() {
        val result = parser.parseFunctionCall(
            "open_settings",
            JSONObject().put("panel", "ROOT_SHELL"),
        )
        assertThat((result as ParseResult.Rejected).code).isEqualTo(RejectionCode.INVALID_PARAMETER)
    }

    @Test
    fun `accepts a valid settings panel case-insensitively`() {
        val result = parser.parseFunctionCall("open_settings", JSONObject().put("panel", "wifi"))
        val action = (result as ParseResult.Parsed).action
        assertThat(action).isEqualTo(JarvisAction.OpenSettings(SettingsPanel.WIFI))
    }

    @Test
    fun `rejects oversized text`() {
        val result = parser.parseFunctionCall("remember", JSONObject().put("text", "x".repeat(600)))
        assertThat((result as ParseResult.Rejected).code).isEqualTo(RejectionCode.TOO_LARGE)
    }

    @Test
    fun `rejects a routine without steps`() {
        val result = parser.parseFunctionCall(
            "create_routine",
            JSONObject()
                .put("name", "Morning")
                .put("hour", 7)
                .put("minute", 0)
                .put("steps", JSONArray()),
        )
        assertThat((result as ParseResult.Rejected).code).isEqualTo(RejectionCode.INVALID_PARAMETER)
    }

    @Test
    fun `rejects malformed json`() {
        assertThat(parser.parseEnvelope("not json"))
            .isInstanceOf(ParseResult.Rejected::class.java)
        assertThat(parser.parseEnvelope(""))
            .isInstanceOf(ParseResult.Rejected::class.java)
        assertThat(parser.parseEnvelope("[]"))
            .isInstanceOf(ParseResult.Rejected::class.java)
    }

    @Test
    fun `accepts a complete media command`() {
        val result = parser.parseFunctionCall("media_control", JSONObject().put("command", "next"))
        assertThat((result as ParseResult.Parsed).action)
            .isEqualTo(JarvisAction.MediaControl(MediaCommand.NEXT))
    }

    @Test
    fun `tolerates extra parameters instead of failing open`() {
        val result = parser.parseFunctionCall(
            "device_info",
            JSONObject().put("field", "BATTERY").put("include_files", true),
        )
        assertThat(result).isInstanceOf(ParseResult.Parsed::class.java)
    }

    @Test
    fun `every action type has a schema`() {
        ActionType.entries.forEach { type ->
            assertThat(ActionSchema.specFor(type)).isNotNull()
        }
    }

    @Test
    fun `function declarations match the catalog`() {
        val declarations = ActionSchema.functionDeclarations()
        assertThat(declarations.length()).isEqualTo(ActionSchema.specs.size)

        val names = (0 until declarations.length()).map { declarations.getJSONObject(it).getString("name") }
        assertThat(names).containsExactlyElementsIn(ActionType.entries.map(ActionSchema::functionName))
        // Function names must be unique, otherwise the model cannot disambiguate.
        assertThat(names.toSet()).hasSize(names.size)
    }
}
