package com.jarvis.assistant.core

import com.jarvis.assistant.core.model.ActionSource
import com.jarvis.assistant.core.model.ExecutionResult
import com.jarvis.assistant.core.model.JarvisAction

/**
 * The single door to device control. Every caller — Gemini tool calls, the local
 * parser, automations and the UI — goes through this, so the privacy core cannot be
 * bypassed by adding a new entry point.
 */
interface ActionRunner {
    suspend fun run(
        action: JarvisAction,
        source: ActionSource,
        confirmedFingerprint: String? = null,
    ): ExecutionResult
}
