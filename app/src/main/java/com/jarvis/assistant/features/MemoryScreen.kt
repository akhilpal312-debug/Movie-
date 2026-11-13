package com.jarvis.assistant.features

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.jarvis.assistant.core.ActionRunner
import com.jarvis.assistant.core.model.ActionSource
import com.jarvis.assistant.core.model.JarvisAction
import com.jarvis.assistant.memory.MemoryItem
import com.jarvis.assistant.memory.MemoryRepository
import com.jarvis.assistant.memory.TaskItem
import com.jarvis.assistant.memory.TaskRepository
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
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MemoryViewModel @Inject constructor(
    private val memoriesRepository: MemoryRepository,
    private val taskRepository: TaskRepository,
    private val actionRunner: ActionRunner,
) : ViewModel() {

    val memories: StateFlow<List<MemoryItem>> = memoriesRepository.memories
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val activeTasks: StateFlow<List<TaskItem>> = taskRepository.activeTasks
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val completedTasks: StateFlow<List<TaskItem>> = taskRepository.completedTasks
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun remember(text: String) {
        if (text.isBlank()) return
        viewModelScope.launch {
            val result = actionRunner.run(JarvisAction.Remember(text.trim()), ActionSource.UI)
            _message.value = when (result) {
                is com.jarvis.assistant.core.model.ExecutionResult.Success -> result.message
                is com.jarvis.assistant.core.model.ExecutionResult.Denied -> result.reason
                else -> "Could not save that memory."
            }
        }
    }

    fun delete(memory: MemoryItem) {
        viewModelScope.launch { memoriesRepository.delete(memory.id) }
    }

    /** Memories stay editable: the user must be able to correct what JARVIS remembers. */
    fun updateMemory(id: Long, text: String) {
        if (text.isBlank()) return
        viewModelScope.launch { memoriesRepository.update(id, text) }
    }

    fun clearMemories() {
        viewModelScope.launch {
            memoriesRepository.clearAll()
            _message.value = "All stored memories were deleted."
        }
    }

    fun completeTask(task: TaskItem) {
        viewModelScope.launch { taskRepository.complete(task.title) }
    }

    fun clearCompleted() {
        viewModelScope.launch { taskRepository.clearCompleted() }
    }

    fun addTask(title: String) {
        if (title.isBlank()) return
        viewModelScope.launch {
            val result = actionRunner.run(JarvisAction.AddTask(title.trim(), null), ActionSource.UI)
            _message.value = (result as? com.jarvis.assistant.core.model.ExecutionResult.Success)?.message
        }
    }

    fun clearMessage() {
        _message.value = null
    }
}

@Composable
fun MemoryScreen(
    onBack: () -> Unit,
    viewModel: MemoryViewModel = hiltViewModel(),
) {
    val memories by viewModel.memories.collectAsStateWithLifecycle()
    val activeTasks by viewModel.activeTasks.collectAsStateWithLifecycle()
    val completedTasks by viewModel.completedTasks.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()

    var newMemory by remember { mutableStateOf("") }
    var newTask by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<MemoryItem?>(null) }

    JarvisScreen(
        title = "Memory",
        subtitle = "Encrypted on device, never uploaded",
        onBack = onBack,
    ) {
        item {
            GlassCard {
                Column {
                    SectionHeader("Remembered facts", "${memories.size} stored")
                    Spacer(Modifier.height(8.dp))
                    if (memories.isEmpty()) {
                        Paragraph("Nothing stored yet. Say “remember I prefer filter coffee” — or add one here.")
                    }
                    memories.forEach { memory ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.weight(1f)) {
                                Text(memory.text, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    memory.createdAt.asDayTime(),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            TextButton(onClick = { editing = memory }) { Text("Edit") }
                            TextButton(onClick = { viewModel.delete(memory) }) { Text("Delete") }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = newMemory,
                        onValueChange = { newMemory = it },
                        label = { Text("Add a memory") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(onClick = {
                            viewModel.remember(newMemory)
                            newMemory = ""
                        }) { Text("Save") }
                        TextButton(onClick = viewModel::clearMemories) { Text("Clear all memory") }
                    }
                }
            }
        }

        item {
            GlassCard {
                Column {
                    SectionHeader("Tasks", "${activeTasks.size} active · ${completedTasks.size} done")
                    Spacer(Modifier.height(8.dp))
                    activeTasks.forEach { task ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            Text(task.title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                            TextButton(onClick = { viewModel.completeTask(task) }) { Text("Done") }
                        }
                    }
                    if (activeTasks.isEmpty()) Paragraph("No active tasks.")
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = newTask,
                        onValueChange = { newTask = it },
                        label = { Text("Add a task") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(onClick = {
                            viewModel.addTask(newTask)
                            newTask = ""
                        }) { Text("Add task") }
                        TextButton(onClick = viewModel::clearCompleted) { Text("Clear completed") }
                    }
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
                Paragraph(
                    "Memories and task titles are encrypted with a key held in the Android Keystore, so the " +
                        "database file alone is not readable. Passwords, PINs and one-time codes are refused and " +
                        "never stored. Nothing here is included in a Gemini request.",
                )
            }
        }
    }

    editing?.let { memory ->
        var draft by remember(memory.id) { mutableStateOf(memory.text) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text("Edit memory") },
            text = {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                )
            },
            confirmButton = {
                FilledTonalButton(
                    onClick = {
                        viewModel.updateMemory(memory.id, draft)
                        editing = null
                    },
                ) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("Cancel") } },
        )
    }
}
