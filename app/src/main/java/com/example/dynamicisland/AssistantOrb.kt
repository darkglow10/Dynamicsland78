package com.example.dynamicisland

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

private val SIRI_COLORS = listOf(
    Color(0xFF0A84FF), Color(0xFFBF5AF2), Color(0xFFFF375F),
    Color(0xFFFF9F0A), Color(0xFF30D5C8),
)

/**
 * Siri / Gemini tarzı canlı animasyon: karanlık bir gövdenin alt yarısında dolaşan renkli ışık
 * lekeleri ve kenarda dönen gökkuşağı halkası. Tüm animasyon değerleri yalnızca çizim fazında
 * okunur; recomposition olmaz.
 */
@Composable
fun AssistantOrb() {
    val t = rememberInfiniteTransition(label = "orb")
    val phase = t.animateFloat(0f, 1f, infiniteRepeatable(tween(7000, easing = LinearEasing)), label = "ph")
    val breathe = t.animateFloat(
        0.88f, 1.05f,
        infiniteRepeatable(tween(1600, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "br",
    )

    Canvas(Modifier.fillMaxSize()) {
        val w = size.width; val h = size.height
        if (w <= 1f || h <= 1f) return@Canvas
        val p = phase.value
        val b = breathe.value
        val tau = (2 * PI).toFloat()

        // 1) Alt yarıda dolaşan renkli ışık lekeleri (toplamsal karışım)
        SIRI_COLORS.forEachIndexed { i, c ->
            val a = tau * (p * (1f + i * 0.35f) + i / SIRI_COLORS.size.toFloat())
            val center = Offset(
                w * 0.5f + cos(a) * w * 0.30f,
                h * 0.80f + sin(a * 1.3f) * h * 0.10f,
            )
            val r = h * 0.62f * b
            drawCircle(
                brush = Brush.radialGradient(
                    0f to c.copy(alpha = 0.95f), 0.55f to c.copy(alpha = 0.35f), 1f to Color.Transparent,
                    center = center, radius = r,
                ),
                radius = r, center = center, blendMode = BlendMode.Plus,
            )
        }

        // 2) Üst tarafı karart: renkler alt yarıda toplanır, üst koyu cam gibi kalır
        drawRect(
            Brush.verticalGradient(
                0f to Color.Black.copy(alpha = 0.96f),
                0.45f to Color.Black.copy(alpha = 0.55f),
                0.85f to Color.Transparent,
            )
        )

        // 3) Kenarda akan gökkuşağı halkası (alt kenarda parlak, üstte sönük)
        val sw = 2.2.dp.toPx()
        val n = 12
        val ring = Brush.sweepGradient(
            colors = List(n + 1) { rainbowColorAt(p + it / n.toFloat(), 0.95f) },
            center = Offset(w / 2f, h / 2f),
        )
        drawOval(
            brush = ring, topLeft = Offset(sw, sw),
            size = Size(w - 2 * sw, h - 2 * sw), style = Stroke(width = sw),
            alpha = 0.85f,
        )
        // Üst kenardaki halkayı hafifçe söndür
        drawRect(
            Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.7f), 0.4f to Color.Transparent),
            size = Size(w, h * 0.4f),
        )
    }
}
