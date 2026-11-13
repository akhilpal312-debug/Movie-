package com.jarvis.assistant.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Shared header + scrolling body for every secondary screen. */
@Composable
fun JarvisScreen(
    title: String,
    subtitle: String,
    onBack: (() -> Unit)? = null,
    content: LazyListScope.() -> Unit,
) {
    JarvisBackground {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(horizontal = 18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 16.dp),
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back",
                                tint = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                    Column(Modifier.weight(1f)) {
                        Text(title, style = MaterialTheme.typography.headlineSmall)
                        Spacer(Modifier.height(2.dp))
                        Text(
                            subtitle,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            content()
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

/** A compact, readable block of explanatory copy. */
@Composable
fun Paragraph(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier.fillMaxWidth(),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
fun EmptyState(text: String) {
    Box(Modifier.fillMaxWidth().padding(vertical = 16.dp)) {
        Paragraph(text)
    }
}

fun Long.asDayTime(): String =
    SimpleDateFormat("d MMM, HH:mm", Locale.getDefault()).format(Date(this))

fun Long.asClock(): String =
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(this))

fun Long.asTimeOfDayOrDate(): String =
    SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(this))
