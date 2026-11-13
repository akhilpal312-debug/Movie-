package com.jarvis.assistant.voice

import com.jarvis.assistant.ai.GeminiLiveClient
import com.jarvis.assistant.ai.JarvisPrompts
import com.jarvis.assistant.ai.LiveAuthMode
import com.jarvis.assistant.ai.LiveConnectRequest
import com.jarvis.assistant.ai.LiveEvent
import com.jarvis.assistant.ai.LiveSession
import com.jarvis.assistant.ai.LiveTokenService
import com.jarvis.assistant.ai.LocalCommandParser
import com.jarvis.assistant.core.ActionRunner
import com.jarvis.assistant.core.model.ActionSource
import com.jarvis.assistant.core.model.ExecutionResult
import com.jarvis.assistant.core.model.OperatingMode
import com.jarvis.assistant.core.model.UserPreferences
import com.jarvis.assistant.core.model.VoiceState
import com.jarvis.assistant.database.HistoryRepository
import com.jarvis.assistant.device.NotificationVault
import com.jarvis.assistant.security.ActionParser
import com.jarvis.assistant.security.ActionSummary
import com.jarvis.assistant.security.ParseResult
import com.jarvis.assistant.security.SensitiveContentScanner
import com.jarvis.assistant.settings.PreferencesRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns the voice conversation lifecycle for the whole app.
 *
 * Two modes, never mixed:
 *  - GEMINI: push-to-talk → 16 kHz PCM to Gemini Live → 24 kHz PCM back.
 *  - LOCAL:  on-device recogniser → offline parser → local TTS. No socket is opened.
 *
 * Every device action the model requests is executed through [ActionRunner], so the
 * privacy core still authorises it.
 */
