package com.jarvis.assistant.core.model

/** One consistent, honest rendering of an execution outcome for the UI. */
fun ExecutionResult.userMessage(): String = when (this) {
    is ExecutionResult.Success -> message
    is ExecutionResult.Denied -> "Blocked: $reason"
    is ExecutionResult.NeedsPermission -> "Needs access: $reason"
    is ExecutionResult.Unsupported -> "Not supported here: $reason"
    is ExecutionResult.NeedsConfirmation -> "Needs confirmation: $preview"
    is ExecutionResult.Failed -> "Failed: $reason"
    is ExecutionResult.VoiceFailure -> reason
}
