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
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * Normal adadan videodaki yumuşak köşeli şekle akıcı geçiş.
 * t = 0 -> normal ada, t = 1 -> asistan şekli.
 */
class AssistantShape(private val t: Float, private val baseRadiusDp: Float = 36f) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val base = min(baseRadiusDp * density.density, size.height / 2f)
        val k = t.coerceIn(0f, 1f)
        val rx = base + (size.width * CORNER_X - base) * k
        val ry = base + (size.height * CORNER_Y - base) * k
        return Outline.Rounded(RoundRect(0f, 0f, size.width, size.height, CornerRadius(rx, ry)))
    }

    private companion object {
        const val CORNER_X = 0.36f   // 0.5 = tam elips, küçüldükçe daha köşeli
        const val CORNER_Y = 0.42f
    }
}

// Videodaki renk geçişi: mavi -> mor -> pembe/kırmızı -> turuncu -> sarı -> beyaz -> cyan -> mavi (döngüsel)
private val SIRI = arrayOf(
    Color(0xFF0A84FF), Color(0xFF7B4DFF), Color(0xFFFF2D87), Color(0xFFFF3B30),
    Color(0xFFFF9500), Color(0xFFFFD60A), Color(0xFFFFFFFF), Color(0xFF64D2FF), Color(0xFF0A84FF),
)

private fun siriColor(t: Float, alpha: Float = 1f): Color {
    val n = SIRI.size - 1
    val x = (((t % 1f) + 1f) % 1f) * n
    val i = x.toInt().coerceIn(0, n - 1)
    return lerp(SIRI[i], SIRI[i + 1], x - i).copy(alpha = alpha)
}

private const val STEPS = 48
private const val SAMPLES = 9

/**
 * Siri / Gemini benzeri asistan animasyonu. Çizginin KENDİSİ animasyondur: ortada kalın, dalgalı,
 * uçlara doğru sivrilen bir bant; rengi (mavi/mor/kırmızı/turuncu/sarı/beyaz/cyan) boyunca akar ve
 * bant sallanırken eğimi / kalınlığı değişir. Altında gümüş gri ikinci bir dalga ve alt kenarda
 * mat gri bir geçiş var (videodaki cam yansıması). Değerler yalnızca çizim fazında okunur.
 */
