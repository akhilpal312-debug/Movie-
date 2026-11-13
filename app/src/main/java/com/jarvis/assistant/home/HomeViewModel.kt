package com.jarvis.assistant.home

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jarvis.assistant.assistant.AssistantRoleManager
import com.jarvis.assistant.automation.Routine
import com.jarvis.assistant.automation.RoutineRepository
import com.jarvis.assistant.core.ActionRunner
import com.jarvis.assistant.core.model.ActionSource
import com.jarvis.assistant.core.model.JarvisAction
import com.jarvis.assistant.core.model.OperatingMode
import com.jarvis.assistant.core.model.PermissionKey
import com.jarvis.assistant.core.model.UserPreferences
import com.jarvis.assistant.memory.TaskItem
import com.jarvis.assistant.memory.TaskRepository
import com.jarvis.assistant.permissions.PermissionDashboard
import com.jarvis.assistant.settings.PreferencesRepository
import com.jarvis.assistant.voice.VoiceSessionController
import com.jarvis.assistant.voice.VoiceUiState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val voice: VoiceSessionController,
    private val preferencesRepository: PreferencesRepository,
    taskRepository: TaskRepository,
    routineRepository: RoutineRepository,
    private val dashboard: PermissionDashboard,
    private val assistantRole: AssistantRoleManager,
    private val actionRunner: ActionRunner,
) : ViewModel() {

    val voiceState: StateFlow<VoiceUiState> = voice.state

    val preferences: StateFlow<UserPreferences> = preferencesRepository.preferences
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UserPreferences())

    val activeTasks: StateFlow<List<TaskItem>> = taskRepository.activeTasks
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val routines: StateFlow<List<Routine>> = routineRepository.routines
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _micGranted = MutableStateFlow(dashboard.isGranted(PermissionKey.MICROPHONE))
    val micGranted: StateFlow<Boolean> = _micGranted.asStateFlow()

    fun refreshPermissionState() {
        _micGranted.value = dashboard.isGranted(PermissionKey.MICROPHONE)
    }

    fun startListening() {
        refreshPermissionState()
        voice.startListening()
    }

    fun stopListening() = voice.stopListening()

    fun interrupt() = voice.interruptAndListen()

    fun stopEverything(reason: String? = null) = voice.stopEverything(reason)

    fun submitCommand(text: String) = voice.submitText(text)

    fun confirmPending() = voice.confirmPending()

    fun dismissPending() = voice.dismissPending()

    fun enableGeminiVoice() {
        viewModelScope.launch { preferencesRepository.grantGeminiConsent() }
    }

    fun useLocalMode() {
        viewModelScope.launch { preferencesRepository.setMode(OperatingMode.LOCAL) }
    }

    /** System role dialog intent, or the assistant settings screen on older devices. */
    fun requestAssistantRole(): Intent? = assistantRole.requestRoleIntent()

    fun openAssistantSettings() = assistantRole.openAssistantSettings()

    /** Completing a task from the checklist is a direct, local action — not a model request. */
    fun completeTask(title: String) {
        viewModelScope.launch {
            actionRunner.run(JarvisAction.CompleteTask(title), ActionSource.UI)
        }
    }

    fun endSession() = voice.endSession()
}
