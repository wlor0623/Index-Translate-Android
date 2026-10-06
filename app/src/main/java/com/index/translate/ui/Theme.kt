package com.index.translate.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// 与桌面版 webui 一致的配色
val Accent = Color(0xFF4F6AF5)
val AccentDark = Color(0xFF3D55D8)
val AccentSoft = Color(0xFFEAF0FF)
val BgLight = Color(0xFFF5F7FB)
val GreenOk = Color(0xFF22A06B)
val RedErr = Color(0xFFE5484D)

private val LightColors = lightColorScheme(
    primary = Accent,
    onPrimary = Color.White,
    primaryContainer = AccentSoft,
    onPrimaryContainer = AccentDark,
    secondary = Color(0xFF5A6B8C),
    background = BgLight,
    surface = Color.White,
    surfaceVariant = Color(0xFFEFF2F8),
    onSurfaceVariant = Color(0xFF7A8699),
    outlineVariant = Color(0xFFE3E8F0),
    error = RedErr,
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF93A8FF),
    onPrimary = Color(0xFF0D1B4B),
    primaryContainer = Color(0xFF2C3E8F),
    onPrimaryContainer = Color(0xFFDCE2FF),
    secondary = Color(0xFFA8B4CC),
    background = Color(0xFF12151C),
    surface = Color(0xFF1A1E28),
    surfaceVariant = Color(0xFF232836),
    onSurfaceVariant = Color(0xFF9AA5B8),
    outlineVariant = Color(0xFF333A4A),
    error = Color(0xFFFF7B80),
)

@Composable
fun IndexTranslateTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        content = content,
    )
}