@Composable
fun AssistantOrb() {
    val t = rememberInfiniteTransition(label = "orb")
    // Dalga fazı ve renk fazı: tam döngüde başa sorunsuz döner (zamanla çarpanlar tam sayı)
    val wave = t.animateFloat(0f, 1f, infiniteRepeatable(tween(3600, easing = LinearEasing)), label = "wave")
    val hue = t.animateFloat(0f, 1f, infiniteRepeatable(tween(8000, easing = LinearEasing)), label = "hue")
    val breathe = t.animateFloat(
        0.85f, 1.15f,
        infiniteRepeatable(tween(1500, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "br",
    )
    val path = remember { Path() }
    val yc = remember { FloatArray(STEPS + 1) }
    val th = remember { FloatArray(STEPS + 1) }
    val y2 = remember { FloatArray(STEPS + 1) }
    val th2 = remember { FloatArray(STEPS + 1) }

    Canvas(Modifier.fillMaxSize()) {
        val w = size.width; val h = size.height
        if (w <= 1f || h <= 1f) return@Canvas
        val p = wave.value
        val hp = hue.value
        val b = breathe.value
        val tau = (2 * PI).toFloat()
        val pi = PI.toFloat()

        val cy = h * 0.60f
        val x0 = w * 0.03f
        val span = w * 0.94f

        // 1) Alt kenarda mat gri geçiş (cam yansıması)
        drawRect(
            Brush.verticalGradient(
                colorStops = arrayOf(0.60f to Color.Transparent, 1f to Color(0xFFAEB4BE).copy(alpha = 0.62f)),
            ),
        )

        // 2) Ana bant: merkez çizgisi ve kalınlığı hesapla
        for (i in 0..STEPS) {
            val u = i / STEPS.toFloat()
            val env = sin(pi * u).coerceAtLeast(0f).pow(0.7f)
            val tilt = (u - 0.5f) * h * 0.18f * sin(tau * p + 0.4f)
            yc[i] = cy + tilt +
                env * h * 0.055f * b * sin(tau * (u * 1.25f - p)) +
                env * h * 0.025f * sin(tau * (u * 2.6f + p * 2f))
            th[i] = h * 0.145f * b * (0.65f + 0.4f * sin(tau * (u * 1.7f + p))) * env
        }
        val cols = List(SAMPLES) { k ->
            val s = k / (SAMPLES - 1).toFloat()
            val a = sin(pi * s).coerceAtLeast(0f).pow(0.5f)
            siriColor(s * 0.85f + hp, a)
        }
        val ribbon = Brush.horizontalGradient(cols, startX = x0, endX = x0 + span)
        val scales = floatArrayOf(1.9f, 1.4f, 1.0f, 0.6f)
        val alphas = floatArrayOf(0.16f, 0.26f, 0.46f, 0.95f)
        scales.forEachIndexed { li, sc ->
            path.reset()
            for (i in 0..STEPS) {
                val x = x0 + span * i / STEPS
                val y = yc[i] - th[i] * sc / 2f
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            for (i in STEPS downTo 0) path.lineTo(x0 + span * i / STEPS, yc[i] + th[i] * sc / 2f)
            path.close()
            drawPath(path, ribbon, alpha = alphas[li], blendMode = BlendMode.Plus)
        }

        // 3) Bandın üst kenarında parlak beyaz çizgi (tepe ışığı)
        path.reset()
        for (i in 0..STEPS) {
            val x = x0 + span * i / STEPS
            val y = yc[i] - th[i] * 0.32f
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(
            path,
            Brush.horizontalGradient(
                0f to Color(0x00FFFFFF), 0.30f to Color(0xAAFFFFFF), 0.5f to Color(0xFFFFFFFF),
                0.70f to Color(0xAAFFFFFF), 1f to Color(0x00FFFFFF), startX = x0, endX = x0 + span,
            ),
            alpha = 0.85f,
            style = Stroke(width = 1.2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
            blendMode = BlendMode.Plus,
        )

        // 4) Ana bandın yansıması: aynı dalga ve kalınlıktan türetilir, bu yüzden birebir senkron hareket eder
        for (i in 0..STEPS) {
            y2[i] = yc[i] + th[i] * 0.5f + h * 0.03f
            th2[i] = th[i] * 0.22f
        }
        val silver = Brush.horizontalGradient(
            0f to Color(0x00DADDE4), 0.25f to Color(0xFFC9CDD6), 0.5f to Color(0xFFFFFFFF),
            0.75f to Color(0xFFC9CDD6), 1f to Color(0x00DADDE4), startX = x0, endX = x0 + span,
        )
        floatArrayOf(2.2f, 1.0f, 0.4f).forEachIndexed { li, sc ->
            path.reset()
            for (i in 0..STEPS) {
                val x = x0 + span * i / STEPS
                val y = y2[i] - th2[i] * sc / 2f
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            for (i in STEPS downTo 0) path.lineTo(x0 + span * i / STEPS, y2[i] + th2[i] * sc / 2f)
            path.close()
            drawPath(path, silver, alpha = floatArrayOf(0.10f, 0.22f, 0.45f)[li], blendMode = BlendMode.Plus)
        }

        // 5) Cam hissi: sol üstte hafif parlama
        drawRect(
            Brush.radialGradient(
                0f to Color.White.copy(alpha = 0.12f), 1f to Color.Transparent,
                center = Offset(w * 0.30f, h * 0.08f), radius = w * 0.30f,
            ),
        )
    }
}
