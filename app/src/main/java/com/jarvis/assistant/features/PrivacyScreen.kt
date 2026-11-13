package com.jarvis.assistant.features

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.jarvis.assistant.core.model.OperatingMode
import com.jarvis.assistant.core.model.UserPreferences
import com.jarvis.assistant.database.AuditItem
import com.jarvis.assistant.database.AuditRepository
import com.jarvis.assistant.settings.PreferencesRepository
import com.jarvis.assistant.ui.components.GlassCard
import com.jarvis.assistant.ui.components.InfoRow
import com.jarvis.assistant.ui.components.JarvisScreen
import com.jarvis.assistant.ui.components.Paragraph
import com.jarvis.assistant.ui.components.SectionHeader
import com.jarvis.assistant.ui.components.asDayTime
import com.jarvis.assistant.ui.theme.LocalJarvisPalette
import com.jarvis.assistant.voice.VoiceSessionController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PrivacyViewModel @Inject constructor(
    private val preferences: PreferencesRepository,
    auditRepository: AuditRepository,
    private val voice: VoiceSessionController,
) : ViewModel() {

    val preferencesFlow: StateFlow<UserPreferences> = preferences.preferences
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UserPreferences())

    val audit: StateFlow<List<AuditItem>> = auditRepository.recent
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val denials: StateFlow<Int> = auditRepository.denialCount
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    private val repository = auditRepository

    fun useLocalMode() = viewModelScope.launch { preferences.revokeGeminiConsentAndUseLocal() }

    fun enableGemini() = viewModelScope.launch { preferences.grantGeminiConsent() }

    fun clearAudit() = viewModelScope.launch { repository.clear() }

    /** Ends the live socket and drops buffered notification contents. */
    fun endSessionAndClear() = voice.endSession("Session ended and buffers cleared.")
}

@Composable
fun PrivacyScreen(
    onBack: () -> Unit,
    viewModel: PrivacyViewModel = hiltViewModel(),
) {
    val prefs by viewModel.preferencesFlow.collectAsStateWithLifecycle()
    val audit by viewModel.audit.collectAsStateWithLifecycle()
    val denials by viewModel.denials.collectAsStateWithLifecycle()
    val palette = LocalJarvisPalette.current

    JarvisScreen(
        title = "Privacy",
        subtitle = "The firewall in front of every action",
        onBack = onBack,
    ) {
        item {
            GlassCard(strong = true) {
                Column {
                    InfoRow(
                        "Mode",
                        if (prefs.mode == OperatingMode.GEMINI) "Gemini voice (audio to Google)" else "Local privacy (no network)",
                    )
                    InfoRow("Gemini consent", if (prefs.geminiConsentGrantedAt != null) "Recorded" else "Not given")
                    InfoRow("Blocked requests", denials.toString())
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(onClick = viewModel::useLocalMode) { Text("Go local") }
                        FilledTonalButton(onClick = viewModel::enableGemini) { Text("Use Gemini voice") }
                    }
                    Spacer(Modifier.height(8.dp))
                    FilledTonalButton(onClick = viewModel::endSessionAndClear) {
                        Text("End session and clear buffers")
                    }
                }
            }
        }

        item {
            GlassCard {
                Column {
                    SectionHeader("What JARVIS never does")
                    Spacer(Modifier.height(8.dp))
                    Paragraph(
                        "1. Uploads files, photos, documents, messages, notifications, passwords, authentication " +
                            "codes or private database contents to Gemini.\n" +
                            "2. Reads the clipboard.\n" +
                            "3. Captures the screen without a feature you started yourself.\n" +
                            "4. Records audio in the background — the mic is open only between press and release.\n" +
                            "5. Enables hidden monitoring of any kind.\n" +
                            "6. Sends your contacts or call history to the AI service.\n" +
                            "7. Saves raw voice recordings.\n" +
                            "8. Shares location with the AI service.\n" +
                            "9. Runs a command that violates your own privacy policies.\n" +
                            "10. Hides the Stop control — it is always on the home screen.",
                    )
                }
            }
        }

        item {
            GlassCard {
                Column {
                    SectionHeader("Cloud boundary")
                    Spacer(Modifier.height(6.dp))
                    Paragraph(
                        "Gemini Live is a cloud API. Audio you deliberately send during a voice session is " +
                            "processed on Google's servers — that is the only device data that leaves the phone, " +
                            "and only while a session you started is open.\n\n" +
                            "The Gemini API key never exists in the app. Android receives a short-lived token " +
                            "from the JARVIS backend, scoped to a single session.",
                    )
                }
            }
        }

        item {
            GlassCard {
                Column {
                    SectionHeader("Recent decisions", "${audit.size} recorded")
                    Spacer(Modifier.height(8.dp))
                    if (audit.isEmpty()) {
                        Paragraph("No actions authorised yet.")
                    }
                    audit.take(20).forEach { item ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    "${item.actionType} · ${item.decision}",
                                    style = MaterialTheme.typography.bodyLarge,
                                )
                                Text(
                                    item.detail,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    "${item.source} · ${item.at.asDayTime()}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = palette.muted,
                                )
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                    TextButton(onClick = viewModel::clearAudit) { Text("Clear audit log") }
                }
            }
        }
    }
}
