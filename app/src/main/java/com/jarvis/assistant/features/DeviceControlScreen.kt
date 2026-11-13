package com.jarvis.assistant.features

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.jarvis.assistant.core.ActionRunner
import com.jarvis.assistant.core.model.ActionSource
import com.jarvis.assistant.core.model.AudioStream
import com.jarvis.assistant.core.model.DeviceInfoField
import com.jarvis.assistant.core.model.ExecutionResult
import com.jarvis.assistant.core.model.JarvisAction
import com.jarvis.assistant.core.model.MediaCommand
import com.jarvis.assistant.core.model.SettingsPanel
import com.jarvis.assistant.device.AppResolver
import com.jarvis.assistant.device.DeviceInfoProvider
import com.jarvis.assistant.ui.components.GlassCard
import com.jarvis.assistant.ui.components.JarvisScreen
import com.jarvis.assistant.ui.components.Paragraph
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Manual device control. Every button goes through the same [ActionRunner] as voice
 * commands, so nothing here can bypass the privacy core either.
 */
@HiltViewModel
class DeviceControlViewModel @Inject constructor(
    private val runner: ActionRunner,
    private val deviceInfo: DeviceInfoProvider,
    private val appResolver: AppResolver,
) : ViewModel() {

    private val _result = MutableStateFlow<String?>(null)
    val result: StateFlow<String?> = _result.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _appQuery = MutableStateFlow("")
    val appQuery: StateFlow<String> = _appQuery.asStateFlow()

    val deviceSummary: String get() = deviceInfo.model()
    val appCount: Int get() = appResolver.launchableApps().size

    fun onAppQueryChange(value: String) {
        _appQuery.value = value
    }

    fun run(action: JarvisAction) {
        viewModelScope.launch {
            _busy.value = true
            _result.value = describe(runner.run(action, ActionSource.UI))
            _busy.value = false
        }
    }

    fun openAppByName() {
        val query = _appQuery.value.trim()
        if (query.isEmpty()) return
        run(JarvisAction.OpenApp(query))
    }

    private fun describe(result: ExecutionResult): String = when (result) {
        is ExecutionResult.Success -> result.message
        is ExecutionResult.Denied -> "Blocked: ${result.reason}"
        is ExecutionResult.NeedsPermission -> "Needs access: ${result.reason}"
        is ExecutionResult.Unsupported -> "Not supported here: ${result.reason}"
        is ExecutionResult.NeedsConfirmation -> "Needs confirmation: ${result.preview}"
        is ExecutionResult.Failed -> "Failed: ${result.reason}"
        is ExecutionResult.VoiceFailure -> result.reason
    }
}

