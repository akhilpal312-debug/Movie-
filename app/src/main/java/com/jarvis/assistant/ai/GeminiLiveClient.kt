package com.jarvis.assistant.ai

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Opens one `BidiGenerateContent` session over the ephemeral token minted by the
 * JARVIS backend. The token is a short-lived credential, never an API key.
 */
@Singleton
class GeminiLiveClient @Inject constructor(
    private val httpClient: OkHttpClient,
) {

    suspend fun connect(request: LiveConnectRequest): Result<LiveSession> {
        if (request.accessToken.isBlank()) {
            return Result.failure(IOException("Missing Live API token"))
        }

        val opened = CompletableDeferred<Unit>()
        val ready = CompletableDeferred<Unit>()
        val events = MutableSharedFlow<LiveEvent>(
            extraBufferCapacity = 512,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                opened.complete(Unit)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                LiveProtocol.parseServerMessage(text).forEach(::dispatch)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                LiveProtocol.parseServerMessage(bytes.utf8()).forEach(::dispatch)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                events.tryEmit(LiveEvent.Closed(code, reason))
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                events.tryEmit(LiveEvent.Closed(code, reason))
                if (!ready.isCompleted) {
                    ready.completeExceptionally(IOException("Socket closed before setup (code $code)"))
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val message = response?.let { "Gemini connection failed with HTTP ${it.code}" }
                    ?: t.message ?: "Gemini connection failed"
                if (!opened.isCompleted) opened.completeExceptionally(t)
                if (!ready.isCompleted) ready.completeExceptionally(IOException(message))
                events.tryEmit(LiveEvent.Failure(message, response?.code))
            }

            private fun dispatch(event: LiveEvent) {
                if (event is LiveEvent.SetupComplete && !ready.isCompleted) ready.complete(Unit)
                events.tryEmit(event)
            }
        }

        val wsRequest = Request.Builder().url(LiveProtocol.endpoint(request)).build()
        val socket = httpClient.newWebSocket(wsRequest, listener)

        return try {
            withTimeout(10_000) { opened.await() }
            socket.send(LiveProtocol.setupMessage(request))
            withTimeout(15_000) { ready.await() }
            Result.success(LiveSession(socket, events.asSharedFlow()))
        } catch (timeout: TimeoutCancellationException) {
            socket.cancel()
            Result.failure(IOException("Gemini Live did not finish setup in time"))
        } catch (error: Throwable) {
            socket.cancel()
            Result.failure(error)
        }
    }
}

/**
 * A live conversation. Audio is only sent between [beginUserSpeech] and
 * [endUserSpeech]; playback is discarded immediately on barge-in.
 */
class LiveSession internal constructor(
    private val socket: WebSocket,
    val events: SharedFlow<LiveEvent>,
) {
    private val closed = AtomicBoolean(false)

    val isOpen: Boolean get() = !closed.get()

    fun sendAudio(pcm16: ByteArray) {
        send(LiveProtocol.audioMessage(pcm16))
    }

    fun beginUserSpeech() {
        send(LiveProtocol.activityStart())
    }

    fun endUserSpeech() {
        send(LiveProtocol.activityEnd())
    }

    fun sendText(text: String) {
        send(LiveProtocol.textTurn(text))
    }

    fun replyToToolCall(id: String, name: String, status: String, message: String) {
        send(LiveProtocol.toolResponse(id, name, status, message))
    }

    fun close() {
        if (closed.compareAndSet(false, true)) {
            socket.close(1000, "JARVIS session ended")
            socket.cancel()
        }
    }

    private fun send(frame: String) {
        if (closed.get()) return
        // OkHttp buffers frames; false means the socket is already gone.
        socket.send(frame)
    }
}
