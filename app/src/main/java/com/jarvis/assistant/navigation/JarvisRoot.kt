package com.jarvis.assistant.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Tune
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.jarvis.assistant.features.ActivityScreen
import com.jarvis.assistant.features.AutomationScreen
import com.jarvis.assistant.features.DeviceControlScreen
import com.jarvis.assistant.features.LocalModeScreen
import com.jarvis.assistant.features.MemoryScreen
import com.jarvis.assistant.features.PermissionsScreen
import com.jarvis.assistant.features.PrivacyScreen
import com.jarvis.assistant.features.VoiceSettingsScreen
import com.jarvis.assistant.home.HomeScreen
import com.jarvis.assistant.onboarding.OnboardingScreen
import com.jarvis.assistant.settings.PreferencesRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Every destination in the app, with the copy used by the home grid. */
enum class JarvisDestination(
    val route: String,
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
) {
    VOICE("voice", "Voice & Gemini", "Model, voice and backend", Icons.Filled.Mic),
    DEVICE("device", "Device control", "Apps, settings, alarms, media", Icons.Filled.Devices),
    AUTOMATION("automation", "Automation", "Routines and schedules", Icons.Filled.Bolt),
    MEMORY("memory", "Memory", "Facts, tasks and clearing", Icons.Filled.Memory),
    PERMISSIONS("permissions", "Permissions", "Access, rationale and revocation", Icons.Filled.Shield),
    PRIVACY("privacy", "Privacy", "Firewall, audit and consent", Icons.Filled.Lock),
    ACTIVITY("activity", "Activity", "What was run and when", Icons.Filled.History),
    LOCAL("local", "Local mode", "Offline behaviour and limits", Icons.Filled.Tune),
    ONBOARDING("onboarding", "Setup", "Guided first-run setup", Icons.Filled.SmartToy),
}

@Composable
fun JarvisRoot() {
    val navController = rememberNavController()
    val rootViewModel: RootViewModel = hiltViewModel()
    val onboardingComplete by rootViewModel.onboardingComplete.collectAsStateWithLifecycle()

    if (!onboardingComplete) {
        OnboardingScreen(
            onFinished = { rootViewModel.completeOnboarding() },
        )
        return
    }

    JarvisNavHost(navController)
}

@Composable
private fun JarvisNavHost(navController: NavHostController) {
    NavHost(navController = navController, startDestination = "home") {
        composable("home") {
            HomeScreen(
                onNavigate = { destination -> navController.navigate(destination.route) },
            )
        }
        composable(JarvisDestination.VOICE.route) {
            VoiceSettingsScreen(onBack = { navController.popBackStack() })
        }
        composable(JarvisDestination.DEVICE.route) {
            DeviceControlScreen(onBack = { navController.popBackStack() })
        }
        composable(JarvisDestination.AUTOMATION.route) {
            AutomationScreen(onBack = { navController.popBackStack() })
        }
        composable(JarvisDestination.MEMORY.route) {
            MemoryScreen(onBack = { navController.popBackStack() })
        }
        composable(JarvisDestination.PERMISSIONS.route) {
            PermissionsScreen(onBack = { navController.popBackStack() })
        }
        composable(JarvisDestination.PRIVACY.route) {
            PrivacyScreen(onBack = { navController.popBackStack() })
        }
        composable(JarvisDestination.ACTIVITY.route) {
            ActivityScreen(onBack = { navController.popBackStack() })
        }
        composable(JarvisDestination.LOCAL.route) {
            LocalModeScreen(onBack = { navController.popBackStack() })
        }
    }
}

@HiltViewModel
class RootViewModel @Inject constructor(
    private val preferences: PreferencesRepository,
) : ViewModel() {
    val onboardingComplete: StateFlow<Boolean> = preferences.preferences
        .map { it.onboardingComplete }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun completeOnboarding() {
        viewModelScope.launch { preferences.setOnboardingComplete() }
    }
}
