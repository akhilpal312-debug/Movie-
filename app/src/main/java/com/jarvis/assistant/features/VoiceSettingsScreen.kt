package com.jarvis.assistant.features

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.jarvis.assistant.ai.LiveTokenService
import com.jarvis.assistant.core.model.OperatingMode
import com.jarvis.assistant.core.model.UserPreferences
import com.jarvis.assistant.settings.PreferencesRepository
import com.jarvis.assistant.ui.components.GlassCard
import com.jarvis.assistant.ui.components.InfoRow
import com.jarvis.assistant.ui.components.JarvisScreen
import com.jarvis.assistant.ui.components.Paragraph
import com.jarvis.assistant.ui.components.ToggleRow
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class VoiceSettingsViewModel @Inject constructor(
    private val preferences: PreferencesRepository,
    private val tokenService: LiveTokenService,
) : ViewModel() {

    val preferencesFlow: StateFlow<UserPreferences> = preferences.preferences
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UserPreferences())

    private val _testResult = MutableStateFlow<String?>(null)
    val testResult: StateFlow<String?> = _testResult.asStateFlow()

    private val _testing = MutableStateFlow(false)
    val testing: StateFlow<Boolean> = _testing.asStateFlow()

    private val _keyStatus = MutableStateFlow<String?>(null)
    val keyStatus: StateFlow<String?> = _keyStatus.asStateFlow()

    /**
     * Saves the user's own Gemini key, encrypted with the Android Keystore.
     * The value is validated for shape only; it is never echoed back to the screen.
     */
    fun saveDirectKey(raw: String) {
        val key = raw.trim()
        when {
            key.isEmpty() -> _keyStatus.value = "Paste your Gemini API key first."
            key.any { it.isWhitespace() } -> _keyStatus.value = "That does not look like a single key."
            key.length < 20 -> _keyStatus.value = "That key looks too short to be valid."
            else -> viewModelScope.launch {
                preferences.setDirectApiKey(key)
                _keyStatus.value = "Saved. The key is encrypted on this device and used for the next session."
            }
        }
    }

    fun clearDirectKey() {
        viewModelScope.launch {
            preferences.clearDirectApiKey()
            _keyStatus.value = "Key removed from this device."
        }
    }

    fun setUseDirectKey(enabled: Boolean) {
        viewModelScope.launch {
            preferences.setUseDirectApiKey(enabled)
            _keyStatus.value = if (enabled) {
                "Using your own key. The JARVIS backend is bypassed."
            } else {
                "Using the JARVIS backend for short-lived tokens."
            }
        }
    }

    fun saveModel(value: String) = viewModelScope.launch { preferences.setModel(value) }

    fun saveVoice(value: String) = viewModelScope.launch { preferences.setVoice(value) }

    fun saveBackend(value: String) = viewModelScope.launch { preferences.setBackendUrl(value) }

    fun useLocalMode() = viewModelScope.launch { preferences.setMode(OperatingMode.LOCAL) }

    fun enableGemini() = viewModelScope.launch { preferences.grantGeminiConsent() }

    /**
     * Performs a real token request against the configured backend. This is the only
     * honest way to test the voice path without shipping an API key: if the backend,
     * its Gemini key or the model is wrong, the error surfaces here.
     */
    fun testBackend() {
        viewModelScope.launch {
            _testing.value = true
            val prefs = preferences.current()
            if (prefs.useDirectApiKey && prefs.directApiKeyPresent) {
                val key = preferences.directApiKey()
                _testResult.value = if (key.isNullOrBlank()) {
                    "Saved key could not be decrypted. Enter it again."
                } else {
                    verifyDirectKey(key, prefs.geminiModel)
                }
                _testing.value = false
                return@launch
            }
            val installationId = preferences.installationId()
            val outcome = tokenService.mintToken(prefs.backendBaseUrl, installationId, prefs.geminiModel)
            _testResult.value = outcome.fold(
                onSuccess = { token ->
                    "Backend OK. Token issued for ${token.model}, valid for " +
                        "${((token.expiresAtMillis - System.currentTimeMillis()) / 60_000).coerceAtLeast(0)} minutes."
                },
                onFailure = { error -> "Failed: ${error.message}" },
            )
            _testing.value = false
        }
    }

    /**
     * Checks the user's own key against Google's real model endpoint. A 2xx proves the
     * key is valid and shows whether the configured model is available to it. No audio
     * is sent and no session is opened.
     */
    private suspend fun verifyDirectKey(key: String, model: String): String = withContext(Dispatchers.IO) {
        val url = "https://generativelanguage.googleapis.com/v1beta/models?key=" +
            URLEncoder.encode(key, "UTF-8")
        runCatching {
            val client = OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .build()
            client.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
                when {
                    !response.isSuccessful ->
                        "Google rejected the key (HTTP ${response.code}). Check it and save again."
                    response.body?.string().orEmpty().contains(model) ->
                        "Key accepted. $model is available."
                    else ->
                        "Key accepted, but $model is not in your model list. Try gemini-3.8-live."
                }
            }
        }.getOrElse { "Could not reach Google: ${it.message}" }
    }
}

