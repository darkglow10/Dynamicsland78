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

// Derin, nötr-mavi koyu tema: arka plan ile yüzeyler arasında net ama sakin kademe.
// Tek vurgu rengi (mavi) kullanılır; renk yalnızca durum bildirmek için vardır.
private val Scheme = darkColorScheme(
    primary = Color(0xFF6EA8FF), onPrimary = Color(0xFF06142E),
    primaryContainer = Color(0xFF1B3157), onPrimaryContainer = Color(0xFFD9E6FF),
    secondary = Color(0xFFA9B6D3), onSecondary = Color(0xFF111B31),
    secondaryContainer = Color(0xFF232C40), onSecondaryContainer = Color(0xFFDCE5F8),
    background = Color(0xFF07080B), onBackground = Color(0xFFECEEF3),
    surface = Color(0xFF101217), onSurface = Color(0xFFECEEF3),
    surfaceVariant = Color(0xFF1A1D25), onSurfaceVariant = Color(0xFF8E96A6),
    outline = Color(0xFF2A2E39), outlineVariant = Color(0xFF1F232C),
)

private val AppTypography = Typography(
    titleLarge = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.4).sp),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.1).sp),
    bodyLarge = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Medium),
    bodyMedium = TextStyle(fontSize = 14.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
    labelMedium = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium),
)

private val AppShapes = Shapes(
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
)

@Composable
fun IslandTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, typography = AppTypography, shapes = AppShapes, content = content)
}
