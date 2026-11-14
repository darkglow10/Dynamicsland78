package com.example.dynamicisland

import androidx.compose.animation.core.Animatable
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.StateFlow

/**
 * Asistan açılınca tüm ekranın kenarlarında Siri / Apple Intelligence tarzı renkli parlama.
 * Açılışta renkler ekranın SAĞINDAN SOLUNA doğru yayılır, sonra kenarlarda kalır ve renk değiştirmeye devam eder.
 * Asistan kapanınca yumuşakça söner. Pencere dokunmatik değildir (bkz. IslandOverlayService.attachEdgeGlow).
 */
@Composable
fun ScreenEdgeGlow(active: StateFlow<Boolean>) {
    val on by active.collectAsState()
    val reveal = remember { Animatable(0f) }   // 0 -> 1: sağdan sola yayılma
    val fade = remember { Animatable(0f) }     // genel görünürlük

    LaunchedEffect(on) {
        if (on) {
            reveal.snapTo(0f)
            fade.snapTo(1f)
            reveal.animateTo(1f, tween(1300, easing = FastOutSlowInEasing))
        } else {
            fade.animateTo(0f, tween(450))
        }
    }

    // Görünmezken hiçbir şey çizilmez ve sonsuz animasyon çalışmaz
    if (on || fade.value > 0.001f) EdgeGlowCanvas(reveal.value, fade.value)
}

private const val GLOW_COLORS = 12

@Composable
private fun EdgeGlowCanvas(reveal: Float, fade: Float) {
    val t = rememberInfiniteTransition(label = "edgeGlow")
    // Kenarlarda dururken renkler akar (tam döngü, sorunsuz başa döner)
    val hue = t.animateFloat(0f, 1f, infiniteRepeatable(tween(6000, easing = LinearEasing)), label = "hue")
    // Hafif "nefes": parlama kalınlığı yavaşça değişir
    val pulse = t.animateFloat(0.9f, 1.1f, infiniteRepeatable(tween(1800, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "pulse")

    Canvas(
        Modifier
            .fillMaxSize()
            .graphicsLayer {
                compositingStrategy = CompositingStrategy.Offscreen   // maske (DstIn) yalnızca bu katmanı etkiler
                alpha = fade
            },
    ) {
        val w = size.width; val h = size.height
        if (w <= 1f || h <= 1f) return@Canvas
        val hp = hue.value
        val pl = pulse.value

        // Renkler ekranın çevresinde dolanır ve hue ile kayar
        val colors = List(GLOW_COLORS + 1) { i -> siriColor(i / GLOW_COLORS.toFloat() + hp) }
        val brush = Brush.sweepGradient(colors, center = Offset(w / 2f, h / 2f))

        val corner = 52.dp.toPx()
        // Kalın & sönük -> ince & parlak katmanlar: bulanık parlama hissi
        val widths = floatArrayOf(46f, 30f, 18f, 8f, 3f)
        val alphas = floatArrayOf(0.10f, 0.17f, 0.28f, 0.50f, 0.85f)
        for (i in widths.indices) {
            val sw = widths[i].dp.toPx() * pl
            val inset = sw / 2f
            drawRoundRect(
                brush = brush,
                topLeft = Offset(inset, inset),
                size = Size(w - sw, h - sw),
                cornerRadius = CornerRadius((corner - inset).coerceAtLeast(0f)),
                style = Stroke(width = sw),
                alpha = alphas[i],
                blendMode = BlendMode.Plus,
            )
        }

        // Sağdan sola açılma maskesi: cephe sağdan başlar, sola ilerler; cephenin sağı görünür, solu gizli
        val soft = w * 0.35f
        val front = (w + soft) - reveal * (w + soft * 2f)          // reveal 0: ekranın sağ dışı, 1: sol dışı
        drawRect(
            brush = Brush.horizontalGradient(
                0f to Color.Transparent, 1f to Color.Black,
                startX = front - soft, endX = front,
            ),
            blendMode = BlendMode.DstIn,
        )
    }
}