@Composable
fun VoiceSettingsScreen(
    onBack: () -> Unit,
    viewModel: VoiceSettingsViewModel = hiltViewModel(),
) {
    val prefs by viewModel.preferencesFlow.collectAsStateWithLifecycle()
    val testResult by viewModel.testResult.collectAsStateWithLifecycle()
    val keyStatus by viewModel.keyStatus.collectAsStateWithLifecycle()
    val testing by viewModel.testing.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var model by remember(prefs.geminiModel) { mutableStateOf(prefs.geminiModel) }
    var voice by remember(prefs.voiceName) { mutableStateOf(prefs.voiceName) }
    var backend by remember(prefs.backendBaseUrl) { mutableStateOf(prefs.backendBaseUrl) }
    var keyInput by remember { mutableStateOf("") }

    JarvisScreen(
        title = "Voice & Gemini",
        subtitle = "Model, voice and the backend that mints short-lived tokens",
        onBack = onBack,
    ) {
        item {
            GlassCard {
                Column {
                    Text("Active mode", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    InfoRow(
                        "Mode",
                        if (prefs.mode == OperatingMode.GEMINI) "Gemini voice (cloud)" else "Local privacy (offline)",
                    )
                    InfoRow(
                        "Consent recorded",
                        prefs.geminiConsentGrantedAt?.let { "Yes" } ?: "No",
                    )
                    Spacer(Modifier.height(10.dp))
                    Row {
                        FilledTonalButton(onClick = viewModel::enableGemini) { Text("Use Gemini voice") }
                        Spacer(Modifier.width(10.dp))
                        TextButton(onClick = viewModel::useLocalMode) { Text("Use local only") }
                    }
                }
            }
        }

        item {
            GlassCard {
                Column {
                    Text("Live model", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = model,
                        onValueChange = { model = it },
                        label = { Text("Model id") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    Spacer(Modifier.height(8.dp))
                    Paragraph(
                        "Default is gemini-3.8-live. The id is configurable so a renamed or " +
                            "newer model can be used without shipping a new APK.",
                    )
                    Spacer(Modifier.height(8.dp))
                    FilledTonalButton(onClick = { viewModel.saveModel(model) }) { Text("Save model") }
                }
            }
        }

        item {
            GlassCard {
                Column {
                    Text("Your own Gemini API key", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    Paragraph(
                        "Optional. If you do not want to run the JARVIS token backend, paste a Gemini API key " +
                            "here and JARVIS will connect to Gemini Live directly.\n\n" +
                            "The key is encrypted with the Android Keystore and stored only on this phone. It is " +
                            "sent only to Google's Live API when you start a voice session — never to a JARVIS " +
                            "server, never to a log, and never shown again after saving.\n\n" +
                            "Trade-off: a permanent key on the device is weaker than a 30-minute ephemeral token. " +
                            "Use the backend for the stronger setup.",
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = keyInput,
                        onValueChange = { keyInput = it },
                        label = { Text("Gemini API key") },
                        placeholder = { Text("Paste the key from AI Studio") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    Row {
                        FilledTonalButton(onClick = {
                            viewModel.saveDirectKey(keyInput)
                            keyInput = ""
                        }) { Text("Save key") }
                        Spacer(Modifier.width(10.dp))
                        TextButton(onClick = viewModel::clearDirectKey) { Text("Remove") }
                    }
                    InfoRow(
                        "Stored on device",
                        if (prefs.directApiKeyPresent) "Yes, encrypted" else "No key saved",
                    )
                    ToggleRow(
                        title = "Use this key instead of the backend",
                        subtitle = "Applies from the next voice session. The backend is skipped entirely.",
                        checked = prefs.useDirectApiKey,
                        onCheckedChange = viewModel::setUseDirectKey,
                    )
                    keyStatus?.let { status ->
                        Spacer(Modifier.height(4.dp))
                        Text(status, style = MaterialTheme.typography.bodyMedium)
                    }
                    Spacer(Modifier.height(10.dp))
                    TextButton(
                        onClick = { openUrl(context, GEMINI_API_KEY_URL) },
                    ) { Text("Get a Gemini API key — open Google AI Studio ↗") }
                    Paragraph("Opens aistudio.google.com/apikey in your browser.")
                }
            }
        }

        item {
            GlassCard {
                Column {
                    Text("Voice", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = voice,
                        onValueChange = { voice = it },
                        label = { Text("Prebuilt voice name") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    Spacer(Modifier.height(8.dp))
                    Paragraph(
                        "The name is sent in the Live setup frame. If Google retires it, setup fails " +
                            "with a clear error instead of silently changing how JARVIS sounds.",
                    )
                    Spacer(Modifier.height(8.dp))
                    FilledTonalButton(onClick = { viewModel.saveVoice(voice) }) { Text("Save voice") }
                }
            }
        }

        item {
            GlassCard {
                Column {
                    Text("Token backend", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = backend,
                        onValueChange = { backend = it },
                        label = { Text("Backend base URL") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    Spacer(Modifier.height(8.dp))
                    Paragraph(
                        "JARVIS asks this server for a short-lived Gemini token. The Gemini API key " +
                            "stays on the server and is never stored in the app.\n\n" +
                        "Only used when “Use this key instead of the backend” is off. You can also " +
                            "paste your own key above and skip the server entirely.",
                    )
                    Spacer(Modifier.height(8.dp))
                    Row {
                        FilledTonalButton(onClick = { viewModel.saveBackend(backend) }) { Text("Save URL") }
                        Spacer(Modifier.width(10.dp))
                        FilledTonalButton(onClick = viewModel::testBackend, enabled = !testing) {
                            Text(if (testing) "Testing…" else "Test connection")
                        }
                    }
                    testResult?.let { result ->
                        Spacer(Modifier.height(8.dp))
                        Text(result, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }

        item {
            GlassCard {
                Column {
                    Text("Session rules", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Paragraph(
                        "• Audio is captured only between mic press and release.\n" +
                            "• A session is capped at 15 minutes, matching the Live API limit.\n" +
                            "• Dropped connections retry up to 3 times with a fresh token and session resumption.\n" +
                            "• Interrupting the assistant flushes all queued playback immediately.\n" +
                            "• If Gemini fails, JARVIS never switches to another provider — it offers Local mode.",
                    )
                }
            }
        }
    }
}

/** Where a user creates a Gemini API key. */
private const val GEMINI_API_KEY_URL = "https://aistudio.google.com/apikey"

/** Opens an external page; failures are silent because no browser is a device limitation. */
private fun openUrl(context: Context, url: String) {
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }
}
