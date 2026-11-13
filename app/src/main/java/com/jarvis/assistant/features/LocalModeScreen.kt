package com.jarvis.assistant.features

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.jarvis.assistant.ai.LocalCommandParser
import com.jarvis.assistant.core.model.UserPreferences
import com.jarvis.assistant.settings.PreferencesRepository
import com.jarvis.assistant.ui.components.GlassCard
import com.jarvis.assistant.ui.components.InfoRow
import com.jarvis.assistant.ui.components.JarvisScreen
import com.jarvis.assistant.ui.components.Paragraph
import com.jarvis.assistant.ui.components.SectionHeader
import com.jarvis.assistant.ui.components.ToggleRow
import com.jarvis.assistant.voice.LocalVoiceInput
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class LocalModeViewModel @Inject constructor(
    private val preferences: PreferencesRepository,
    private val parser: LocalCommandParser,
    localVoice: LocalVoiceInput,
) : ViewModel() {

    val preferencesFlow: StateFlow<UserPreferences> = preferences.preferences
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UserPreferences())

    val onDeviceSpeechAvailable: Boolean = localVoice.isAvailable

    val examples: List<String> = parser.supportedExamples

    fun setSpeakReplies(speak: Boolean) = viewModelScope.launch { preferences.setSpeakLocal(speak) }

    fun setAutoMedium(enabled: Boolean) =
        viewModelScope.launch { preferences.setAutoApproveMediumRisk(enabled) }

    fun setPauseHighRisk(pause: Boolean) = viewModelScope.launch { preferences.setPauseHighRisk(pause) }

    fun setMaxActions(value: Int) = viewModelScope.launch { preferences.setMaxActionsPerMinute(value) }
}

@Composable
fun LocalModeScreen(
    onBack: () -> Unit,
    viewModel: LocalModeViewModel = hiltViewModel(),
) {
    val prefs by viewModel.preferencesFlow.collectAsStateWithLifecycle()

    JarvisScreen(
        title = "Local mode",
        subtitle = "What works with no network at all",
        onBack = onBack,
    ) {
        item {
            GlassCard(strong = true) {
                Column {
                    SectionHeader("Offline capability")
                    Spacer(Modifier.height(8.dp))
                    InfoRow(
                        "On-device speech",
                        if (viewModel.onDeviceSpeechAvailable) "Available" else "Unavailable on this device",
                    )
                    InfoRow("Spoken replies", if (prefs.speakRepliesInLocalMode) "On" else "Off")
                    Spacer(Modifier.height(8.dp))
                    Paragraph(
                        if (viewModel.onDeviceSpeechAvailable) {
                            "Local voice input uses Android's on-device recogniser only. Audio is never sent to a " +
                                "cloud recogniser, because doing so would break the promise of this mode."
                        } else {
                            "This device exposes no on-device recogniser, so local voice input is disabled. Typed " +
                                "commands and local spoken replies still work. JARVIS will not fall back to a cloud " +
                                "recogniser to make the microphone appear to work."
                        },
                    )
                }
            }
        }

        item {
            GlassCard {
                Column {
                    SectionHeader("Commands understood offline")
                    Spacer(Modifier.height(8.dp))
                    viewModel.examples.forEach { example ->
                        Text("• $example", style = MaterialTheme.typography.bodyLarge)
                    }
                    Spacer(Modifier.height(8.dp))
                    Paragraph(
                        "Anything outside this set is rejected with a clear message. Local mode never guesses and " +
                            "never forwards the request to the cloud.",
                    )
                }
            }
        }

        item {
            GlassCard {
                Column {
                    SectionHeader("Behaviour")
                    Spacer(Modifier.height(6.dp))
                    ToggleRow(
                        title = "Speak replies locally",
                        subtitle = "Use the device text-to-speech engine for confirmations.",
                        checked = prefs.speakRepliesInLocalMode,
                        onCheckedChange = viewModel::setSpeakReplies,
                    )
                    ToggleRow(
                        title = "Confirm device changes",
                        subtitle = "Ask before actions that change settings or data.",
                        checked = prefs.pauseAndConfirmHighRisk,
                        onCheckedChange = viewModel::setPauseHighRisk,
                    )
                    ToggleRow(
                        title = "Allow medium-risk automation",
                        subtitle = "Lets unattended routines change settings. High-risk work stays blocked.",
                        checked = prefs.autoApproveMediumRisk,
                        onCheckedChange = viewModel::setAutoMedium,
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "Action limit: ${prefs.maxActionsPerMinute} per minute",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Slider(
                        value = prefs.maxActionsPerMinute.toFloat(),
                        onValueChange = { viewModel.setMaxActions(it.toInt()) },
                        valueRange = 1f..60f,
                        steps = 58,
                    )
                    Paragraph(
                        "The limiter is the last check in the privacy core. It stops a looping model or a broken " +
                            "routine from hammering the device.",
                    )
                }
            }
        }
    }
}
