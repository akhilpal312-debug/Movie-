package com.jarvis.assistant.features

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.hilt.navigation.compose.hiltViewModel
import com.jarvis.assistant.database.HistoryItem
import com.jarvis.assistant.database.HistoryRepository
import com.jarvis.assistant.ui.components.EmptyState
import com.jarvis.assistant.ui.components.GlassCard
import com.jarvis.assistant.ui.components.JarvisScreen
import com.jarvis.assistant.ui.components.asDayTime
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ActivityViewModel @Inject constructor(
    private val history: HistoryRepository,
) : ViewModel() {

    val items: StateFlow<List<HistoryItem>> = history.recent
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun clear() = viewModelScope.launch { history.clear() }
}

@Composable
fun ActivityScreen(
    onBack: () -> Unit,
    viewModel: ActivityViewModel = hiltViewModel(),
) {
    val items by viewModel.items.collectAsStateWithLifecycle()

    JarvisScreen(
        title = "Activity",
        subtitle = "Every action JARVIS attempted, kept on this device",
        onBack = onBack,
    ) {
        item {
            GlassCard {
                Column(Modifier.fillMaxWidth()) {
                    if (items.isEmpty()) {
                        EmptyState("Nothing has been run yet.")
                    }
                    items.take(60).forEach { item ->
                        Text(item.summary, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "${item.actionType} · ${item.status} · ${item.source} · ${item.at.asDayTime()}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(10.dp))
                    }
                    TextButton(onClick = viewModel::clear) { Text("Clear history") }
                }
            }
        }
    }
}
