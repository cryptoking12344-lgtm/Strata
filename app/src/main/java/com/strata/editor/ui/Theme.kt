package com.strata.editor.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

data class StrataColors(
    val pro: Boolean,
    val bg: Color,
    val surface: Color,
    val surface2: Color,
    val border: Color,
    val text: Color,
    val textDim: Color,
    val accent: Color,
    val onAccent: Color,
)

val FreeColors = StrataColors(
    pro = false,
    bg = Color(0xFF0E0F12),
    surface = Color(0xFF17191E),
    surface2 = Color(0xFF1E2128),
    border = Color(0xFF2A2E36),
    text = Color(0xFFF2F3F5),
    textDim = Color(0xFF9AA0AA),
    accent = Color(0xFFC6F432),
    onAccent = Color(0xFF0E0F12),
)

val ProColors = StrataColors(
    pro = true,
    bg = Color(0xFF110E09),
    surface = Color(0xFF1A150D),
    surface2 = Color(0xFF231C12),
    border = Color(0xFF3A3020),
    text = Color(0xFFF4EFE6),
    textDim = Color(0xFFA89F8C),
    accent = Color(0xFFE8B94A),
    onAccent = Color(0xFF110E09),
)

/** Layer colours on the timeline. */
object ClipColors {
    val main = Color(0xFF2E3B57)
    val video = Color(0xFF1F4A4A)
    val image = Color(0xFF4A3A2A)
    val text = Color(0xFF3D2F5C)
    val audio = Color(0xFF1E3A28)
    val wave = Color(0xFF6BD48A)
}

val LocalStrata = staticCompositionLocalOf { FreeColors }

object Mono {
    val style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, fontWeight = FontWeight.Medium)
}

@Composable
fun StrataTheme(pro: Boolean, content: @Composable () -> Unit) {
    val c = if (pro) ProColors else FreeColors
    val scheme = darkColorScheme(
        primary = c.accent,
        onPrimary = c.onAccent,
        secondary = c.accent,
        onSecondary = c.onAccent,
        background = c.bg,
        onBackground = c.text,
        surface = c.surface,
        onSurface = c.text,
        surfaceVariant = c.surface2,
        onSurfaceVariant = c.textDim,
        surfaceContainer = c.surface,
        surfaceContainerHigh = c.surface2,
        surfaceContainerHighest = c.surface2,
        outline = c.border,
    )
    CompositionLocalProvider(LocalStrata provides c) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
