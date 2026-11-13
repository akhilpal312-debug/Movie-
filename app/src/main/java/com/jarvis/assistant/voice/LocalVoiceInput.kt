package com.jarvis.assistant.voice

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * On-device speech recognition for Local Privacy mode.
 *
 * Only the platform's *on-device* recogniser is used (`createOnDeviceSpeechRecognizer`,
 * Android 12+). If the device has no on-device recogniser, JARVIS refuses to listen
 * rather than quietly streaming audio to a cloud recogniser, which would break the
 * promise of local mode.
 */
@Singleton
class LocalVoiceInput @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    val isAvailable: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

    suspend fun listenOnce(timeoutMillis: Long = 20_000): Result<String> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return Result.failure(
                IOException("Local voice input needs Android 12 or newer. Type your command instead."),
            )
        }
        if (!SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
            return Result.failure(
                IOException(
                    "This device has no on-device speech recogniser. Type your command, or install one from system settings.",
                ),
            )
        }

        val deferred = CompletableDeferred<String>()
        withContext(Dispatchers.Main) {
            val recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) = Unit
                override fun onBeginningOfSpeech() = Unit
                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() = Unit
                override fun onEvent(eventType: Int, params: Bundle?) = Unit
                override fun onPartialResults(partialResults: Bundle?) = Unit

                override fun onResults(results: Bundle?) {
                    val text = results
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                        .orEmpty()
                    deferred.complete(text)
                    runCatching { recognizer.destroy() }
                }

                override fun onError(error: Int) {
                    deferred.completeExceptionally(
                        IOException("On-device recognition failed (code $error)."),
                    )
                    runCatching { recognizer.destroy() }
                }
            })

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                // Hinglish is best served by the Indian English model on most devices.
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN")
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            }
            runCatching { recognizer.startListening(intent) }
                .onFailure { error -> deferred.completeExceptionally(error) }
        }

        return runCatching { withTimeout(timeoutMillis) { deferred.await() } }
            .mapCatching { text ->
                if (text.isBlank()) throw IOException("I did not catch that.") else text
            }
    }
}
