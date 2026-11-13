package com.jarvis.assistant.ai

import com.google.common.truth.Truth.assertThat
import com.jarvis.assistant.security.ActionSchema
import org.json.JSONObject
import org.junit.Test
import java.util.Base64

class LiveProtocolTest {

    private val request = LiveConnectRequest(
        accessToken = "ephemeral-token",
        model = "gemini-3.8-live",
        systemInstruction = "You are JARVIS.",
        voiceName = "Aoede",
    )

    @Test
    fun `endpoint uses wss and the ephemeral token`() {
        val endpoint = LiveProtocol.endpoint(request)
        assertThat(endpoint).startsWith("wss://generativelanguage.googleapis.com/")
        assertThat(endpoint).contains("BidiGenerateContent")
        assertThat(endpoint).contains("access_token=ephemeral-token")
    }

    @Test
    fun `setup frame declares audio-only native responses`() {
        val setup = JSONObject(LiveProtocol.setupMessage(request)).getJSONObject("setup")

        assertThat(setup.getString("model")).isEqualTo("models/gemini-3.8-live")
        val modality = setup.getJSONObject("generationConfig")
            .getJSONArray("responseModalities")
            .getString(0)
        assertThat(modality).isEqualTo("AUDIO")
        assertThat(
            setup.getJSONObject("generationConfig")
                .getJSONObject("speechConfig")
                .getJSONObject("voiceConfig")
                .getJSONObject("prebuiltVoiceConfig")
                .getString("voiceName"),
        ).isEqualTo("Aoede")
    }

    @Test
    fun `setup publishes every local action as a function`() {
        val setup = JSONObject(LiveProtocol.setupMessage(request)).getJSONObject("setup")
        val declarations = setup.getJSONArray("tools")
            .getJSONObject(0)
            .getJSONArray("functionDeclarations")

        assertThat(declarations.length()).isEqualTo(ActionSchema.specs.size)
    }

    @Test
    fun `automatic voice detection is disabled so audio is push to talk`() {
        val setup = JSONObject(LiveProtocol.setupMessage(request)).getJSONObject("setup")
        val detection = setup.getJSONObject("realtimeInputConfig")
            .getJSONObject("automaticActivityDetection")

        assertThat(detection.getBoolean("disabled")).isTrue()
    }

    @Test
    fun `resumption handle is included only when resuming`() {
        val fresh = JSONObject(LiveProtocol.setupMessage(request)).getJSONObject("setup")
        assertThat(fresh.getJSONObject("sessionResumption").has("handle")).isFalse()

        val resumed = JSONObject(
            LiveProtocol.setupMessage(request.copy(resumeHandle = "handle-123")),
        ).getJSONObject("setup")
        assertThat(resumed.getJSONObject("sessionResumption").getString("handle")).isEqualTo("handle-123")
    }

    @Test
    fun `audio frames carry base64 pcm at 16k`() {
        val pcm = byteArrayOf(1, 2, 3, 4)
        val frame = JSONObject(LiveProtocol.audioMessage(pcm))
            .getJSONObject("realtimeInput")
            .getJSONObject("audio")

        assertThat(frame.getString("mimeType")).isEqualTo("audio/pcm;rate=16000")
        assertThat(Base64.getDecoder().decode(frame.getString("data"))).isEqualTo(pcm)
    }

    @Test
    fun `text turn is marked complete`() {
        val content = JSONObject(LiveProtocol.textTurn("open camera")).getJSONObject("clientContent")
        assertThat(content.getBoolean("turnComplete")).isTrue()
        assertThat(
            content.getJSONArray("turns").getJSONObject(0)
                .getJSONArray("parts").getJSONObject(0).getString("text"),
        ).isEqualTo("open camera")
    }

