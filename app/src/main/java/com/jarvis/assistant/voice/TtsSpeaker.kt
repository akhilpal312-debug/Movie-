package com.jarvis.assistant.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Local text-to-speech.
 *
 * Used in Local Privacy mode and whenever a locally executed action needs a spoken
 * confirmation without involving Gemini. It never runs while Gemini audio is playing,
 * so the two voices never overlap.
 */
@Singleton
class TtsSpeaker @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private var tts: TextToSpeech? = null
    private var ready = false

    @Volatile
    var isSpeaking: Boolean = false
        private set

    private fun ensureInitialised() {
        if (tts != null) return
        tts = TextToSpeech(context) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (ready) {
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) { isSpeaking = true }
                    override fun onDone(utteranceId: String?) { isSpeaking = false }
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) { isSpeaking = false }
                })
            }
        }
    }

    fun speak(text: String) {
        val clean = text.trim().take(500)
        if (clean.isEmpty()) return
        ensureInitialised()
        val engine = tts ?: return
        if (!ready) return

        val locale = if (clean.any { it.code in 0x0900..0x097F }) Locale("hi", "IN") else Locale.getDefault()
        val result = engine.setLanguage(locale)
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            engine.setLanguage(Locale.getDefault())
        }
        engine.speak(clean, TextToSpeech.QUEUE_FLUSH, null, UUID.randomUUID().toString())
        isSpeaking = true
    }

    fun stop() {
        tts?.stop()
        isSpeaking = false
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        ready = false
    }
}
