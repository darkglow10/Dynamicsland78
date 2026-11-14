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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageBitmapConfig
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.PaintingStyle
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlin.math.hypot
import kotlin.math.max
import androidx.compose.ui.graphics.Canvas as GfxCanvas

/**
 * Asistan açılınca ekran kenarlarında Siri / Apple Intelligence tarzı renkli parlama.
 * 1) Sağ kenarda küçük bir renk belirir, 2) büyüyerek dalga gibi tüm ekrana yayılır,
 * 3) renk yalnızca kenarlarda kalır ve renk değiştirmeye devam eder. Asistan kapanınca söner.
 *
 * PERFORMANS: Kenar parlamasının şekli (22 katmanlık yumuşak düşüş) bir kez, arka planda, düşük çözünürlüklü
 * bir maske olarak çizilir. Her karede yalnızca 3-4 çizim yapılır: maske + dönen renk + dalga maskesi (+ halka).
 */
@Composable
fun ScreenEdgeGlow(active: StateFlow<Boolean>, cornerPx: Float) {
    val on by active.collectAsState()
    val density = LocalDensity.current.density
    var size by remember { mutableStateOf(IntSize.Zero) }
    var mask by remember { mutableStateOf<ImageBitmap?>(null) }
    val reveal = remember { Animatable(0f) }   // 0 -> 1: sağdan büyüyen dalga
    val fade = remember { Animatable(0f) }     // genel görünürlük

    // Maske asistan açılmadan ÖNCE (pencere eklenir eklenmez) arka planda hazırlanır: açılışta takılma olmaz
    LaunchedEffect(size, cornerPx) {
        if (size.width > 1 && size.height > 1) {
            mask = withContext(Dispatchers.Default) { buildEdgeMask(size, cornerPx, density) }
        }
    }

    LaunchedEffect(on) {
        if (on) {
            reveal.snapTo(0f)
            fade.snapTo(1f)
            reveal.animateTo(1f, tween(1900, easing = CubicBezierEasing(0.55f, 0.05f, 0.25f, 1f)))
        } else {
            fade.animateTo(0f, tween(450))
        }
    }

    Box(Modifier.fillMaxSize().onSizeChanged { size = it }) {
        val m = mask
        // Görünmezken hiçbir şey çizilmez ve sonsuz animasyon çalışmaz
        if (m != null && (on || fade.value > 0.001f)) EdgeGlowCanvas(m, size, reveal, fade)
    }
}

private const val LAYERS = 22               // parlama katman sayısı (çok katman = çizgisiz, yumuşak geçiş)
private const val GLOW_DEPTH_DP = 64f       // parlamanın ekran kenarından içeri uzanma derinliği
private const val LAYER_ALPHA = 0.048f
private const val MASK_SCALE = 0.25f        // maske çözünürlüğü (yumuşak olduğu için düşük çözünürlük yeter)

/** Ekran kenarı parlamasının şekli: kenarda yoğun, içeri doğru sönen alfa maskesi (renk sonra verilir). */
private fun buildEdgeMask(size: IntSize, cornerPx: Float, density: Float): ImageBitmap {
    val k = MASK_SCALE
    val wS = max((size.width * k).toInt(), 2)
    val hS = max((size.height * k).toInt(), 2)
    val bmp = ImageBitmap(wS, hS, ImageBitmapConfig.Argb8888)
    val canvas = GfxCanvas(bmp)
    val paint = Paint().apply {
        style = PaintingStyle.Stroke
        blendMode = BlendMode.Plus
        isAntiAlias = true
        color = Color.White.copy(alpha = LAYER_ALPHA)
    }
    val depth = GLOW_DEPTH_DP * density * k
    val corner = cornerPx * k
    for (i in 0 until LAYERS) {
        val f = (i + 1) / LAYERS.toFloat()
        val sw = depth * f * f
        val inset = sw / 2f
        val r = (corner - inset).coerceAtLeast(0f)
        paint.strokeWidth = sw
        canvas.drawRoundRect(inset, inset, wS - inset, hS - inset, r, r, paint)
    }
    return bmp
}

private val GLOW_COLOR_LIST: List<Color> by lazy { List(13) { i -> siriColor(i / 12f) } }

@Composable
private fun EdgeGlowCanvas(mask: ImageBitmap, size: IntSize, reveal: Animatable<Float, *>, fade: Animatable<Float, *>) {
    val t = rememberInfiniteTransition(label = "edgeGlow")
    val hue = t.animateFloat(0f, 1f, infiniteRepeatable(tween(8000, easing = LinearEasing)), label = "hue")
    val pulse = t.animateFloat(0.88f, 1f, infiniteRepeatable(tween(1800, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "pulse")
    // Renk halkası sabit; "renk değiştirme" halkayı döndürerek yapılır (her karede yeni renk listesi / fırça üretilmez)
    val sweep = remember(size) { Brush.sweepGradient(GLOW_COLOR_LIST, center = Offset(size.width / 2f, size.height / 2f)) }
    val dst = remember(size) { IntSize(size.width, size.height) }

    Canvas(
        Modifier
            .fillMaxSize()
            .graphicsLayer {
                compositingStrategy = CompositingStrategy.Offscreen
                alpha = fade.value                                     // yalnızca çizim aşamasında okunur: yeniden kompozisyon yok
            },
    ) {
        val w = size.width.toFloat(); val h = size.height.toFloat()
        val cx = w / 2f; val cy = h / 2f
        val p = reveal.value

        // 1) Kenar parlaması şekli (hazır maske)
        drawImage(mask, dstSize = dst, alpha = pulse.value, filterQuality = FilterQuality.High)

        // 2) Maskenin içine dönen renk halkası: yalnızca maskenin olduğu yere boyar
        val d = hypot(w, h)
        rotate(degrees = hue.value * 360f, pivot = Offset(cx, cy)) {
            drawRect(sweep, topLeft = Offset(cx - d, cy - d), size = Size(2f * d, 2f * d), blendMode = BlendMode.SrcIn)
        }

        // 3) Dalga: merkezi sağ kenarın ortası olan büyüyen daire; içi görünür, dışı gizli
        val wx = w; val wy = h / 2f
        val maxR = hypot(w, h / 2f) * 1.25f
        val soft = maxR * 0.30f
        val r = maxR * (0.03f + 0.97f * p) + soft * 0.2f
        if (p < 1f) {
            val inner = ((r - soft) / r).coerceIn(0f, 0.98f)
            drawRect(
                Brush.radialGradient(
                    0f to Color.Black, inner to Color.Black, 1f to Color.Transparent,
                    center = Offset(wx, wy), radius = max(r, 1f),
                ),
                blendMode = BlendMode.DstIn,
            )

            // 4) Dalga cephesinde geçici parlak halka
            val ringA = (1f - p) * 0.55f * (if (p < 0.08f) p / 0.08f else 1f)
            if (ringA > 0.01f) {
                val c = GLOW_COLOR_LIST[((hue.value * 12f).toInt() + 2) % 12]
                drawRect(
                    Brush.radialGradient(
                        0f to Color.Transparent, 0.55f to Color.Transparent,
                        0.88f to c.copy(alpha = ringA), 1f to Color.Transparent,
                        center = Offset(wx, wy), radius = max(r - soft * 0.15f, 1f),
                    ),
                    blendMode = BlendMode.Plus,
                )
            }
        }
    }
}
