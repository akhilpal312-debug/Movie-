package com.jarvis.assistant.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import androidx.core.content.ContextCompat
import com.jarvis.assistant.ai.LiveProtocol
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Microphone capture for one push-to-talk turn.
 *
 * Audio is read only between [start] and [stop]; there is no background recording
 * path anywhere in the app. The device's native rate is used and converted to the
 * 16 kHz mono PCM16 the Live API requires, with 40 ms chunks.
 */
@Singleton
class AudioCapture @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private var recorder: AudioRecord? = null
    private var job: Job? = null

    @Volatile
    private var running = false

    @Volatile
    private var nativeRate = LiveProtocol.INPUT_SAMPLE_RATE

    val isRunning: Boolean get() = running

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    fun start(
        scope: CoroutineScope,
        onChunk: (ShortArray) -> Unit,
        onError: (String) -> Unit,
    ): Boolean {
        if (running) return true
        if (!hasPermission()) {
            onError("Microphone permission is not granted.")
            return false
        }

        val created = openRecorder()
        if (created == null) {
            onError("This device would not open the microphone.")
            return false
        }

        recorder = created
        running = true
        job = scope.launch(Dispatchers.IO) {
            val chunkSamples = (nativeRate * CHUNK_MS / 1000).coerceAtLeast(160)
            val buffer = ShortArray(chunkSamples)
            try {
                created.startRecording()
                while (isActive && running) {
                    val read = created.read(buffer, 0, buffer.size)
                    if (read <= 0) continue
                    val chunk = if (read == buffer.size) buffer.copyOf() else buffer.copyOf(read)
                    val resampled = PcmResampler.resample(chunk, nativeRate, LiveProtocol.INPUT_SAMPLE_RATE)
                    if (resampled.isNotEmpty()) onChunk(resampled)
                }
            } catch (error: Throwable) {
                if (running) onError("Microphone stopped unexpectedly.")
            } finally {
                runCatching { created.stop() }
            }
        }
        return true
    }

    fun stop() {
        running = false
        job?.cancel()
        job = null
        recorder?.let { record ->
            runCatching { if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) record.stop() }
            runCatching { record.release() }
        }
        recorder = null
    }

    private fun openRecorder(): AudioRecord? {
        val candidates = intArrayOf(
            LiveProtocol.INPUT_SAMPLE_RATE,
            44_100,
            48_000,
        )
        for (rate in candidates) {
            val minBuffer = AudioRecord.getMinBufferSize(
                rate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            if (minBuffer <= 0) continue

            val record = runCatching {
                val builder = AudioRecord.Builder()
                    .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(rate)
                            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                            .build(),
                    )
                    .setBufferSizeInBytes(minBuffer * 4)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    builder.setPrivacySensitive(true)
                }
                builder.build()
            }.getOrNull() ?: continue

            if (record.state == AudioRecord.STATE_INITIALIZED) {
                nativeRate = rate
                return record
            }
            runCatching { record.release() }
        }
        return null
    }

    private companion object {
        const val CHUNK_MS = 40
    }
}
