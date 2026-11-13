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
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/**
 * Hap (36dp köşeli) şekilden tam elipse akıcı geçiş yapan şekil.
 * t = 0 -> normal ada, t = 1 -> tam elips.
 */
class AssistantShape(private val t: Float, private val baseRadiusDp: Float = 36f) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val base = min(baseRadiusDp * density.density, size.height / 2f)
        val k = t.coerceIn(0f, 1f)
        val rx = base + (size.width / 2f - base) * k
        val ry = base + (size.height / 2f - base) * k
        return Outline.Rounded(RoundRect(0f, 0f, size.width, size.height, CornerRadius(rx, ry)))
    }
}

// Videodaki renk dizilimi: soldan turuncu/sarı -> ortada beyaz -> sağda cyan/mavi; uçlarda kaybolur
private val LINE_STOPS = arrayOf(
    0.00f to Color(0x00FF9500),
    0.14f to Color(0xFFFF9500),
    0.30f to Color(0xFFFFD60A),
    0.48f to Color(0xFFFFFFFF),
    0.66f to Color(0xFF64D2FF),
    0.84f to Color(0xFF0A84FF),
    1.00f to Color(0x000A84FF),
)

/**
 * Siri / Gemini benzeri asistan animasyonu: koyu elips, ortada tam olmayan (uçlarda kaybolan) ince
 * renkli dalga çizgisi, çizginin altında hafif renk ışıması. Animasyon değerleri yalnızca çizim
 * fazında okunur; recomposition olmaz.
 */
@Composable
fun AssistantOrb() {
    val t = rememberInfiniteTransition(label = "orb")
    val phase = t.animateFloat(0f, 1f, infiniteRepeatable(tween(2600, easing = LinearEasing)), label = "ph")
    val breathe = t.animateFloat(
        0.75f, 1.15f,
        infiniteRepeatable(tween(1400, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "br",
    )
    val path = remember { Path() }

    Canvas(Modifier.fillMaxSize()) {
        val w = size.width; val h = size.height
        if (w <= 1f || h <= 1f) return@Canvas
        val p = phase.value
        val b = breathe.value
        val tau = (2 * PI).toFloat()

        val cy = h * 0.60f
        val x0 = w * 0.07f; val x1 = w * 0.93f
        val span = x1 - x0
        val amp = h * 0.045f * b

        // Dalga çizgisi: uçlara doğru sönen zarf; iki farklı dalga üst üste, soldan sağa akar
        path.reset()
        val steps = 56
        for (i in 0..steps) {
            val u = i / steps.toFloat()
            val env = sin(PI.toFloat() * u)                       // uçlarda 0, ortada 1
            val y = cy + amp * env * (
                sin(tau * (u * 1.6f - p)) + 0.45f * sin(tau * (u * 3.1f + p * 2f))
            )
            val x = x0 + span * u
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }

        // Renk akışı: çizgi boyunca hafifçe kayar (konumlar sabit, rastgele dağılım yok)
        val shift = sin(tau * p) * span * 0.04f
        val line = Brush.horizontalGradient(
            colorStops = LINE_STOPS, startX = x0 + shift, endX = x1 + shift,
        )

        // 1) Çizginin altında yumuşak renk ışıması (soldan turuncu, sağdan mavi)
        drawOval(
            brush = Brush.radialGradient(
                0f to Color(0xFF0A84FF).copy(alpha = 0.34f * b), 1f to Color.Transparent,
                center = Offset(w * 0.68f, cy + h * 0.16f), radius = w * 0.30f,
            ),
            topLeft = Offset.Zero, size = size,
        )
        drawOval(
            brush = Brush.radialGradient(
                0f to Color(0xFFFF9500).copy(alpha = 0.26f * b), 1f to Color.Transparent,
                center = Offset(w * 0.30f, cy + h * 0.14f), radius = w * 0.26f,
            ),
            topLeft = Offset.Zero, size = size,
        )

        // 2) Çizginin yumuşak hâlesi: kalın ve silik -> ince ve parlak
        val halo = listOf(10.dp.toPx() to 0.10f, 6.dp.toPx() to 0.20f, 3.5.dp.toPx() to 0.40f)
        halo.forEach { (sw, a) ->
            drawPath(path, brush = line, alpha = a * b.coerceAtMost(1.1f),
                style = Stroke(width = sw, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        // 3) Parlak çekirdek çizgi
        drawPath(path, brush = line, style = Stroke(width = 1.8.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))

        // 4) Cam hissi: sol üstte hafif parlama
        drawOval(
            brush = Brush.radialGradient(
                0f to Color.White.copy(alpha = 0.16f), 1f to Color.Transparent,
                center = Offset(w * 0.30f, h * 0.10f), radius = w * 0.32f,
            ),
            topLeft = Offset.Zero, size = size,
        )
    }
}
