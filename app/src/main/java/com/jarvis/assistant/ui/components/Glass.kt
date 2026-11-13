package com.jarvis.assistant.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.jarvis.assistant.core.model.VoiceState
import com.jarvis.assistant.ui.theme.LocalJarvisPalette

/** Calm gradient backdrop used by every screen. */
@Composable
fun JarvisBackground(content: @Composable () -> Unit) {
    val palette = LocalJarvisPalette.current
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        scheme.background,
                        palette.glow.copy(alpha = 0.10f),
                        scheme.background,
                    ),
                ),
            ),
    ) {
        content()
    }
}

/** Frosted surface: translucent fill plus a hairline border, no fake blur claim. */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    strong: Boolean = false,
    content: @Composable () -> Unit,
) {
    val palette = LocalJarvisPalette.current
    val shape = RoundedCornerShape(26.dp)
    Box(
        modifier = modifier
            .background(if (strong) palette.glassStrong else palette.glass, shape)
            .border(BorderStroke(1.dp, palette.glassBorder), shape)
            .padding(18.dp),
    ) {
        content()
    }
}

@Composable
fun SectionHeader(title: String, subtitle: String? = null, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(
            text = title.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = LocalJarvisPalette.current.muted,
        )
        if (subtitle != null) {
            Spacer(Modifier.height(4.dp))
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun ModePill(label: String, accent: Color, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .background(accent.copy(alpha = 0.14f), CircleShape)
            .border(1.dp, accent.copy(alpha = 0.35f), CircleShape)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(7.dp)
                    .background(accent, CircleShape),
            )
            Spacer(Modifier.width(7.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = accent,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

/**
 * The primary control. It reflects the real session state: the pulse only runs while
 * JARVIS is actually listening, and it becomes a stop control while speaking.
 */
@Composable
fun VoiceOrb(
    state: VoiceState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalJarvisPalette.current
    val transition = rememberInfiniteTransition(label = "orb")
    val pulse by transition.animateFloat(
        initialValue = 0.94f,
        targetValue = 1.06f,
        animationSpec = infiniteRepeatable(
            animation = tween(1_400),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulse",
    )
    val active = state == VoiceState.LISTENING || state == VoiceState.SPEAKING || state == VoiceState.PROCESSING
    val accent by animateColorAsState(
        targetValue = when (state) {
            VoiceState.LISTENING -> palette.glow
            VoiceState.SPEAKING -> palette.glowSecondary
            VoiceState.ERROR -> palette.danger
            VoiceState.RECONNECTING, VoiceState.CONNECTING -> palette.warning
            else -> palette.muted
        },
        label = "accent",
    )

    Box(contentAlignment = Alignment.Center, modifier = modifier) {
        Box(
            Modifier
                .size(196.dp)
                .scale(if (active) pulse else 1f)
                .background(accent.copy(alpha = 0.10f), CircleShape),
        )
        Box(
            Modifier
                .size(148.dp)
                .background(
                    Brush.radialGradient(
                        listOf(
                            accent.copy(alpha = 0.42f),
                            accent.copy(alpha = 0.12f),
                        ),
                    ),
                    CircleShape,
                )
                .border(1.dp, accent.copy(alpha = 0.45f), CircleShape)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (active) Icons.Filled.Stop else Icons.Filled.Mic,
                contentDescription = if (active) "Stop listening" else "Start listening",
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(42.dp),
            )
        }
    }
}

@Composable
fun InfoRow(label: String, value: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = 5.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(2.dp))
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
fun NavTile(
    title: String,
    subtitle: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    GlassCard(modifier = modifier.clickable(onClick = onClick)) {
        Column {
            Icon(icon, contentDescription = null, tint = LocalJarvisPalette.current.glow)
            Spacer(Modifier.height(10.dp))
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(2.dp))
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun StatusDot(color: Color, modifier: Modifier = Modifier) {
    Box(modifier.size(8.dp).background(color, CircleShape))
}
