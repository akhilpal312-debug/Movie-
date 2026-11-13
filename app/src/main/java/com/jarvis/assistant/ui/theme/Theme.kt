package com.jarvis.assistant.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Extra colours that Material's scheme does not carry, used by the glass surfaces. */
data class JarvisPalette(
    val glow: Color,
    val glowSecondary: Color,
    val glass: Color,
    val glassStrong: Color,
    val glassBorder: Color,
    val muted: Color,
    val success: Color,
    val warning: Color,
    val danger: Color,
)

private val LightPalette = JarvisPalette(
    glow = Color(0xFF2C6E63),
    glowSecondary = Color(0xFF7FB8C9),
    glass = Color(0xCCFFFFFF),
    glassStrong = Color(0xF2FFFFFF),
    glassBorder = Color(0x1A0B1B2A),
    muted = Color(0xFF5C6B7A),
    success = Color(0xFF2F7D5F),
    warning = Color(0xFFA8701F),
    danger = Color(0xFFB03B3B),
)

private val DarkPalette = JarvisPalette(
    glow = Color(0xFF7FD4C1),
    glowSecondary = Color(0xFF6C93B8),
    glass = Color(0x1FFFFFFF),
    glassStrong = Color(0x2EFFFFFF),
    glassBorder = Color(0x24FFFFFF),
    muted = Color(0xFF9FB3C8),
    success = Color(0xFF7FD4C1),
    warning = Color(0xFFE4B363),
    danger = Color(0xFFEE8B8B),
)

val LocalJarvisPalette = staticCompositionLocalOf { DarkPalette }

private val LightColors = lightColorScheme(
    primary = Color(0xFF2C6E63),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFCFE7E1),
    onPrimaryContainer = Color(0xFF0B2B26),
    secondary = Color(0xFF47616F),
    background = Color(0xFFF4F6F8),
    onBackground = Color(0xFF131A21),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF131A21),
    surfaceVariant = Color(0xFFE4E9EE),
    onSurfaceVariant = Color(0xFF46525E),
    error = Color(0xFFB03B3B),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF7FD4C1),
    onPrimary = Color(0xFF06231F),
    primaryContainer = Color(0xFF1B3A35),
    onPrimaryContainer = Color(0xFFD6F2EB),
    secondary = Color(0xFF9DB6C9),
    background = Color(0xFF0B0F14),
    onBackground = Color(0xFFE9F0F6),
    surface = Color(0xFF141A21),
    onSurface = Color(0xFFE9F0F6),
    surfaceVariant = Color(0xFF1E262F),
    onSurfaceVariant = Color(0xFFAEBCC9),
    error = Color(0xFFEE8B8B),
)

private val JarvisTypography = Typography(
    displaySmall = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 32.sp, letterSpacing = (-0.5).sp),
    headlineSmall = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 22.sp, letterSpacing = (-0.2).sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp),
    bodyLarge = TextStyle(fontSize = 15.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontSize = 13.5.sp, lineHeight = 20.sp),
    labelSmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 11.sp, letterSpacing = 0.6.sp),
)

@Composable
fun JarvisTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val palette = if (darkTheme) DarkPalette else LightPalette
    androidx.compose.runtime.CompositionLocalProvider(LocalJarvisPalette provides palette) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkColors else LightColors,
            typography = JarvisTypography,
            content = content,
        )
    }
}
