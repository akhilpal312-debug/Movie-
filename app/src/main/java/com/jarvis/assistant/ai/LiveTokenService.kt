package com.jarvis.assistant.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.time.Instant
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** A short-lived Live API credential issued by the JARVIS backend. */
data class LiveToken(
    val token: String,
    val model: String,
    val expiresAtMillis: Long,
) {
    fun isUsable(now: Long = System.currentTimeMillis()): Boolean = now < expiresAtMillis - 30_000
}

/**
 * Talks to the JARVIS backend only.
 *
 * The Android app never holds a Gemini API key. It authenticates as an app session
 * (a random per-install identifier, not a hardware ID) and receives a short-lived
 * token that is scoped to one Live session.
 */
@Singleton
class LiveTokenService @Inject constructor(
    private val httpClient: OkHttpClient,
) {
    private val mutex = Mutex()
    private var sessionToken: String? = null
    private var sessionExpiresAt: Long = 0

    suspend fun mintToken(
        baseUrl: String,
        installationId: String,
        model: String,
    ): Result<LiveToken> = mutex.withLock {
        if (baseUrl.isBlank()) {
            return Result.failure(IOException("No JARVIS backend configured"))
        }
        return@withLock withContext(Dispatchers.IO) {
            runCatching {
                val session = ensureSession(baseUrl, installationId)
                requestToken(baseUrl, session, model, installationId)
            }
        }
    }

    /** Called when the backend rejects the session so the next attempt re-authenticates. */
    fun invalidateSession() {
        sessionToken = null
        sessionExpiresAt = 0
    }

    private fun ensureSession(baseUrl: String, installationId: String): String {
        val current = sessionToken
        if (current != null && System.currentTimeMillis() < sessionExpiresAt - 30_000) return current

        val body = JSONObject()
            .put("installationId", installationId)
            .put("client", "jarvis-android")
            .toString()
            .toRequestBody(JSON)

        val request = Request.Builder()
            .url("${baseUrl.trimEnd('/')}/api/session")
            .post(body)
            .build()

        httpClient.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IOException("Session request failed (${response.code})")
            }
            val json = JSONObject(text)
            val token = json.optString("sessionToken")
            if (token.isBlank()) throw IOException("Backend returned no session token")
            val ttlSeconds = json.optLong("expiresInSeconds", 900)
            sessionToken = token
            sessionExpiresAt = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(ttlSeconds)
            return token
        }
    }

    private fun requestToken(
        baseUrl: String,
        session: String,
        model: String,
        installationId: String,
    ): LiveToken {
        val payload = JSONObject()
            .put("model", model)
            .put("purpose", "live-voice")
            .toString()
            .toRequestBody(JSON)

        val request = Request.Builder()
            .url("${baseUrl.trimEnd('/')}/api/gemini/live-token")
            .header("Authorization", "Bearer $session")
            .header("X-Jarvis-Install-Id", installationId)
            .post(payload)
            .build()

        httpClient.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (response.code == 401 || response.code == 403) {
                invalidateSession()
                throw IOException("Backend rejected the app session (${response.code})")
            }
            if (!response.isSuccessful) {
                val reason = runCatching { JSONObject(text).optString("error") }.getOrNull().orEmpty()
                throw IOException(
                    if (reason.isNotBlank()) "Token service: $reason" else "Token request failed (${response.code})",
                )
            }
            val json = JSONObject(text)
            val token = json.optString("token")
            if (token.isBlank()) throw IOException("Backend returned an empty token")
            val expiresAt = json.optString("expiresAt").let { value ->
                runCatching { Instant.parse(value).toEpochMilli() }
                    .getOrElse { System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(30) }
            }
            return LiveToken(
                token = token,
                model = json.optString("model").ifBlank { model },
                expiresAtMillis = expiresAt,
            )
        }
    }

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
