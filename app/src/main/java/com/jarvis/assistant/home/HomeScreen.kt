package com.jarvis.assistant.home

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jarvis.assistant.automation.Routine
import com.jarvis.assistant.core.model.OperatingMode
import com.jarvis.assistant.core.model.UserPreferences
import com.jarvis.assistant.core.model.VoiceState
import com.jarvis.assistant.memory.TaskItem
import com.jarvis.assistant.navigation.JarvisDestination
import com.jarvis.assistant.ui.components.GlassCard
import com.jarvis.assistant.ui.components.JarvisBackground
import com.jarvis.assistant.ui.components.ModePill
import com.jarvis.assistant.ui.components.NavTile
import com.jarvis.assistant.ui.components.SectionHeader
import com.jarvis.assistant.ui.components.VoiceOrb
import com.jarvis.assistant.ui.theme.LocalJarvisPalette
import com.jarvis.assistant.voice.VoiceUiState

@Composable
fun HomeScreen(
    onNavigate: (JarvisDestination) -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val voiceState by viewModel.voiceState.collectAsStateWithLifecycle()
    val preferences by viewModel.preferences.collectAsStateWithLifecycle()
    val tasks by viewModel.activeTasks.collectAsStateWithLifecycle()
    val routines by viewModel.routines.collectAsStateWithLifecycle()
    val micGranted by viewModel.micGranted.collectAsStateWithLifecycle()

    var command by rememberSaveable { mutableStateOf("") }
    var showConsent by rememberSaveable { mutableStateOf(false) }
    var startAfterGrant by remember { mutableStateOf(false) }

    val micPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        viewModel.refreshPermissionState()
        if (granted && startAfterGrant) {
            startAfterGrant = false
            viewModel.startListening()
        }
    }
    val roleLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { viewModel.refreshPermissionState() }

    val palette = LocalJarvisPalette.current

    fun onOrbTap() {
        when (voiceState.state) {
            VoiceState.SPEAKING -> viewModel.interrupt()
            VoiceState.LISTENING -> viewModel.stopListening()
            VoiceState.CONNECTING, VoiceState.PROCESSING, VoiceState.RECONNECTING -> viewModel.stopEverything("Stopped.")
            else -> {
                if (!micGranted) {
                    startAfterGrant = true
                    micPermission.launch(Manifest.permission.RECORD_AUDIO)
                } else {
                    viewModel.startListening()
                }
            }
        }
    }

    JarvisBackground {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(horizontal = 18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = PaddingValues(vertical = 16.dp),
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("JARVIS", style = MaterialTheme.typography.headlineSmall)
                        Text(
                            "Your device, under your control",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    ModePill(
                        label = if (preferences.mode == OperatingMode.GEMINI) "Gemini voice" else "Local privacy",
                        accent = if (preferences.mode == OperatingMode.GEMINI) palette.glow else palette.success,
                        modifier = Modifier.clickable {
                            if (preferences.mode == OperatingMode.LOCAL) {
                                showConsent = true
                            } else {
                                onNavigate(JarvisDestination.VOICE)
                            }
                        },
                    )
                }
            }

            item {
                HeroCard(
                    state = voiceState,
                    preferences = preferences,
                    onOrbTap = ::onOrbTap,
                    onStop = { viewModel.stopEverything("Stopped.") },
                )
            }

            item {
                CommandInput(
                    value = command,
                    onValueChange = { command = it },
                    onSend = {
                        if (command.isNotBlank()) {
                            viewModel.submitCommand(command)
                            command = ""
                        }
                    },
                )
            }

            if (tasks.isNotEmpty()) {
                item {
                    TasksCard(tasks = tasks, onComplete = viewModel::completeTask)
                }
            }

            item {
                AutomationCard(routines = routines, onClick = { onNavigate(JarvisDestination.AUTOMATION) })
            }

            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SectionHeader("Control room")
                    JarvisDestination.entries
                        .filter { it != JarvisDestination.ONBOARDING }
                        .chunked(2)
                        .forEach { row ->
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                row.forEach { destination ->
                                    NavTile(
                                        title = destination.title,
                                        subtitle = destination.subtitle,
                                        icon = destination.icon,
                                        onClick = { onNavigate(destination) },
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                                if (row.size == 1) Spacer(Modifier.weight(1f))
                            }
                        }
                }
            }

            item {
                Column {
                    TextButton(
                        onClick = {
                            val intent = viewModel.requestAssistantRole()
                            if (intent != null) roleLauncher.launch(intent) else viewModel.openAssistantSettings()
                        },
                    ) { Text("Set JARVIS as the default assistant") }
                    Text(
                        "Audio leaves this device only in Gemini voice mode, and only while you hold the mic. " +
                            "Local Privacy mode never opens a network connection.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    if (showConsent) {
        ConsentDialog(
            onAccept = {
                showConsent = false
                viewModel.enableGeminiVoice()
            },
            onDecline = {
                showConsent = false
                viewModel.useLocalMode()
            },
        )
    }

    voiceState.pendingConfirmation?.let { pending ->
        AlertDialog(
            onDismissRequest = viewModel::dismissPending,
            title = { Text("Approve this action?") },
            text = {
                Column {
                    Text(pending.preview, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Text(pending.reason, style = MaterialTheme.typography.bodyMedium)
                }
            },
            confirmButton = {
                FilledTonalButton(onClick = viewModel::confirmPending) { Text("Approve") }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissPending) { Text("Not now") }
            },
        )
    }
}

@Composable
private fun HeroCard(
    state: VoiceUiState,
    preferences: UserPreferences,
    onOrbTap: () -> Unit,
    onStop: () -> Unit,
) {
    GlassCard(strong = true) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
            VoiceOrb(state = state.state, onClick = onOrbTap)
            Spacer(Modifier.height(10.dp))
            Text(
                text = stateLabel(state),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            val detail = when {
                state.error != null -> state.error.orEmpty()
                state.userText.isNotBlank() -> "“${state.userText.take(140)}”"
                state.assistantText.isNotBlank() -> state.assistantText.take(240)
                state.statusMessage.isNotBlank() -> state.statusMessage
                preferences.mode == OperatingMode.LOCAL &&
                    !state.localVoiceAvailable -> "On-device speech is unavailable here — type your command."
                else -> "Push to talk. Release to let JARVIS answer."
            }
            Text(
                text = detail,
                style = MaterialTheme.typography.bodyMedium,
                color = if (state.error != null) LocalJarvisPalette.current.danger else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
            state.sessionSecondsRemaining?.let { remaining ->
                Spacer(Modifier.height(6.dp))
                Text(
                    "Gemini session: %d:%02d left".format(remaining / 60, remaining % 60),
                    style = MaterialTheme.typography.labelSmall,
                    color = LocalJarvisPalette.current.muted,
                )
            }
            if (state.isBusy) {
                Spacer(Modifier.height(10.dp))
                FilledTonalButton(onClick = onStop) {
                    Icon(Icons.Filled.Stop, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Stop listening")
                }
            }
        }
    }
}

private fun stateLabel(state: VoiceUiState): String = when (state.state) {
    VoiceState.IDLE -> "Ready"
    VoiceState.CONNECTING -> "Connecting to Gemini Live…"
    VoiceState.LISTENING -> "Listening"
    VoiceState.PROCESSING -> "Working on it"
    VoiceState.SPEAKING -> "Speaking — tap to interrupt"
    VoiceState.RECONNECTING -> "Reconnecting"
    VoiceState.ERROR -> "Needs attention"
}

@Composable
private fun CommandInput(value: String, onValueChange: (String) -> Unit, onSend: () -> Unit) {
    GlassCard {
        Column {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.fillMaxWidth(),
                minLines = 1,
                maxLines = 3,
                placeholder = { Text("Type a command…") },
                trailingIcon = {
                    IconButton(onClick = onSend) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send command")
                    }
                },
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Try: “open camera”, “timer 10 minutes”, “battery batao”, “table lamp kholo”",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun TasksCard(tasks: List<TaskItem>, onComplete: (String) -> Unit) {
    GlassCard {
        Column {
            SectionHeader("Active tasks", "${tasks.size} open")
            Spacer(Modifier.height(8.dp))
            tasks.take(4).forEach { task ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Box(
                        Modifier
                            .size(22.dp)
                            .background(LocalJarvisPalette.current.glow.copy(alpha = 0.18f), CircleShape)
                            .clickable { onComplete(task.title) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Filled.Check,
                            contentDescription = "Complete ${task.title}",
                            modifier = Modifier.size(14.dp),
                            tint = LocalJarvisPalette.current.glow,
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Text(
                        task.title,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f),
                    )
                }
                Spacer(Modifier.height(6.dp))
            }
        }
    }
}

@Composable
private fun AutomationCard(routines: List<Routine>, onClick: () -> Unit) {
    val enabled = routines.filter { it.enabled }
    val palette = LocalJarvisPalette.current
    GlassCard(modifier = Modifier.clickable(onClick = onClick)) {
        Column {
            SectionHeader("Automation")
            Spacer(Modifier.height(6.dp))
            Text(
                if (enabled.isEmpty()) {
                    "No routines scheduled. Create one to run a list of steps at a fixed time."
                } else {
                    enabled.joinToString("\n") { "\"%s\" at %02d:%02d".format(it.name, it.hour, it.minute) }
                },
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "Routines only ever run low-risk steps and never send messages, call, or spend money.",
                style = MaterialTheme.typography.bodyMedium,
                color = palette.muted,
            )
        }
    }
}

@Composable
private fun ConsentDialog(onAccept: () -> Unit, onDecline: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDecline,
        title = { Text("Before Gemini voice mode") },
        text = {
            Column {
                Text(
                    "Gemini Live is a cloud service. While a voice session is open, your microphone audio " +
                        "is streamed to Google to be processed, and the spoken reply is generated there.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    "JARVIS never attaches your files, photos, messages, notifications, contacts, clipboard, " +
                        "location or app list to that stream. It never records or uploads audio outside a session " +
                        "you started, and Local Privacy mode never contacts Google at all.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    "You can stop listening at any time, and switch back to Local Privacy mode whenever you like.",
                    fontWeight = FontWeight.Medium,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        },
        confirmButton = { FilledTonalButton(onClick = onAccept) { Text("I understand") } },
        dismissButton = { TextButton(onClick = onDecline) { Text("Stay local") } },
    )
}
