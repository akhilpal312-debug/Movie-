package com.jarvis.assistant.device

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.media.AudioManager
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.view.KeyEvent
import com.jarvis.assistant.core.model.AudioStream
import com.jarvis.assistant.core.model.ExecutionResult
import com.jarvis.assistant.core.model.MediaCommand
import com.jarvis.assistant.permissions.PermissionRegistry
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

@Singleton
class AudioController @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val audioManager: AudioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val notificationManager: NotificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    fun setVolume(stream: AudioStream, percent: Int): ExecutionResult {
        val androidStream = streamOf(stream)
        val max = audioManager.getStreamMaxVolume(androidStream)
        if (max <= 0) return ExecutionResult.Unsupported("This device does not expose a ${stream.name.lowercase()} volume.")
        val index = (max * percent.coerceIn(0, 100) / 100f).toInt().coerceIn(0, max)
        return runCatching {
            audioManager.setStreamVolume(androidStream, index, 0)
            val actual = (audioManager.getStreamVolume(androidStream) * 100f / max).toInt()
            ExecutionResult.Success("${stream.name.lowercase().replaceFirstChar(Char::uppercase)} volume is now $actual%.")
        }.getOrElse { ExecutionResult.Failed("Android refused the volume change.") }
    }

    fun adjustVolume(stream: AudioStream, stepPercent: Int): ExecutionResult {
        val androidStream = streamOf(stream)
        val max = audioManager.getStreamMaxVolume(androidStream)
        if (max <= 0) return ExecutionResult.Unsupported("This device does not expose a ${stream.name.lowercase()} volume.")
        val currentPercent = audioManager.getStreamVolume(androidStream) * 100 / max
        return setVolume(stream, currentPercent + stepPercent)
    }

    fun setDoNotDisturb(enabled: Boolean): ExecutionResult {
        if (!notificationManager.isNotificationPolicyAccessGranted) {
            return ExecutionResult.Unsupported(
                "Do Not Disturb access is not granted, so Android will not let JARVIS change it.",
            )
        }
        return runCatching {
            notificationManager.setInterruptionFilter(
                if (enabled) {
                    NotificationManager.INTERRUPTION_FILTER_PRIORITY
                } else {
                    NotificationManager.INTERRUPTION_FILTER_ALL
                },
            )
            ExecutionResult.Success(if (enabled) "Do Not Disturb is on." else "Do Not Disturb is off.")
        }.getOrElse { ExecutionResult.Failed("Android refused the Do Not Disturb change.") }
    }

    /**
     * Media control goes through the active media session when notification access
     * is available. Without it Android blocks background media dispatch, and JARVIS
     * says so instead of pretending the command worked.
     */
    fun media(command: MediaCommand): ExecutionResult {
        val controller = activeController()
        if (controller != null) {
            val transport = controller.transportControls
            return runCatching {
                when (command) {
                    MediaCommand.PLAY -> transport.play()
                    MediaCommand.PAUSE -> transport.pause()
                    MediaCommand.TOGGLE ->
                        if (controller.playbackState?.state == PlaybackState.STATE_PLAYING) {
                            transport.pause()
                        } else {
                            transport.play()
                        }
                    MediaCommand.NEXT -> transport.skipToNext()
                    MediaCommand.PREVIOUS -> transport.skipToPrevious()
                    MediaCommand.STOP -> transport.stop()
                }
                ExecutionResult.Success(
                    when (command) {
                        MediaCommand.TOGGLE -> "Media toggled."
                        else -> "Media ${command.name.lowercase()} sent."
                    },
                )
            }.getOrElse { ExecutionResult.Failed("The media app rejected the command.") }
        }

        // Fallback for devices without notification access: a media key event only
        // works while JARVIS is in the foreground, and may be ignored otherwise.
        val keyCode = when (command) {
            MediaCommand.PLAY, MediaCommand.TOGGLE, MediaCommand.PAUSE -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
            MediaCommand.NEXT -> KeyEvent.KEYCODE_MEDIA_NEXT
            MediaCommand.PREVIOUS -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            MediaCommand.STOP -> KeyEvent.KEYCODE_MEDIA_STOP
        }
        return runCatching {
            audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
            audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
            ExecutionResult.Success("Media ${command.name.lowercase()} sent to the active player.")
        }.getOrElse {
            ExecutionResult.Unsupported(
                "Android blocks media control without notification access. Grant it in Permission Manager to use this.",
            )
        }
    }

    private fun activeController(): MediaController? {
        if (!PermissionRegistry.hasNotificationListenerAccess(context)) return null
        return runCatching {
            val manager = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            val component = ComponentName(context, JarvisNotificationListenerService::class.java)
            manager.getActiveSessions(component)
                .filter { it.playbackState?.state != PlaybackState.STATE_NONE }
                .maxByOrNull { it.playbackState?.lastPositionUpdateTime ?: 0L }
        }.getOrNull()
    }

    private fun streamOf(stream: AudioStream): Int = when (stream) {
        AudioStream.MUSIC -> AudioManager.STREAM_MUSIC
        AudioStream.RING -> AudioManager.STREAM_RING
        AudioStream.ALARM -> AudioManager.STREAM_ALARM
        AudioStream.NOTIFICATION -> AudioManager.STREAM_NOTIFICATION
        AudioStream.CALL -> AudioManager.STREAM_VOICE_CALL
        AudioStream.SYSTEM -> AudioManager.STREAM_SYSTEM
    }

    @Suppress("unused")
    private fun nearlyEqual(a: Int, b: Int) = abs(a - b) < 2
}
