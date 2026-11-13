package com.jarvis.assistant.core.model

/** Result of a fully authorised action run against Android APIs. */
sealed interface ExecutionResult {
    /** Action ran and produced a user-facing (and locally stored) result. */
    data class Success(val message: String, val spokenText: String = message) : ExecutionResult

    /** Android blocked it or the feature does not exist on this device/build. */
    data class Unsupported(val reason: String) : ExecutionResult

    /** Required runtime permission or special access is missing. */
    data class NeedsPermission(val permission: PermissionKey, val reason: String) : ExecutionResult

    /** The user must approve this specific action before it runs. */
    data class NeedsConfirmation(
        val reason: String,
        val preview: String,
        val action: JarvisAction,
    ) : ExecutionResult

    /** Refused by the privacy core or by local validation. */
    data class Denied(val reason: String, val code: String) : ExecutionResult

    /** Unexpected failure, already logged without private content. */
    data class Failed(val reason: String) : ExecutionResult

    /** Recoverable or fatal failure while talking to Gemini. */
    data class VoiceFailure(val reason: String, val recoverable: Boolean) : ExecutionResult
}

/** The visible state of a voice session. Never hidden from the user. */
enum class VoiceState {
    IDLE,
    CONNECTING,
    LISTENING,
    PROCESSING,
    SPEAKING,
    RECONNECTING,
    ERROR,
}
