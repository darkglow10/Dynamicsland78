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
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * Normal adadan videodaki "yumuşak kare-elips" (squircle benzeri) şekle akıcı geçiş.
 * t = 0 -> normal ada, t = 1 -> asistan şekli. Köşe oranlarını değiştirerek şekli ayarlayabilirsin.
 */
class AssistantShape(private val t: Float, private val baseRadiusDp: Float = 36f) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val base = min(baseRadiusDp * density.density, size.height / 2f)
        val k = t.coerceIn(0f, 1f)
        val rx = base + (size.width * CORNER_X - base) * k     // yatay köşe yarıçapı (genişliğin oranı)
        val ry = base + (size.height * CORNER_Y - base) * k    // dikey köşe yarıçapı (yüksekliğin oranı)
        return Outline.Rounded(RoundRect(0f, 0f, size.width, size.height, CornerRadius(rx, ry)))
    }

    private companion object {
        const val CORNER_X = 0.36f   // 0.5 = tam elips, küçüldükçe daha köşeli
        const val CORNER_Y = 0.42f
    }
}

private class RibbonSpec(
    val stops: Array<Pair<Float, Color>>,
    val freq: Float,     // çizgi boyunca dalga sayısı
    val speed: Float,    // akış yönü ve hızı (işaret = yön)
    val ph: Float,       // faz kayması
    val thick: Float,    // kalınlık (yüksekliğin oranı)
    val yOff: Float,     // dikey kayma (yüksekliğin oranı)
    val alpha: Float,
)

// Renk bantları üst üste toplamsal karışır: turuncu/sarı/beyaz (sol), beyaz/cyan/mavi (sağ), mor + kırmızı derinlik
private val RIBBONS = listOf(
    RibbonSpec(arrayOf(0f to Color(0x00FF8A00), 0.18f to Color(0xFFFF8A00), 0.40f to Color(0xFFFFD60A),
        0.62f to Color(0xFFFFFFFF), 0.84f to Color(0x00FFFFFF)), 1.5f, 1f, 0.0f, 0.15f, 0.00f, 1f),
    RibbonSpec(arrayOf(0.20f to Color(0x00FFFFFF), 0.42f to Color(0xFFFFFFFF), 0.60f to Color(0xFF64D2FF),
        0.80f to Color(0xFF0A84FF), 1f to Color(0x000A84FF)), 1.9f, -1f, 0.3f, 0.17f, 0.01f, 1f),
    RibbonSpec(arrayOf(0f to Color(0x00BF5AF2), 0.35f to Color(0xFFBF5AF2), 0.62f to Color(0xFF3A5BFF),
        1f to Color(0x000A84FF)), 1.1f, 0.6f, 0.6f, 0.30f, 0.07f, 0.55f),
    RibbonSpec(arrayOf(0f to Color(0x00FF375F), 0.22f to Color(0xFFFF375F), 0.52f to Color(0xFFFF9500),
        0.86f to Color(0x00FF9500)), 1.3f, -0.7f, 0.8f, 0.26f, 0.05f, 0.50f),
)

private const val STEPS = 48

/**
 * Siri / Gemini benzeri asistan animasyonu. Birkaç renkli, uçlarda sivrilen dalgalı bant üst üste
 * toplamsal olarak karışır (turuncu, sarı, beyaz, cyan, mavi, mor); ortada parlak ince çekirdek çizgi
 * vardır. Animasyon değerleri yalnızca çizim fazında okunur (recomposition yok).
 */
@Composable
fun AssistantOrb() {
    val t = rememberInfiniteTransition(label = "orb")
    val phase = t.animateFloat(0f, 1f, infiniteRepeatable(tween(4200, easing = LinearEasing)), label = "ph")
    val breathe = t.animateFloat(
        0.80f, 1.20f,
        infiniteRepeatable(tween(1500, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "br",
    )
    val path = remember { Path() }
    val yc = remember { FloatArray(STEPS + 1) }
    val th = remember { FloatArray(STEPS + 1) }

    Canvas(Modifier.fillMaxSize()) {
        val w = size.width; val h = size.height
        if (w <= 1f || h <= 1f) return@Canvas
        val p = phase.value
        val b = breathe.value
        val tau = (2 * PI).toFloat()
        val pi = PI.toFloat()

        val cy = h * 0.60f
        val x0 = w * 0.06f
        val span = w * 0.88f

        // Alt tarafta geniş, yumuşak renk ışıması (sol turuncu, sağ mavi)
        drawRect(
            Brush.radialGradient(
                0f to Color(0xFF0A84FF).copy(alpha = 0.40f * b), 1f to Color.Transparent,
                center = Offset(w * 0.68f, h * 0.88f), radius = w * 0.42f,
            ),
        )
        drawRect(
            Brush.radialGradient(
                0f to Color(0xFFFF8A00).copy(alpha = 0.32f * b), 1f to Color.Transparent,
                center = Offset(w * 0.28f, h * 0.84f), radius = w * 0.36f,
            ),
        )

        // Renk bantları
        RIBBONS.forEach { r ->
            for (i in 0..STEPS) {
                val u = i / STEPS.toFloat()
                val env = sin(pi * u).coerceAtLeast(0f).pow(0.8f)
                val wave = sin(tau * (u * r.freq - p * r.speed + r.ph)) +
                    0.45f * sin(tau * (u * r.freq * 2.1f + p * r.speed * 1.6f))
                yc[i] = cy + r.yOff * h - h * 0.05f * env + h * 0.045f * b * env * wave
                th[i] = h * r.thick * env * (0.75f + 0.35f * sin(tau * (p * 0.5f * r.speed + u * 1.3f + r.ph))) * b
            }
            val shift = sin(tau * (p * 0.5f + r.ph)) * span * 0.10f
            val brush = Brush.horizontalGradient(colorStops = r.stops, startX = x0 + shift, endX = x0 + span + shift)
            // Yumuşak kenar: kalın-silik katmandan ince-parlak katmana
            floatArrayOf(1f, 0.66f, 0.38f, 0.18f).forEachIndexed { li, s ->
                val a = r.alpha * floatArrayOf(0.14f, 0.20f, 0.32f, 0.55f)[li]
                path.reset()
                for (i in 0..STEPS) {
                    val x = x0 + span * i / STEPS
                    val y = yc[i] - th[i] * s / 2f
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                for (i in STEPS downTo 0) path.lineTo(x0 + span * i / STEPS, yc[i] + th[i] * s / 2f)
                path.close()
                drawPath(path, brush, alpha = a, blendMode = BlendMode.Plus)
            }
        }

        // Parlak ince çekirdek çizgi
        path.reset()
        for (i in 0..STEPS) {
            val x = x0 + span * i / STEPS
            if (i == 0) path.moveTo(x, yc[i]) else path.lineTo(x, yc[i])
        }
        drawPath(
            path,
            Brush.horizontalGradient(
                0f to Color(0x00FFFFFF), 0.35f to Color(0xCCFFFFFF), 0.5f to Color(0xFFFFFFFF),
                0.65f to Color(0xCCFFFFFF), 1f to Color(0x00FFFFFF), startX = x0, endX = x0 + span,
            ),
            alpha = 0.9f,
            style = Stroke(width = 1.4.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
            blendMode = BlendMode.Plus,
        )

        // Cam hissi: sol üstte hafif parlama
        drawRect(
            Brush.radialGradient(
                0f to Color.White.copy(alpha = 0.14f), 1f to Color.Transparent,
                center = Offset(w * 0.30f, h * 0.08f), radius = w * 0.30f,
            ),
        )
    }
}
