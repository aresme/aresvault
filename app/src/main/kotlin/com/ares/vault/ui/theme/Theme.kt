package com.ares.vault.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val AresDark = darkColorScheme(
    primary = Color(0xFFE5484D),
    onPrimary = Color(0xFF1A0607),
    primaryContainer = Color(0xFF4A1416),
    onPrimaryContainer = Color(0xFFFFDAD8),
    secondary = Color(0xFFB4B8C5),
    background = Color(0xFF0E1116),
    onBackground = Color(0xFFE6E8EE),
    surface = Color(0xFF14161C),
    onSurface = Color(0xFFE6E8EE),
    surfaceVariant = Color(0xFF1E2129),
    onSurfaceVariant = Color(0xFFB4B8C5),
    outline = Color(0xFF3A3F4B),
    error = Color(0xFFFF6B6B),
)

/** Dark by default (spec §6.2). A light scheme can be added with a settings toggle later. */
@Composable
fun AresVaultTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = AresDark, content = content)
}
