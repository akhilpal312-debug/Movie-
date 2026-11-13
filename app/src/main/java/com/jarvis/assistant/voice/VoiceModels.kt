package com.jarvis.assistant.voice

import com.jarvis.assistant.core.model.JarvisAction
import com.jarvis.assistant.core.model.OperatingMode
import com.jarvis.assistant.core.model.VoiceState

/** An action waiting for the user's explicit approval. */
data class PendingConfirmation(
    val action: JarvisAction,
    val preview: String,
    val reason: String,
    val fingerprint: String,
)

/** Everything the UI needs to render the current voice session truthfully. */
data class VoiceUiState(
    val state: VoiceState = VoiceState.IDLE,
    val mode: OperatingMode = OperatingMode.LOCAL,
    val userText: String = "",
    val assistantText: String = "",
    val statusMessage: String = "",
    val error: String? = null,
    val pendingConfirmation: PendingConfirmation? = null,
    val sessionSecondsRemaining: Int? = null,
    val cloudSessionActive: Boolean = false,
    val localVoiceAvailable: Boolean = false,
) {
    val isBusy: Boolean
        get() = state == VoiceState.CONNECTING ||
            state == VoiceState.LISTENING ||
            state == VoiceState.PROCESSING ||
            state == VoiceState.SPEAKING ||
            state == VoiceState.RECONNECTING
}
