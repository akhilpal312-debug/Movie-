package com.jarvis.assistant.ai

import com.jarvis.assistant.security.ActionSchema
import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64

/**
 * How the socket is authenticated.
 *
 * [EPHEMERAL_TOKEN] is the default: the JARVIS backend mints a single-use token, so no
 * long-lived credential exists on the phone. [API_KEY] is the bring-your-own-key path
 * for users who do not run the backend; the key stays encrypted on this device.
 */
enum class LiveAuthMode {
    EPHEMERAL_TOKEN,
    API_KEY,
}

/** Everything needed to open one Live API session. */
data class LiveConnectRequest(
    val accessToken: String,
    val model: String,
    val systemInstruction: String,
    val voiceName: String,
    /** Resumption handle from a previous session, when resuming. */
    val resumeHandle: String? = null,
    val temperature: Double = 0.7,
    val authMode: LiveAuthMode = LiveAuthMode.EPHEMERAL_TOKEN,
)

/** Server events JARVIS reacts to. Anything unknown is surfaced, never guessed. */
sealed interface LiveEvent {
    data object SetupComplete : LiveEvent
    data class AudioChunk(val pcm16: ByteArray) : LiveEvent
    data class OutputTranscript(val text: String, val final: Boolean) : LiveEvent
    data class InputTranscript(val text: String) : LiveEvent
    data object Interrupted : LiveEvent
    data object TurnComplete : LiveEvent
    data class ToolCall(val id: String, val name: String, val args: JSONObject) : LiveEvent
    data class ResumptionUpdate(val handle: String, val resumable: Boolean) : LiveEvent
    data class GoAway(val timeLeft: String?) : LiveEvent
    data class Failure(val message: String, val code: Int? = null) : LiveEvent
    data class Closed(val code: Int, val reason: String) : LiveEvent
}

/**
 * The wire format of `BidiGenerateContent`.
 *
 * Kept as pure functions with no Android or OkHttp dependency so the exact frames
 * JARVIS sends can be asserted in JVM unit tests.
 */
object LiveProtocol {

    const val INPUT_SAMPLE_RATE = 16_000
    const val OUTPUT_SAMPLE_RATE = 24_000
    const val INPUT_MIME_TYPE = "audio/pcm;rate=16000"
    const val OUTPUT_MIME_TYPE = "audio/pcm;rate=24000"
    const val HOST = "generativelanguage.googleapis.com"
    const val METHOD_PATH =
        "/ws/google.ai.generativelanguage.v1alpha.GenerativeService.BidiGenerateContent"

    /**
     * WSS endpoint. Ephemeral tokens travel as `access_token`; a user-supplied API key
     * travels as `key`, which is how the Live API authenticates an API key directly.
     */
    fun endpoint(request: LiveConnectRequest): String {
        val parameter = when (request.authMode) {
            LiveAuthMode.EPHEMERAL_TOKEN -> "access_token"
            LiveAuthMode.API_KEY -> "key"
        }
        return "wss://$HOST$METHOD_PATH?$parameter=${request.accessToken}"
    }

    /**
     * Push-to-talk is used deliberately: automatic voice activity detection is
     * disabled so the microphone is only streamed between activityStart and
     * activityEnd. Nothing is uploaded while the user is not holding the session.
     */
    fun setupMessage(request: LiveConnectRequest): String {
        val generationConfig = JSONObject()
            .put("responseModalities", JSONArray().put("AUDIO"))
            .put("temperature", request.temperature)
            .put(
                "speechConfig",
                JSONObject().put(
                    "voiceConfig",
                    JSONObject().put(
                        "prebuiltVoiceConfig",
                        JSONObject().put("voiceName", request.voiceName),
                    ),
                ),
            )

        val setup = JSONObject()
            .put("model", modelUri(request.model))
            .put("generationConfig", generationConfig)
            .put(
                "systemInstruction",
                JSONObject().put("parts", JSONArray().put(JSONObject().put("text", request.systemInstruction))),
            )
            .put(
                "tools",
                JSONArray().put(JSONObject().put("functionDeclarations", ActionSchema.functionDeclarations())),
            )
            .put("outputAudioTranscription", JSONObject())
            .put(
                "realtimeInputConfig",
                JSONObject().put(
                    "automaticActivityDetection",
                    JSONObject().put("disabled", true),
                ),
            )

        val resumption = JSONObject()
        request.resumeHandle?.let { resumption.put("handle", it) }
        setup.put("sessionResumption", resumption)

        return JSONObject().put("setup", setup).toString()
    }