    @Test
    fun `parses setup, audio, transcription and turn completion`() {
        val audio = Base64.getEncoder().encodeToString(byteArrayOf(9, 8, 7))
        val raw = """
            {"serverContent":{
              "modelTurn":{"parts":[{"inlineData":{"mimeType":"audio/pcm;rate=24000","data":"$audio"}}]},
              "outputTranscription":{"text":"Done."},
              "turnComplete":true
            }}
        """.trimIndent()

        val events = LiveProtocol.parseServerMessage(raw)
        assertThat(events.filterIsInstance<LiveEvent.AudioChunk>()).hasSize(1)
        assertThat(events.filterIsInstance<LiveEvent.AudioChunk>().first().pcm16)
            .isEqualTo(byteArrayOf(9, 8, 7))
        assertThat(events.filterIsInstance<LiveEvent.OutputTranscript>().first().text).isEqualTo("Done.")
        assertThat(events).contains(LiveEvent.TurnComplete)
    }

    @Test
    fun `parses setup complete and interruption`() {
        assertThat(LiveProtocol.parseServerMessage("""{"setupComplete":{}}"""))
            .contains(LiveEvent.SetupComplete)
        assertThat(LiveProtocol.parseServerMessage("""{"serverContent":{"interrupted":true}}"""))
            .contains(LiveEvent.Interrupted)
    }

    @Test
    fun `parses tool calls with arguments`() {
        val raw = """
            {"toolCall":{"functionCalls":[
              {"id":"call-1","name":"open_app","args":{"app":"camera"}}
            ]}}
        """.trimIndent()

        val call = LiveProtocol.parseServerMessage(raw).filterIsInstance<LiveEvent.ToolCall>().single()
        assertThat(call.id).isEqualTo("call-1")
        assertThat(call.name).isEqualTo("open_app")
        assertThat(call.args.getString("app")).isEqualTo("camera")
    }

    @Test
    fun `parses resumption updates and go away`() {
        val resumed = LiveProtocol.parseServerMessage(
            """{"sessionResumptionUpdate":{"newHandle":"h2","resumable":true}}""",
        ).filterIsInstance<LiveEvent.ResumptionUpdate>().single()
        assertThat(resumed.handle).isEqualTo("h2")

        val goAway = LiveProtocol.parseServerMessage("""{"goAway":{"timeLeft":"5s"}}""")
            .filterIsInstance<LiveEvent.GoAway>().single()
        assertThat(goAway.timeLeft).isEqualTo("5s")
    }

    @Test
    fun `parses provider errors without crashing`() {
        val failure = LiveProtocol.parseServerMessage(
            """{"error":{"code":429,"message":"quota exceeded"}}""",
        ).filterIsInstance<LiveEvent.Failure>().single()

        assertThat(failure.code).isEqualTo(429)
        assertThat(failure.message).isEqualTo("quota exceeded")
    }

    @Test
    fun `malformed frames become a failure instead of an exception`() {
        assertThat(LiveProtocol.parseServerMessage("}{").first())
            .isInstanceOf(LiveEvent.Failure::class.java)
        assertThat(LiveProtocol.parseServerMessage("")).isEmpty()
    }

    @Test
    fun `non-audio inline data is ignored`() {
        val raw = """{"serverContent":{"modelTurn":{"parts":[{"inlineData":{"mimeType":"text/plain","data":"aGk="}}]}}}"""
        assertThat(LiveProtocol.parseServerMessage(raw).filterIsInstance<LiveEvent.AudioChunk>()).isEmpty()
    }

    @Test
    fun `a user-supplied api key authenticates with the key parameter`() {
        val direct = request.copy(
            accessToken = "AIzaTESTKEY",
            authMode = LiveAuthMode.API_KEY,
        )
        val endpoint = LiveProtocol.endpoint(direct)

        assertThat(endpoint).contains("?key=AIzaTESTKEY")
        assertThat(endpoint).doesNotContain("access_token")
    }

    @Test
    fun `credentials never appear in the setup frame`() {
        for (mode in LiveAuthMode.entries) {
            val credential = "secret-${mode.name}"
            val setup = LiveProtocol.setupMessage(request.copy(accessToken = credential, authMode = mode))

            // The credential belongs in the socket URL only; an accidental log of the
            // setup frame must never reveal it.
            assertThat(setup).doesNotContain(credential)
        }
    }
}
