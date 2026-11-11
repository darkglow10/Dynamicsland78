package com.example.dynamicisland

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Göz yormayan, derinlikli koyu tema (yüzeyler arasında belirgin kademe)
private val Scheme = darkColorScheme(
    primary = Color(0xFF8AB4FF), onPrimary = Color(0xFF0B1B3A),
    primaryContainer = Color(0xFF243A63), onPrimaryContainer = Color(0xFFDCE7FF),
    secondary = Color(0xFFA8B5D1), onSecondary = Color(0xFF14203A),
    secondaryContainer = Color(0xFF28344D), onSecondaryContainer = Color(0xFFDCE7FF),
    background = Color(0xFF0B0D12), onBackground = Color(0xFFE8EAF0),
    surface = Color(0xFF14171E), onSurface = Color(0xFFE8EAF0),
    surfaceVariant = Color(0xFF1C2029), onSurfaceVariant = Color(0xFF9AA2B1),
    outline = Color(0xFF2A2F3B), outlineVariant = Color(0xFF222733),
)

private val AppTypography = Typography(
    titleLarge = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.2).sp),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Medium),
    bodyMedium = TextStyle(fontSize = 14.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
    labelMedium = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium),
)

private val AppShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(22.dp),
)

@Composable
fun IslandTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, typography = AppTypography, shapes = AppShapes, content = content)
}
