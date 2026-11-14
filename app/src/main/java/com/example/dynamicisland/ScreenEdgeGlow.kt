package com.example.dynamicisland

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
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
import kotlin.math.hypot
import kotlin.math.max

/**
 * Asistan açılınca ekran kenarlarında Siri / Apple Intelligence tarzı renkli parlama.
 * 1) Ekranın sağ kenarında küçük bir renk belirir,
 * 2) büyüyerek dalga gibi tüm ekrana yayılır (üst, alt ve sol kenar dahil),
 * 3) dalga geçince renk yalnızca kenarlarda kalır ve renk değiştirmeye devam eder.
 * Asistan kapanınca yumuşakça söner.
 *
 * cornerPx: ekranın gerçek köşe yarıçapı (parlama dış kenarı ekran kenarına oturur, köşeler belli olmaz).
 */
@Composable
fun ScreenEdgeGlow(active: StateFlow<Boolean>, cornerPx: Float) {
    val on by active.collectAsState()
    val reveal = remember { Animatable(0f) }   // 0 -> 1: sağdan büyüyen dalga
    val fade = remember { Animatable(0f) }     // genel görünürlük

    LaunchedEffect(on) {
        if (on) {
            reveal.snapTo(0f)
            fade.snapTo(1f)
            // Yavaş başlar (küçük renk), sonra hızlanıp ekranı kaplar
            reveal.animateTo(1f, tween(1900, easing = CubicBezierEasing(0.55f, 0.05f, 0.25f, 1f)))
        } else {
            fade.animateTo(0f, tween(450))
        }
    }

    // Görünmezken hiçbir şey çizilmez ve sonsuz animasyon çalışmaz
    if (on || fade.value > 0.001f) EdgeGlowCanvas(reveal.value, fade.value, cornerPx)
}

private const val GLOW_COLORS = 12
private const val LAYERS = 22               // parlama katman sayısı (çok katman = çizgisiz, yumuşak geçiş)
private const val GLOW_DEPTH_DP = 64f       // parlamanın ekran kenarından içeri uzanma derinliği
private const val LAYER_ALPHA = 0.048f

@Composable
private fun EdgeGlowCanvas(reveal: Float, fade: Float, cornerPx: Float) {
    val t = rememberInfiniteTransition(label = "edgeGlow")
    val hue = t.animateFloat(0f, 1f, infiniteRepeatable(tween(6000, easing = LinearEasing)), label = "hue")
    val pulse = t.animateFloat(0.92f, 1.08f, infiniteRepeatable(tween(1800, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "pulse")

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

        val colors = List(GLOW_COLORS + 1) { i -> siriColor(i / GLOW_COLORS.toFloat() + hp) }
        val brush = Brush.sweepGradient(colors, center = Offset(w / 2f, h / 2f))

        // Dış kenarı ekran kenarına oturan, içeri doğru yumuşakça sönen parlama.
        // Katman i, kenardan itibaren genişliği sw_i olan bir bant; üst üste binince kenarda yoğun, içeride sönük olur.
        val depth = GLOW_DEPTH_DP.dp.toPx() * pl
        for (i in 0 until LAYERS) {
            val f = (i + 1) / LAYERS.toFloat()
            val sw = depth * f * f                          // ikinci derece dağılım: kenara yakın daha sık katman
            val inset = sw / 2f
            drawRoundRect(
                brush = brush,
                topLeft = Offset(inset, inset),
                size = Size(w - sw, h - sw),
                cornerRadius = CornerRadius((cornerPx - inset).coerceAtLeast(0f)),
                style = Stroke(width = sw),
                alpha = LAYER_ALPHA,
                blendMode = BlendMode.Plus,
            )
        }

        // Dalga: merkezi ekranın SAĞ kenarının ortası olan büyüyen daire. İçi görünür, dışı gizli.
        val cx = w; val cy = h / 2f
        val maxR = hypot(w, h / 2f) * 1.25f
        val soft = maxR * 0.30f
        val r = maxR * (0.03f + 0.97f * reveal) + soft * 0.2f
        val inner = ((r - soft) / r).coerceIn(0f, 0.98f)
        drawRect(
            brush = Brush.radialGradient(
                0f to Color.Black, inner to Color.Black, 1f to Color.Transparent,
                center = Offset(cx, cy), radius = max(r, 1f),
            ),
            blendMode = BlendMode.DstIn,
        )

        // Dalga cephesinde geçici parlak halka: renk "dalgası" ekranı kaplarken görünür, sonra kaybolur
        val ringA = (1f - reveal).coerceIn(0f, 1f) * 0.55f * (if (reveal < 0.08f) reveal / 0.08f else 1f)
        if (ringA > 0.01f) {
            val rr = max(r - soft * 0.15f, 1f)
            val ringColor = siriColor(hp + 0.15f)
            drawRect(
                brush = Brush.radialGradient(
                    0f to Color.Transparent, 0.55f to Color.Transparent,
                    0.88f to ringColor.copy(alpha = ringA), 1f to Color.Transparent,
                    center = Offset(cx, cy), radius = rr,
                ),
                blendMode = BlendMode.Plus,
            )
        }
    }
}
