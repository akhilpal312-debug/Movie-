package com.jarvis.assistant.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.jarvis.assistant.BuildConfig
import com.jarvis.assistant.core.model.OperatingMode
import com.jarvis.assistant.core.model.UserPreferences
import com.jarvis.assistant.security.SecretCipher
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

private val Context.jarvisDataStore: DataStore<Preferences> by preferencesDataStore("jarvis_preferences")

/**
 * Single source of truth for mode, consent and policy preferences.
 *
 * Local mode is the default. Gemini voice mode only becomes active after the user
 * has read the disclosure and recorded consent, and switching modes is always an
 * explicit user action.
 */
@Singleton
class PreferencesRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    /** Keystore-backed cipher used only for the optional bring-your-own Gemini key. */
    private val cipher: SecretCipher,
) {
    private object Keys {
        val MODE = stringPreferencesKey("mode")
        val GEMINI_CONSENT_AT = longPreferencesKey("gemini_consent_at")
        val MODEL = stringPreferencesKey("gemini_model")
        val VOICE = stringPreferencesKey("gemini_voice")
        val BACKEND = stringPreferencesKey("backend_base_url")
        val PAUSE_HIGH_RISK = booleanPreferencesKey("pause_high_risk")
        val AUTO_MEDIUM = booleanPreferencesKey("auto_medium_risk")
        val NOTIFICATION_CONSENT = booleanPreferencesKey("notification_consent")
        val NOTIFICATION_BLOCKED = androidx.datastore.preferences.core.stringSetPreferencesKey("notification_blocked")
        val CONTACTS_CONSENT = booleanPreferencesKey("contacts_consent")
        val SPEAK_LOCAL = booleanPreferencesKey("speak_local")
        val MAX_ACTIONS = intPreferencesKey("max_actions_per_minute")
        val ONBOARDED = booleanPreferencesKey("onboarding_complete")
        val INSTALLATION_ID = stringPreferencesKey("installation_id")
        val DIRECT_KEY_CIPHER = stringPreferencesKey("direct_api_key_cipher")
        val USE_DIRECT_KEY = booleanPreferencesKey("use_direct_api_key")
    }

    val preferences: Flow<UserPreferences> = context.jarvisDataStore.data.map { prefs ->
        UserPreferences(
            mode = prefs[Keys.MODE]?.let { runCatching { OperatingMode.valueOf(it) }.getOrNull() }
                ?: OperatingMode.LOCAL,
            geminiConsentGrantedAt = prefs[Keys.GEMINI_CONSENT_AT],
            geminiModel = prefs[Keys.MODEL] ?: BuildConfig.DEFAULT_GEMINI_MODEL,
            voiceName = prefs[Keys.VOICE] ?: "Aoede",
            backendBaseUrl = prefs[Keys.BACKEND] ?: BuildConfig.DEFAULT_BACKEND_BASE_URL,
            pauseAndConfirmHighRisk = prefs[Keys.PAUSE_HIGH_RISK] ?: true,
            autoApproveMediumRisk = prefs[Keys.AUTO_MEDIUM] ?: false,
            notificationAccessConsent = prefs[Keys.NOTIFICATION_CONSENT] ?: false,
            notificationBlockedPackages = prefs[Keys.NOTIFICATION_BLOCKED] ?: emptySet(),
            contactsConsent = prefs[Keys.CONTACTS_CONSENT] ?: false,
            speakRepliesInLocalMode = prefs[Keys.SPEAK_LOCAL] ?: true,
            maxActionsPerMinute = prefs[Keys.MAX_ACTIONS] ?: 20,
            onboardingComplete = prefs[Keys.ONBOARDED] ?: false,
            directApiKeyPresent = !prefs[Keys.DIRECT_KEY_CIPHER].isNullOrBlank(),
            useDirectApiKey = (prefs[Keys.USE_DIRECT_KEY] ?: false) &&
                !prefs[Keys.DIRECT_KEY_CIPHER].isNullOrBlank(),
        )
    }

    suspend fun current(): UserPreferences = preferences.first()

    suspend fun setMode(mode: OperatingMode) = edit { prefs ->
        prefs[Keys.MODE] = mode.name
        if (mode == OperatingMode.GEMINI && (prefs[Keys.GEMINI_CONSENT_AT] ?: 0L) == 0L) {
            prefs[Keys.GEMINI_CONSENT_AT] = System.currentTimeMillis()
        }
    }

    suspend fun grantGeminiConsent() = edit { prefs ->
        prefs[Keys.GEMINI_CONSENT_AT] = System.currentTimeMillis()
        prefs[Keys.MODE] = OperatingMode.GEMINI.name
    }

    suspend fun revokeGeminiConsentAndUseLocal() = edit { prefs ->
        prefs[Keys.MODE] = OperatingMode.LOCAL.name
    }

    suspend fun setModel(model: String) = edit { prefs ->
        prefs[Keys.MODEL] = model.trim()
    }

    suspend fun setVoice(voice: String) = edit { prefs -> prefs[Keys.VOICE] = voice.trim() }

    suspend fun setBackendUrl(url: String) = edit { prefs ->
        prefs[Keys.BACKEND] = url.trim().trimEnd('/')
    }

    suspend fun setPauseHighRisk(pause: Boolean) = edit { prefs -> prefs[Keys.PAUSE_HIGH_RISK] = pause }

    suspend fun setAutoApproveMediumRisk(enabled: Boolean) =
        edit { prefs -> prefs[Keys.AUTO_MEDIUM] = enabled }

    suspend fun setNotificationConsent(granted: Boolean) = edit { prefs ->
        prefs[Keys.NOTIFICATION_CONSENT] = granted
    }

    suspend fun setContactsConsent(granted: Boolean) = edit { prefs ->
        prefs[Keys.CONTACTS_CONSENT] = granted
    }

    /**
     * Per-app notification control. Even with notification access granted, JARVIS will not
     * read a notification whose package appears here.
     */
    suspend fun setNotificationBlocked(packageName: String, blocked: Boolean) = edit { prefs ->
        val current = prefs[Keys.NOTIFICATION_BLOCKED] ?: emptySet()
        prefs[Keys.NOTIFICATION_BLOCKED] =
            if (blocked) current + packageName else current - packageName
    }

    suspend fun setSpeakLocal(speak: Boolean) = edit { prefs -> prefs[Keys.SPEAK_LOCAL] = speak }

    suspend fun setMaxActionsPerMinute(value: Int) = edit { prefs ->
        prefs[Keys.MAX_ACTIONS] = value.coerceIn(1, 60)
    }

    suspend fun setOnboardingComplete() = edit { prefs -> prefs[Keys.ONBOARDED] = true }

    /**
     * Stores the user's own Gemini API key, encrypted with the Android Keystore.
     *
     * This is the opt-in alternative to the token broker. The plaintext never leaves
     * this method except through [directApiKey], is never logged, and is sent only to
     * Google's Live API endpoint.
     */
    suspend fun setDirectApiKey(rawKey: String) {
        val key = rawKey.trim()
        require(key.isNotEmpty()) { "API key is empty" }
        edit { prefs ->
            prefs[Keys.DIRECT_KEY_CIPHER] = cipher.encrypt(key)
            prefs[Keys.USE_DIRECT_KEY] = true
        }
    }

    suspend fun clearDirectApiKey() = edit { prefs ->
        prefs.remove(Keys.DIRECT_KEY_CIPHER)
        prefs[Keys.USE_DIRECT_KEY] = false
    }

    suspend fun setUseDirectApiKey(enabled: Boolean) = edit { prefs ->
        prefs[Keys.USE_DIRECT_KEY] = enabled
    }

    /** Decrypts the stored key for one connection attempt. Returns null when absent. */
    suspend fun directApiKey(): String? {
        val stored = context.jarvisDataStore.data.first()[Keys.DIRECT_KEY_CIPHER] ?: return null
        return cipher.decrypt(stored)
    }

    /**
     * A random identifier created on first launch. It is not a hardware ID and is
     * only used to scope the backend session that mints Gemini tokens.
     */
    suspend fun installationId(): String {
        context.jarvisDataStore.data.first()[Keys.INSTALLATION_ID]?.let { return it }
        val generated = UUID.randomUUID().toString().lowercase(Locale.ROOT)
        edit { prefs -> prefs[Keys.INSTALLATION_ID] = generated }
        return generated
    }

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.jarvisDataStore.edit { prefs -> block(prefs) }
    }
}