@Singleton
class VoiceSessionController @Inject constructor(
    private val liveClient: GeminiLiveClient,
    private val tokenService: LiveTokenService,
    private val capture: AudioCapture,
    private val playback: AudioPlayback,
    private val localVoice: LocalVoiceInput,
    private val tts: TtsSpeaker,
    private val parser: ActionParser,
    private val localParser: LocalCommandParser,
    private val scanner: SensitiveContentScanner,
    private val runner: ActionRunner,
    private val preferences: PreferencesRepository,
    private val notificationVault: NotificationVault,
    private val history: HistoryRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _state = MutableStateFlow(VoiceUiState(localVoiceAvailable = localVoice.isAvailable))
    val state: StateFlow<VoiceUiState> = _state.asStateFlow()

    private var session: LiveSession? = null
    private var eventJob: Job? = null
    private var audioQueue: Channel<ByteArray>? = null
    private var audioJob: Job? = null
    private var timerJob: Job? = null
    private var resumeHandle: String? = null
    private var sessionStartedAt = 0L
    private var reconnectAttempts = 0

    @Volatile
    private var listening = false

    @Volatile
    private var intentionalClose = false

    init {
        scope.launch {
            preferences.preferences.collect { prefs ->
                _state.update { current ->
                    current.copy(
                        mode = prefs.mode,
                        localVoiceAvailable = localVoice.isAvailable,
                    )
                }
            }
        }
    }

    // ------------------------------------------------------------- user actions

    /** Push-to-talk press, or the mic button in Local mode. */
    fun startListening() {
        scope.launch {
            val prefs = preferences.current()
            _state.update { it.copy(mode = prefs.mode, error = null, assistantText = "") }
            when (prefs.mode) {
                OperatingMode.LOCAL -> startLocalListening(prefs)
                OperatingMode.GEMINI -> startGeminiListening(prefs)
            }
        }
    }

    /** Push-to-talk release: stop uploading and let the model answer. */
    fun stopListening() {
        if (!listening) return
        listening = false
        capture.stop()
        session?.endUserSpeech()
        val cloud = session?.isOpen == true
        _state.update {
            it.copy(
                state = if (cloud) VoiceState.PROCESSING else VoiceState.IDLE,
                statusMessage = if (cloud) "Working on it…" else "",
            )
        }
    }

    /** Barge-in: silence JARVIS immediately and start listening again. */
    fun interruptAndListen() {
        dropQueuedAudio()
        playback.flushNow()
        tts.stop()
        scope.launch {
            val prefs = preferences.current()
            if (prefs.mode == OperatingMode.LOCAL) {
                startLocalListening(prefs)
                return@launch
            }
            if (!ensureSession(prefs)) return@launch
            listening = true
            session?.beginUserSpeech()
            startCapture()
            _state.update {
                it.copy(
                    state = VoiceState.LISTENING,
                    statusMessage = "Listening",
                    userText = "",
                    assistantText = "",
                    error = null,
                )
            }
        }
    }

    /** The permanent Stop Listening control. */
    fun stopEverything(reason: String? = null) {
        listening = false
        capture.stop()
        dropQueuedAudio()
        playback.flushNow()
        tts.stop()
        session?.endUserSpeech()
        _state.update { current ->
            current.copy(
                state = VoiceState.IDLE,
                statusMessage = reason.orEmpty(),
                pendingConfirmation = null,
            )
        }
    }

    /** Closes the socket, releases audio resources and clears the notification buffer. */
    fun endSession(reason: String? = null) {
        intentionalClose = true
        listening = false
        capture.stop()
        tts.stop()
        dropQueuedAudio()
        playback.flushNow()
        teardownSession()
        notificationVault.clear()
        _state.update { current ->
            VoiceUiState(
                mode = current.mode,
                localVoiceAvailable = localVoice.isAvailable,
                state = VoiceState.IDLE,
                statusMessage = reason.orEmpty(),
            )
        }
    }

    /** Typed command; works in both modes. */
    fun submitText(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        scope.launch {
            val prefs = preferences.current()
            _state.update { it.copy(mode = prefs.mode, userText = trimmed, error = null) }

            if (prefs.mode == OperatingMode.LOCAL) {
                handleLocalText(trimmed, prefs)
                return@launch
            }

            val scan = scanner.scan(trimmed)
            if (scan.blocked) {
                val message = "That message contains something that looks like a password or one-time code. " +
                    "JARVIS will not send it. Please remove it and try again."
                history.record(
                    status = "BLOCKED_SECRET",
                    actionType = "TEXT_COMMAND",
                    source = ActionSource.TEXT_GEMINI.name,
                    summary = "Blocked a command containing a secret",
                    at = System.currentTimeMillis(),
                )
                respondLocally(message, prefs)
                return@launch
            }

            if (!ensureSession(prefs)) return@launch
            session?.sendText(scan.safeText)
            _state.update { it.copy(state = VoiceState.PROCESSING, statusMessage = "Thinking…") }
        }
    }

    /** User approved the pending high-risk action. */
    fun confirmPending() {
        val pending = _state.value.pendingConfirmation ?: return
        _state.update { it.copy(pendingConfirmation = null, state = VoiceState.PROCESSING, statusMessage = "Running…") }
        scope.launch {
            val result = runner.run(
                action = pending.action,
                source = ActionSource.UI,
                confirmedFingerprint = pending.fingerprint,
            )
            val message = messageFor(result)
            _state.update { it.copy(state = VoiceState.IDLE, assistantText = message, statusMessage = "") }
            tts.speak(message)
            session?.sendText(
                "The user approved \"${pending.preview}\". Local result: $message. " +
                    "Tell them briefly what happened and do not repeat the request.",
            )
        }
    }

    fun dismissPending() {
        val pending = _state.value.pendingConfirmation ?: return
        _state.update { it.copy(pendingConfirmation = null) }
        session?.sendText("The user declined \"${pending.preview}\". Acknowledge briefly and do not retry.")
    }

    /** Called when the user leaves the app or the screen goes away. */
    fun onScreenClosed() {
        if (session?.isOpen != true) {
            stopEverything()
        }
    }

    // -------------------------------------------------------------- gemini path

    private suspend fun startGeminiListening(prefs: UserPreferences) {
        if (!capture.hasPermission()) {
            fail("Microphone permission is needed for voice conversation.")
            return
        }
        if (prefs.geminiConsentGrantedAt == null) {
            fail("Enable Gemini voice from the privacy screen first.")
            return
        }
        if (!ensureSession(prefs)) return

        listening = true
        session?.beginUserSpeech()
        startCapture()
        _state.update {
            it.copy(
                state = VoiceState.LISTENING,
                statusMessage = "Listening",
                userText = "",
                assistantText = "",
                error = null,
            )
        }
    }

    private fun startCapture() {
        capture.start(
            scope = scope,
            // Microphone samples are 16-bit PCM; the Live API expects little-endian bytes.
            onChunk = { chunk -> session?.sendAudio(PcmResampler.shortsToBytes(chunk)) },
            onError = { message -> fail(message) },
        )
    }

    private suspend fun ensureSession(prefs: UserPreferences): Boolean {
        session?.let { if (it.isOpen) return true }
        intentionalClose = false

        _state.update {
            it.copy(state = VoiceState.CONNECTING, statusMessage = "Connecting to Gemini Live…", error = null)
        }

        val request: LiveConnectRequest
        if (prefs.useDirectApiKey && prefs.directApiKeyPresent) {
            // Bring-your-own-key: the key is decrypted for this connection only.
            val key = preferences.directApiKey()
            if (key.isNullOrBlank()) {
                fail("The saved Gemini key could not be read. Enter it again in Voice & Gemini settings.")
                return false
            }
            request = LiveConnectRequest(
                accessToken = key,
                model = prefs.geminiModel,
                systemInstruction = JarvisPrompts.SYSTEM_INSTRUCTION,
                voiceName = prefs.voiceName,
                resumeHandle = resumeHandle,
                authMode = LiveAuthMode.API_KEY,
            )
        } else {
            val installationId = preferences.installationId()
            val token = tokenService.mintToken(
                baseUrl = prefs.backendBaseUrl,
                installationId = installationId,
                model = prefs.geminiModel,
            ).getOrElse { error ->
                fail("Could not get a Gemini session from the JARVIS backend: ${error.message}")
                return false
            }
            request = LiveConnectRequest(
                accessToken = token.token,
                model = token.model,
                systemInstruction = JarvisPrompts.SYSTEM_INSTRUCTION,
                voiceName = prefs.voiceName,
                resumeHandle = resumeHandle,
                authMode = LiveAuthMode.EPHEMERAL_TOKEN,
            )
        }

        val connected = liveClient.connect(request).getOrElse { error ->
            fail(error.message ?: "Gemini Live could not be reached.")
            return false
        }

        session = connected
        sessionStartedAt = System.currentTimeMillis()
        startAudioPipeline()
        eventJob = scope.launch {
            connected.events.collect { event -> handleEvent(event) }
        }
        startSessionTimer()
        _state.update { it.copy(cloudSessionActive = true, error = null, statusMessage = "Connected") }
        return true
    }

    private fun startAudioPipeline() {
        audioJob?.cancel()
        audioQueue?.close()
        playback.reset()
        val queue = Channel<ByteArray>(Channel.UNLIMITED)
        audioQueue = queue
        audioJob = scope.launch(Dispatchers.IO) {
            for (chunk in queue) {
                playback.write(chunk)
            }
        }
    }

    private suspend fun handleEvent(event: LiveEvent) {
        when (event) {
            LiveEvent.SetupComplete -> Unit

            is LiveEvent.AudioChunk -> {
                audioQueue?.trySend(event.pcm16)
                _state.update {
                    if (it.state == VoiceState.SPEAKING) it
                    else it.copy(state = VoiceState.SPEAKING, statusMessage = "Speaking — tap to interrupt")
                }
            }

            is LiveEvent.OutputTranscript -> _state.update {
                it.copy(assistantText = (it.assistantText + event.text).takeLast(600))
            }

            is LiveEvent.InputTranscript -> _state.update {
                it.copy(userText = (it.userText + event.text).takeLast(600))
            }

            LiveEvent.Interrupted -> {
                dropQueuedAudio()
                playback.flushNow()
                _state.update {
                    it.copy(
                        state = if (listening) VoiceState.LISTENING else VoiceState.IDLE,
                        assistantText = "",
                        statusMessage = if (listening) "Listening" else "",
                    )
                }
            }

            LiveEvent.TurnComplete -> {
                if (!listening) {
                    reconnectAttempts = 0
                    _state.update {
                        it.copy(
                            state = VoiceState.IDLE,
                            statusMessage = "",
                            sessionSecondsRemaining = it.sessionSecondsRemaining,
                        )
                    }
                }
            }

            is LiveEvent.ToolCall -> handleToolCall(event)

            is LiveEvent.ResumptionUpdate -> if (event.resumable) resumeHandle = event.handle

            is LiveEvent.GoAway -> _state.update {
                it.copy(statusMessage = "Gemini is closing this session; JARVIS will reconnect.")
            }

            is LiveEvent.Failure -> {
                _state.update { it.copy(error = event.message) }
                attemptReconnect(event.message)
            }

            is LiveEvent.Closed -> {
                if (!intentionalClose) attemptReconnect("The Gemini session ended unexpectedly.")
            }
        }
    }

    private suspend fun handleToolCall(call: LiveEvent.ToolCall) {
        _state.update { it.copy(state = VoiceState.PROCESSING, statusMessage = "Working on it…") }

        when (val parsed = parser.parseFunctionCall(call.name, call.args)) {
            is ParseResult.Rejected -> {
                session?.replyToToolCall(call.id, call.name, "rejected", parsed.reason)
                _state.update { it.copy(assistantText = parsed.reason) }
            }

            is ParseResult.Parsed -> {
                val result = runner.run(parsed.action, ActionSource.VOICE_GEMINI)
                when (result) {
                    is ExecutionResult.Success -> {
                        session?.replyToToolCall(call.id, call.name, "executed", result.message)
                        _state.update { it.copy(assistantText = result.message) }
                    }
                    is ExecutionResult.NeedsConfirmation -> {
                        session?.replyToToolCall(
                            call.id,
                            call.name,
                            "needs_confirmation",
                            "Waiting for the user to approve this on screen. Do not retry.",
                        )
                        _state.update {
                            it.copy(
                                pendingConfirmation = PendingConfirmation(
                                    action = result.action,
                                    preview = result.preview,
                                    reason = result.reason,
                                    fingerprint = ActionSummary.fingerprint(result.action),
                                ),
                                statusMessage = "Waiting for your approval",
                            )
                        }
                    }
                    is ExecutionResult.Denied -> {
                        session?.replyToToolCall(call.id, call.name, "denied", result.reason)
                        _state.update { it.copy(assistantText = result.reason) }
                    }
                    is ExecutionResult.NeedsPermission -> {
                        session?.replyToToolCall(
                            call.id,
                            call.name,
                            "needs_permission",
                            "${result.permission.name.lowercase()} access is not granted.",
                        )
                        _state.update { it.copy(assistantText = result.reason) }
                    }
                    is ExecutionResult.Unsupported -> {
                        session?.replyToToolCall(call.id, call.name, "unsupported", result.reason)
                        _state.update { it.copy(assistantText = result.reason) }
                    }
                    is ExecutionResult.Failed -> {
                        session?.replyToToolCall(call.id, call.name, "failed", result.reason)
                        _state.update { it.copy(assistantText = result.reason) }
                    }
                    is ExecutionResult.VoiceFailure -> {
                        session?.replyToToolCall(call.id, call.name, "failed", result.reason)
                    }
                }
            }
        }
    }

    private fun startSessionTimer() {
        timerJob?.cancel()
        timerJob = scope.launch {
            while (isActive) {
                val elapsed = System.currentTimeMillis() - sessionStartedAt
                val remaining = ((SESSION_LIMIT_MS - elapsed) / 1000).coerceAtLeast(0L).toInt()
                _state.update { it.copy(sessionSecondsRemaining = remaining) }
                if (remaining <= 0) {
                    endSession("Session limit reached. Tap the mic to start a new session.")
                    break
                }
                delay(1000)
            }
        }
    }

    private fun attemptReconnect(reason: String) {
        if (intentionalClose) return
        if (reconnectAttempts >= MAX_RECONNECTS) {
            fail("$reason JARVIS stopped trying after $MAX_RECONNECTS attempts. Local mode still works.")
            return
        }
        reconnectAttempts++
        _state.update {
            it.copy(state = VoiceState.RECONNECTING, statusMessage = "Reconnecting…", error = reason)
        }
        scope.launch {
            delay(1_000L * reconnectAttempts)
            val prefs = preferences.current()
            if (prefs.mode != OperatingMode.GEMINI) {
                endSession("Switched to Local Privacy mode.")
                return@launch
            }
            // The token may have expired while disconnected; never reuse it blindly.
            tokenService.invalidateSession()
            teardownSession()
            if (ensureSession(prefs)) {
                reconnectAttempts = 0
                _state.update { it.copy(state = VoiceState.IDLE, error = null, statusMessage = "Reconnected") }
            } else if (reconnectAttempts < MAX_RECONNECTS) {
                attemptReconnect("Connection failed again.")
            }
        }
    }

    // --------------------------------------------------------------- local path

    private suspend fun startLocalListening(prefs: UserPreferences) {
        if (!capture.hasPermission()) {
            fail("Microphone permission is needed for voice input.")
            return
        }
        if (!localVoice.isAvailable) {
            fail("This device has no on-device speech recogniser, so local voice input is unavailable. Type your command instead.")
            return
        }
        _state.update {
            it.copy(state = VoiceState.LISTENING, statusMessage = "Listening on device…", userText = "", assistantText = "")
        }
        val outcome = localVoice.listenOnce()
        val text = outcome.getOrElse { error ->
            fail(error.message ?: "On-device recognition failed.")
            return
        }
        handleLocalText(text, prefs)
    }

    private suspend fun handleLocalText(text: String, prefs: UserPreferences) {
        _state.update { it.copy(userText = text, state = VoiceState.PROCESSING, statusMessage = "Working on it…") }
        when (val parsed = localParser.parse(text)) {
            is ParseResult.Rejected -> respondLocally(parsed.reason, prefs)
            is ParseResult.Parsed -> {
                val result = runner.run(parsed.action, ActionSource.LOCAL_PARSER)
                if (result is ExecutionResult.NeedsConfirmation) {
                    _state.update {
                        it.copy(
                            pendingConfirmation = PendingConfirmation(
                                action = result.action,
                                preview = result.preview,
                                reason = result.reason,
                                fingerprint = ActionSummary.fingerprint(result.action),
                            ),
                            state = VoiceState.IDLE,
                            statusMessage = "Waiting for your approval",
                        )
                    }
                    return
                }
                respondLocally(messageFor(result), prefs)
            }
        }
    }

    private fun respondLocally(message: String, prefs: UserPreferences) {
        _state.update { it.copy(state = VoiceState.IDLE, assistantText = message, statusMessage = "", error = null) }
        if (prefs.speakRepliesInLocalMode) tts.speak(message)
    }

    // ------------------------------------------------------------------ helpers

    private fun messageFor(result: ExecutionResult): String = when (result) {
        is ExecutionResult.Success -> result.spokenText
        is ExecutionResult.Denied -> result.reason
        is ExecutionResult.NeedsPermission -> result.reason
        is ExecutionResult.Unsupported -> result.reason
        is ExecutionResult.Failed -> result.reason
        is ExecutionResult.NeedsConfirmation -> result.preview
        is ExecutionResult.VoiceFailure -> result.reason
    }

    private fun dropQueuedAudio() {
        val queue = audioQueue ?: return
        while (queue.tryReceive().isSuccess) {
            // Drain anything that has not reached the speaker yet.
        }
    }

    private fun teardownSession() {
        eventJob?.cancel()
        eventJob = null
        audioJob?.cancel()
        audioJob = null
        audioQueue?.close()
        audioQueue = null
        timerJob?.cancel()
        timerJob = null
        session?.close()
        session = null
        playback.release()
        _state.update { it.copy(cloudSessionActive = false, sessionSecondsRemaining = null) }
    }

    private fun fail(message: String) {
        _state.update { it.copy(state = VoiceState.ERROR, error = message, statusMessage = message) }
        if (session?.isOpen != true) {
            capture.stop()
        }
    }

    private companion object {
        const val SESSION_LIMIT_MS = 15 * 60 * 1000L
        const val MAX_RECONNECTS = 3
    }
}
