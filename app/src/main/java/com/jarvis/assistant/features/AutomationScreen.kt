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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.jarvis.assistant.automation.AutomationScheduler
import com.jarvis.assistant.automation.Routine
import com.jarvis.assistant.automation.RoutineRepository
import com.jarvis.assistant.core.ActionRunner
import com.jarvis.assistant.core.model.ActionSource
import com.jarvis.assistant.core.model.JarvisAction
import com.jarvis.assistant.core.model.userMessage
import com.jarvis.assistant.ui.components.GlassCard
import com.jarvis.assistant.ui.components.JarvisScreen
import com.jarvis.assistant.ui.components.Paragraph
import com.jarvis.assistant.ui.components.SectionHeader
import com.jarvis.assistant.ui.components.asDayTime
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AutomationViewModel @Inject constructor(
    private val runner: ActionRunner,
    private val routines: RoutineRepository,
    private val scheduler: AutomationScheduler,
) : ViewModel() {

    val routinesFlow: StateFlow<List<Routine>> = routines.routines
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun createRoutine(name: String, hour: Int, minute: Int, stepsText: String) {
        val steps = stepsText.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (name.isBlank() || steps.isEmpty()) {
            _message.value = "A routine needs a name and at least one step."
            return
        }
        viewModelScope.launch {
            val result = runner.run(
                JarvisAction.CreateRoutine(name.trim(), hour, minute, steps.take(10)),
                ActionSource.UI,
            )
            _message.value = result.userMessage()
        }
    }

    fun runNow(routine: Routine) {
        viewModelScope.launch {
            _message.value = runner.run(JarvisAction.RunRoutine(routine.name), ActionSource.UI).userMessage()
        }
    }

    fun setEnabled(routine: Routine, enabled: Boolean) {
        viewModelScope.launch {
            routines.setEnabled(routine.id, enabled)
            if (enabled) {
                routines.routines.first { list -> list.any { it.id == routine.id && it.enabled } }
                    .firstOrNull { it.id == routine.id }
                    ?.let(scheduler::schedule)
            } else {
                scheduler.cancel(routine.id)
            }
        }
    }

    fun delete(routine: Routine) {
        viewModelScope.launch {
            scheduler.cancel(routine.id)
            routines.delete(routine.id)
        }
    }

    fun clearMessage() {
        _message.value = null
    }
}

@Composable
fun AutomationScreen(
    onBack: () -> Unit,
    viewModel: AutomationViewModel = hiltViewModel(),
) {
    val routines by viewModel.routinesFlow.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()

    var name by remember { mutableStateOf("") }
    var hour by remember { mutableStateOf("7") }
    var minute by remember { mutableStateOf("30") }
    var steps by remember { mutableStateOf("") }

    JarvisScreen(
        title = "Automation",
        subtitle = "Routines that run on schedule, without the cloud",
        onBack = onBack,
    ) {
        item {
            GlassCard {
                Column {
                    SectionHeader("Your routines", "${routines.count { it.enabled }} active")
                    Spacer(Modifier.height(8.dp))
                    if (routines.isEmpty()) {
                        Paragraph("No routines yet. Create one below — steps are interpreted on device.")
                    }
                    routines.forEach { routine ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    "${routine.name} · %02d:%02d".format(routine.hour, routine.minute),
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                Text(
                                    "${routine.steps.size} steps" +
                                        (routine.lastRunAt?.let { " · last run ${it.asDayTime()}" } ?: ""),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            TextButton(onClick = { viewModel.runNow(routine) }) { Text("Run") }
                            Switch(
                                checked = routine.enabled,
                                onCheckedChange = { checked -> viewModel.setEnabled(routine, checked) },
                            )
                            TextButton(onClick = { viewModel.delete(routine) }) { Text("✕") }
                        }
                        Spacer(Modifier.height(6.dp))
                    }
                }
            }
        }

        item {
            GlassCard {
                Column {
                    SectionHeader("New routine")
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Name") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = hour,
                            onValueChange = { hour = it.filter(Char::isDigit).take(2) },
                            label = { Text("Hour") },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                        )
                        OutlinedTextField(
                            value = minute,
                            onValueChange = { minute = it.filter(Char::isDigit).take(2) },
                            label = { Text("Minute") },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = steps,
                        onValueChange = { steps = it },
                        label = { Text("Steps, one per line") },
                        placeholder = { Text("battery\nopen camera\nvolume 30") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 3,
                    )
                    Spacer(Modifier.height(8.dp))
                    FilledTonalButton(
                        onClick = {
                            viewModel.createRoutine(
                                name = name,
                                hour = hour.toIntOrNull()?.coerceIn(0, 23) ?: 7,
                                minute = minute.toIntOrNull()?.coerceIn(0, 59) ?: 0,
                                stepsText = steps,
                            )
                            name = ""
                            steps = ""
                        },
                    ) { Text("Save routine") }
                }
            }
        }

        message?.let { text ->
            item {
                GlassCard {
                    Column {
                        Text(text, style = MaterialTheme.typography.bodyLarge)
                        TextButton(onClick = viewModel::clearMessage) { Text("Dismiss") }
                    }
                }
            }
        }

        item {
            GlassCard {
                Column {
                    SectionHeader("What routines can never do")
                    Spacer(Modifier.height(6.dp))
                    Paragraph(
                        "A schedule runs unattended, so it is held to the strictest rule: routines may only " +
                            "perform low-risk steps. Calling, messaging, spending money, deleting data, changing " +
                            "security settings or granting permissions is refused even if the step text asks for it.\n\n" +
                            "Scheduling uses WorkManager, survives reboots and re-registers itself after an update.",
                    )
                }
            }
        }
    }
}