    fun modelUri(model: String): String = if (model.startsWith("models/")) model else "models/$model"

    fun activityStart(): String = JSONObject()
        .put("realtimeInput", JSONObject().put("activityStart", JSONObject()))
        .toString()

    fun activityEnd(): String = JSONObject()
        .put("realtimeInput", JSONObject().put("activityEnd", JSONObject()))
        .toString()

    fun audioMessage(pcm16: ByteArray): String {
        val base64 = Base64.getEncoder().encodeToString(pcm16)
        return JSONObject().put(
            "realtimeInput",
            JSONObject().put(
                "audio",
                JSONObject().put("data", base64).put("mimeType", INPUT_MIME_TYPE),
            ),
        ).toString()
    }

    /** Typed text turn, used when the user types a command instead of speaking. */
    fun textTurn(text: String): String = JSONObject().put(
        "clientContent",
        JSONObject()
            .put(
                "turns",
                JSONArray().put(
                    JSONObject()
                        .put("role", "user")
                        .put("parts", JSONArray().put(JSONObject().put("text", text))),
                ),
            )
            .put("turnComplete", true),
    ).toString()

    /** Result of a locally executed action, reported back to the model. */
    fun toolResponse(id: String, name: String, status: String, message: String): String {
        val response = JSONObject()
            .put("status", status)
            .put("message", message)
        return JSONObject().put(
            "toolResponse",
            JSONObject().put(
                "functionResponses",
                JSONArray().put(
                    JSONObject()
                        .put("id", id)
                        .put("name", name)
                        .put("response", response),
                ),
            ),
        ).toString()
    }

    fun parseServerMessage(raw: String): List<LiveEvent> {
        if (raw.isBlank()) return emptyList()
        val json = runCatching { JSONObject(raw) }.getOrNull()
            ?: return listOf(LiveEvent.Failure("Unreadable frame from Gemini"))
        val events = mutableListOf<LiveEvent>()

        if (json.has("setupComplete")) events += LiveEvent.SetupComplete

        json.optJSONObject("serverContent")?.let { content ->
            if (content.optBoolean("interrupted", false)) events += LiveEvent.Interrupted

            content.optJSONObject("inputTranscription")?.optString("text")?.takeIf(String::isNotBlank)?.let {
                events += LiveEvent.InputTranscript(it)
            }
            content.optJSONObject("outputTranscription")?.let { transcription ->
                val text = transcription.optString("text")
                if (text.isNotBlank()) {
                    events += LiveEvent.OutputTranscript(text, transcription.optBoolean("final", false))
                }
            }

            content.optJSONObject("modelTurn")?.optJSONArray("parts")?.let { parts ->
                for (index in 0 until parts.length()) {
                    val inline = parts.optJSONObject(index)?.optJSONObject("inlineData") ?: continue
                    val mime = inline.optString("mimeType")
                    if (!mime.startsWith("audio/")) continue
                    val decoded = runCatching {
                        Base64.getDecoder().decode(inline.optString("data"))
                    }.getOrNull() ?: continue
                    if (decoded.isNotEmpty()) events += LiveEvent.AudioChunk(decoded)
                }
            }

            if (content.optBoolean("turnComplete", false)) events += LiveEvent.TurnComplete
        }

        json.optJSONObject("toolCall")?.optJSONArray("functionCalls")?.let { calls ->
            for (index in 0 until calls.length()) {
                val call = calls.optJSONObject(index) ?: continue
                events += LiveEvent.ToolCall(
                    id = call.optString("id"),
                    name = call.optString("name"),
                    args = call.optJSONObject("args") ?: JSONObject(),
                )
            }
        }

        json.optJSONObject("sessionResumptionUpdate")?.let { update ->
            val handle = update.optString("newHandle")
            if (handle.isNotBlank()) {
                events += LiveEvent.ResumptionUpdate(handle, update.optBoolean("resumable", true))
            }
        }

        json.optJSONObject("goAway")?.let { goAway ->
            events += LiveEvent.GoAway(goAway.optString("timeLeft").ifBlank { null })
        }

        json.optJSONObject("error")?.let { error ->
            events += LiveEvent.Failure(
                message = error.optString("message").ifBlank { "Gemini reported an error" },
                code = error.optInt("code").takeIf { it != 0 },
            )
        }

        return events
    }
}
