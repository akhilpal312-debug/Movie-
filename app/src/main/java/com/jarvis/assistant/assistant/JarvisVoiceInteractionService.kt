package com.jarvis.assistant.assistant

import android.os.Bundle
import android.service.voice.VoiceInteractionService

/**
 * Bound by the system only while JARVIS holds ROLE_ASSISTANT.
 *
 * The heavy lifting lives in the session (see [JarvisVoiceInteractionSession]) which
 * reuses the same [com.jarvis.assistant.voice.VoiceSessionController] as the app, so
 * assistant invocations obey exactly the same privacy rules.
 */
class JarvisVoiceInteractionService : VoiceInteractionService() {

    override fun onReady() {
        super.onReady()
    }

    override fun onShutdown() {
        super.onShutdown()
    }

    override fun onLaunchVoiceAssistFromKeyguard() {
        // Deliberately not supported: JARVIS never listens from the lock screen,
        // because the device owner has not necessarily consented to that mic use.
    }

    override fun onGetSupportedVoiceActions(voiceActions: MutableSet<String>): Set<String> {
        super.onGetSupportedVoiceActions(voiceActions)
        return voiceActions
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
    }

    @Suppress("unused")
    private fun unusedBundle(): Bundle = Bundle()
}