@Composable
fun DeviceControlScreen(
    onBack: () -> Unit,
    viewModel: DeviceControlViewModel = hiltViewModel(),
) {
    val result by viewModel.result.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val appQuery by viewModel.appQuery.collectAsStateWithLifecycle()
    var volume by remember { mutableStateOf(40f) }

    JarvisScreen(
        title = "Device control",
        subtitle = "Real Android actions, each one authorised locally first",
        onBack = onBack,
    ) {
        item {
            GlassCard {
                Column {
                    Text("Applications", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    Paragraph(
                        "JARVIS knows ${viewModel.appCount} launchable apps on this device. " +
                            "The list never leaves the phone — only the name you speak is used to find the app.",
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = appQuery,
                        onValueChange = viewModel::onAppQueryChange,
                        label = { Text("App name") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    Spacer(Modifier.height(8.dp))
                    FilledTonalButton(onClick = viewModel::openAppByName, enabled = !busy) {
                        Text("Open app")
                    }
                }
            }
        }

        item {
            GlassCard {
                Column {
                    Text("Settings panels", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Paragraph(
                        "Wi-Fi, Bluetooth and mobile data are protected settings: Android lets JARVIS open " +
                            "the panel, but only you can change them there.",
                    )
                    Spacer(Modifier.height(8.dp))
                    ButtonRow(
                        listOf(
                            "Wi-Fi" to { viewModel.run(JarvisAction.OpenSettings(SettingsPanel.WIFI)) },
                            "Bluetooth" to { viewModel.run(JarvisAction.OpenSettings(SettingsPanel.BLUETOOTH)) },
                            "Battery" to { viewModel.run(JarvisAction.OpenSettings(SettingsPanel.BATTERY)) },
                            "Display" to { viewModel.run(JarvisAction.OpenSettings(SettingsPanel.DISPLAY)) },
                            "Sound" to { viewModel.run(JarvisAction.OpenSettings(SettingsPanel.SOUND)) },
                            "DND" to { viewModel.run(JarvisAction.OpenSettings(SettingsPanel.DND)) },
                        ),
                        enabled = !busy,
                    )
                }
            }
        }

        item {
            GlassCard {
                Column {
                    Text("Volume and media", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        FilledTonalButton(
                            onClick = { volume = (volume - 10f).coerceAtLeast(0f) },
                            enabled = !busy,
                        ) { Text("–") }
                        Spacer(Modifier.width(12.dp))
                        Text("${volume.toInt()}%", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.width(12.dp))
                        FilledTonalButton(
                            onClick = { volume = (volume + 10f).coerceAtMost(100f) },
                            enabled = !busy,
                        ) { Text("+") }
                        Spacer(Modifier.width(12.dp))
                        FilledTonalButton(
                            onClick = { viewModel.run(JarvisAction.SetVolume(AudioStream.MUSIC, volume.toInt())) },
                            enabled = !busy,
                        ) { Text("Apply") }
                    }
                    Spacer(Modifier.height(8.dp))
                    ButtonRow(
                        listOf(
                            "Play/pause" to { viewModel.run(JarvisAction.MediaControl(MediaCommand.TOGGLE)) },
                            "Next" to { viewModel.run(JarvisAction.MediaControl(MediaCommand.NEXT)) },
                            "Previous" to { viewModel.run(JarvisAction.MediaControl(MediaCommand.PREVIOUS)) },
                        ),
                        enabled = !busy,
                    )
                    Spacer(Modifier.height(6.dp))
                    Paragraph("Media control requires notification access; without it Android blocks the key event.")
                }
            }
        }

        item {
            GlassCard {
                Column {
                    Text("Clock", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    ButtonRow(
                        listOf(
                            "Alarm 07:00" to { viewModel.run(JarvisAction.CreateAlarm(7, 0, "JARVIS")) },
                            "Timer 5 min" to { viewModel.run(JarvisAction.StartTimer(300, "JARVIS")) },
                        ),
                        enabled = !busy,
                    )
                }
            }
        }

        item {
            GlassCard {
                Column {
                    Text("Device status", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Paragraph(viewModel.deviceSummary)
                    Spacer(Modifier.height(8.dp))
                    ButtonRow(
                        listOf(
                            "Battery" to { viewModel.run(JarvisAction.DeviceInfo(DeviceInfoField.BATTERY)) },
                            "Storage" to { viewModel.run(JarvisAction.DeviceInfo(DeviceInfoField.STORAGE)) },
                            "Memory" to { viewModel.run(JarvisAction.DeviceInfo(DeviceInfoField.MEMORY)) },
                            "Network" to { viewModel.run(JarvisAction.DeviceInfo(DeviceInfoField.NETWORK)) },
                        ),
                        enabled = !busy,
                    )
                }
            }
        }

        result?.let { message ->
            item {
                GlassCard {
                    Text(message, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
}

@Composable
private fun ButtonRow(labels: List<Pair<String, () -> Unit>>, enabled: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        labels.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { (label, action) ->
                    FilledTonalButton(onClick = action, enabled = enabled, modifier = Modifier.weight(1f)) {
                        Text(label)
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}
