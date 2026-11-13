package com.jarvis.assistant.voice

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.jarvis.assistant.ai.LiveProtocol
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Plays the 24 kHz PCM16 audio Gemini Live streams back.
 *
 * [flushNow] is the barge-in primitive: it discards everything still queued so the
 * assistant goes quiet the instant the user interrupts.
 */
@Singleton
class AudioPlayback @Inject constructor() {

    private var track: AudioTrack? = null

    @Volatile
    private var released = false

    @Synchronized
    private fun ensureTrack(): AudioTrack? {
        if (released) return null
        track?.let { return it }
        val minBuffer = AudioTrack.getMinBufferSize(
            LiveProtocol.OUTPUT_SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuffer <= 0) return null

        val created = runCatching {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(LiveProtocol.OUTPUT_SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setBufferSizeInBytes(minBuffer * 2)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        }.getOrNull() ?: return null

        runCatching { created.play() }
        track = created
        return created
    }

    /** Writes one chunk; call from an IO dispatcher because it blocks while buffering. */
    fun write(pcm16: ByteArray) {
        val target = ensureTrack() ?: return
        val samples = PcmResampler.bytesToShorts(pcm16)
        if (samples.isEmpty()) return
        runCatching { target.write(samples, 0, samples.size) }
    }

    /** Immediate silence: drop whatever is queued and anything still buffered. */
    @Synchronized
    fun flushNow() {
        val target = track ?: return
        runCatching {
            target.pause()
            target.flush()
            target.play()
        }
    }

    @Synchronized
    fun release() {
        released = true
        track?.let { target ->
            runCatching { target.stop() }
            runCatching { target.release() }
        }
        track = null
    }

    /** Allows a new session after [release]. */
    @Synchronized
    fun reset() {
        released = false
        track = null
    }
}
