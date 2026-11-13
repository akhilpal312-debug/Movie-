package com.jarvis.assistant.features

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.jarvis.assistant.core.model.PermissionKey
import com.jarvis.assistant.core.model.PermissionStatus
import com.jarvis.assistant.core.model.UserPreferences
import com.jarvis.assistant.permissions.PermissionDashboard
import com.jarvis.assistant.permissions.PermissionRegistry
import com.jarvis.assistant.settings.PreferencesRepository
import com.jarvis.assistant.ui.components.GlassCard
import com.jarvis.assistant.ui.components.JarvisScreen
import com.jarvis.assistant.ui.components.Paragraph
import com.jarvis.assistant.ui.components.SectionHeader
import com.jarvis.assistant.ui.theme.LocalJarvisPalette
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PermissionsViewModel @Inject constructor(
    private val dashboard: PermissionDashboard,
    private val preferences: PreferencesRepository,
    private val notificationVault: com.jarvis.assistant.device.NotificationVault,
) : ViewModel() {

    private val _statuses = MutableStateFlow(dashboard.statuses())
    val statuses: StateFlow<List<PermissionStatus>> = _statuses.asStateFlow()

    val preferencesFlow: StateFlow<UserPreferences> = preferences.preferences
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UserPreferences())

    fun refresh() {
        _statuses.value = dashboard.statuses()
    }

    fun setNotificationConsent(granted: Boolean) {
        viewModelScope.launch { preferences.setNotificationConsent(granted) }
    }

    fun setContactsConsent(granted: Boolean) {
        viewModelScope.launch { preferences.setContactsConsent(granted) }
    }

    /** Apps JARVIS has actually seen, so the user can exclude them individually. */
    fun recentNotificationApps(): List<Pair<String, String>> = notificationVault.recentApps()

    fun setAppBlocked(packageName: String, blocked: Boolean) {
        viewModelScope.launch { preferences.setNotificationBlocked(packageName, blocked) }
    }
}

@Composable
fun PermissionsScreen(
    onBack: () -> Unit,
    viewModel: PermissionsViewModel = hiltViewModel(),
) {
    val statuses by viewModel.statuses.collectAsStateWithLifecycle()
    val prefs by viewModel.preferencesFlow.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val palette = LocalJarvisPalette.current

    val runtimeLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { viewModel.refresh() }

    JarvisScreen(
        title = "Permissions",
        subtitle = "What JARVIS can use, why, and how to take it back",
        onBack = onBack,
    ) {
        statuses.forEach { status ->
            val descriptor = PermissionRegistry.descriptor(status.key)
            if (descriptor != null) {
                item {
                    GlassCard {
                        Column {
                            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                Text(
                                    descriptor.title,
                                    style = MaterialTheme.typography.titleMedium,
                                    modifier = Modifier.weight(1f),
                                )
                                Text(
                                    if (status.granted) "Granted" else "Not granted",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (status.granted) palette.success else palette.warning,
                                )
                            }
                            Spacer(Modifier.height(6.dp))
                            Paragraph(descriptor.rationale)
                            Spacer(Modifier.height(4.dp))
                            Paragraph("Without it: ${descriptor.consequenceIfDenied}")
                            Spacer(Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilledTonalButton(
                                    onClick = {
                                        when (status.key) {
                                            PermissionKey.MICROPHONE -> runtimeLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                            PermissionKey.CONTACTS -> runtimeLauncher.launch(Manifest.permission.READ_CONTACTS)
                                            PermissionKey.NOTIFICATIONS ->
                                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                                    runtimeLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                                } else {
                                                    context.startActivity(PermissionRegistry.settingsIntent(context, status.key))
                                                }
                                            else -> context.startActivity(
                                                PermissionRegistry.settingsIntent(context, status.key),
                                            )
                                        }
                                    },
                                ) { Text(if (status.granted) "Manage" else "Allow") }
                                TextButton(
                                    onClick = {
                                        context.startActivity(PermissionRegistry.settingsIntent(context, status.key))
                                    },
                                ) { Text("System settings") }
                            }
                        }
                    }
                }
            }
        }

        item {
            GlassCard {
                Column {
                    SectionHeader("Private data consents", "These are JARVIS rules on top of Android grants")
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Notification reading", style = MaterialTheme.typography.titleMedium)
                            Paragraph("Allow JARVIS to summarise recent notifications, on device only.")
                        }
                        Switch(
                            checked = prefs.notificationAccessConsent,
                            onCheckedChange = viewModel::setNotificationConsent,
                        )
                    }
                    if (prefs.notificationAccessConsent) {
                        val apps = viewModel.recentNotificationApps()
                        if (apps.isNotEmpty()) {
                            Spacer(Modifier.height(12.dp))
                            Text("Apps JARVIS may read", style = MaterialTheme.typography.titleMedium)
                            Paragraph(
                                "Turn an app off to keep its notifications out of JARVIS completely, " +
                                    "even though notification access is granted.",
                            )
                            apps.forEach { (packageName, label) ->
                                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                    Text(label, modifier = Modifier.weight(1f))
                                    Switch(
                                        checked = packageName !in prefs.notificationBlockedPackages,
                                        onCheckedChange = { allowed ->
                                            viewModel.setAppBlocked(packageName, !allowed)
                                        },
                                    )
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Contact lookups", style = MaterialTheme.typography.titleMedium)
                            Paragraph("Allow resolving a contact name when you ask to call or message.")
                        }
                        Switch(
                            checked = prefs.contactsConsent,
                            onCheckedChange = viewModel::setContactsConsent,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Paragraph(
                        "Turning a consent off takes effect immediately: the privacy core refuses the matching " +
                            "actions even if the Android permission is still granted.",
                    )
                }
            }
        }

        item {
            Column {
                TextButton(onClick = viewModel::refresh, modifier = Modifier.fillMaxWidth()) {
                    Text("Refresh status")
                }
                Spacer(Modifier.width(4.dp))
                Paragraph(
                    "Android may show its own confirmation screen for special access. JARVIS cannot skip it, " +
                        "and it never claims a permission it does not hold.",
                )
            }
        }
    }
}
