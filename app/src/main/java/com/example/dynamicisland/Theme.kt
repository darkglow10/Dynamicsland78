package com.example.dynamicisland

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Göz yormayan, düşük kontrastlı koyu tema
private val Scheme = darkColorScheme(
    primary = Color(0xFF8AB4FF), onPrimary = Color(0xFF0B1B3A),
    primaryContainer = Color(0xFF22375E), onPrimaryContainer = Color(0xFFD9E5FF),
    secondaryContainer = Color(0xFF263149), onSecondaryContainer = Color(0xFFD9E5FF),
    background = Color(0xFF0E1014), onBackground = Color(0xFFE6E8EE),
    surface = Color(0xFF161920), onSurface = Color(0xFFE6E8EE),
    surfaceVariant = Color(0xFF1E222B), onSurfaceVariant = Color(0xFF9CA3B0),
    outline = Color(0xFF2B303B),
)

@Composable
fun IslandTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, content = content)
}
