package com.jarvis.assistant.onboarding

import android.Manifest
import android.content.Intent
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jarvis.assistant.assistant.AssistantRoleManager
import com.jarvis.assistant.core.model.OperatingMode
import com.jarvis.assistant.core.model.PermissionKey
import com.jarvis.assistant.permissions.PermissionDashboard
import com.jarvis.assistant.settings.PreferencesRepository
import com.jarvis.assistant.ui.components.GlassCard
import com.jarvis.assistant.ui.components.InfoRow
import com.jarvis.assistant.ui.components.JarvisBackground
import com.jarvis.assistant.ui.components.SectionHeader
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val preferences: PreferencesRepository,
    private val dashboard: PermissionDashboard,
    private val assistantRole: AssistantRoleManager,
) : ViewModel() {

    fun enableGeminiVoice() = viewModelScope.launch { preferences.grantGeminiConsent() }

    fun stayLocal() = viewModelScope.launch { preferences.setMode(OperatingMode.LOCAL) }

    fun isGranted(key: PermissionKey): Boolean = dashboard.isGranted(key)

    fun roleRequestIntent(): Intent? = assistantRole.requestRoleIntent()

    fun openAssistantSettings() = assistantRole.openAssistantSettings()

    val assistantRoleSupported: Boolean get() = assistantRole.isSupported
}

/**
 * Guided first-run setup: what JARVIS is, the cloud disclosure, the permissions it
 * actually needs, and the system-managed assistant role request.
 */
@Composable
fun OnboardingScreen(
    onFinished: () -> Unit,
    viewModel: OnboardingViewModel = hiltViewModel(),
) {
    var step by remember { mutableIntStateOf(0) }
    var micRequested by remember { mutableStateOf(viewModel.isGranted(PermissionKey.MICROPHONE)) }
    var notificationsRequested by remember {
        mutableStateOf(viewModel.isGranted(PermissionKey.NOTIFICATIONS))
    }
    var geminiChosen by remember { mutableStateOf(false) }

    val micLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> micRequested = granted || viewModel.isGranted(PermissionKey.MICROPHONE) }

    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> notificationsRequested = granted || viewModel.isGranted(PermissionKey.NOTIFICATIONS) }

    val roleLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { }

    JarvisBackground {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(horizontal = 18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = PaddingValues(vertical = 20.dp),
        ) {
            item {
                Column {
                    Text("JARVIS", style = MaterialTheme.typography.displaySmall)
                    Text(
                        "Setup ${step + 1} of 4",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            when (step) {
                0 -> {
                    item {
                        GlassCard(strong = true) {
                            Column {
                                SectionHeader("What this is")
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    "A native Android assistant that talks with you and can actually drive " +
                                        "this phone: apps, settings, alarms, media, tasks, reminders and routines.",
                                    style = MaterialTheme.typography.bodyLarge,
                                )
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    "It is not a chatbot in a browser shell, and it does not pretend to do " +
                                        "things Android forbids. Anything blocked is reported as blocked.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }

                1 -> {
                    item {
                        GlassCard(strong = true) {
                            Column {
                                SectionHeader("The one cloud boundary")
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    "Gemini Live is a cloud service. In Gemini voice mode your microphone audio " +
                                        "is sent to Google while a session is open, and the reply is generated there.",
                                    style = MaterialTheme.typography.bodyLarge,
                                )
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    "JARVIS never attaches your files, messages, notifications, contacts, " +
                                        "clipboard, location or app list. Local Privacy mode sends nothing at all.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Spacer(Modifier.height(12.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    FilledTonalButton(onClick = {
                                        geminiChosen = true
                                        viewModel.enableGeminiVoice()
                                        step = 2
                                    }) { Text("Enable Gemini voice") }
                                    TextButton(onClick = {
                                        geminiChosen = false
                                        viewModel.stayLocal()
                                        step = 2
                                    }) { Text("Local only") }
                                }
                            }
                        }
                    }
                }

                2 -> {
                    item {
                        GlassCard(strong = true) {
                            Column {
                                SectionHeader("Permissions, only what is needed")
                                Spacer(Modifier.height(8.dp))
                                InfoRow("Microphone", if (micRequested) "Granted" else "Not granted")
                                InfoRow("Notifications", if (notificationsRequested) "Granted" else "Not granted")
                                Spacer(Modifier.height(10.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    FilledTonalButton(onClick = {
                                        micLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                    }) { Text("Microphone") }
                                    FilledTonalButton(onClick = {
                                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                        } else {
                                            notificationsRequested = true
                                        }
                                    }) { Text("Notifications") }
                                }
                                Spacer(Modifier.height(10.dp))
                                Text(
                                    "Everything else — contacts, notification access, brightness, Do Not Disturb, " +
                                        "exact alarms — is requested later, only when you use that feature.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }

                else -> {
                    item {
                        GlassCard(strong = true) {
                            Column {
                                SectionHeader("Default assistant")
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    "Android decides whether JARVIS becomes the assistant. Tapping below opens " +
                                        "the system's own confirmation; installing the app never does it automatically.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Spacer(Modifier.height(10.dp))
                                FilledTonalButton(onClick = {
                                    val intent = viewModel.roleRequestIntent()
                                    if (intent != null) roleLauncher.launch(intent) else viewModel.openAssistantSettings()
                                }) { Text("Open assistant selection") }
                                Spacer(Modifier.height(12.dp))
                                InfoRow("Mode chosen", if (geminiChosen) "Gemini voice" else "Local privacy")
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    "You can change every one of these choices later from the home screen.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }

            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    if (step > 0) {
                        TextButton(onClick = { step -= 1 }) { Text("Back") }
                        Spacer(Modifier.width(8.dp))
                    }
                    FilledTonalButton(
                        onClick = { if (step < 3) step += 1 else onFinished() },
                    ) {
                        Text(if (step < 3) "Continue" else "Enter JARVIS")
                    }
                }
            }
        }
    }
}
