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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageBitmapConfig
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.PaintingStyle
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SweepGradientShader
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin
import androidx.compose.ui.graphics.Canvas as GfxCanvas

/**
 * Asistan açılınca ekran kenarlarında Siri / Apple Intelligence tarzı renkli parlama.
 * 1) Sağ kenarda küçük bir renk belirir, 2) büyüyerek dalga gibi tüm ekrana yayılır,
 * 3) renk yalnızca kenarlarda kalır ve renk değiştirmeye devam eder. Asistan kapanınca söner.
 *
 * PERFORMANS: Renkli kenar parlaması (yumuşak düşüşlü, renkleri hazır) bir kez, arka planda, düşük çözünürlüklü
 * tek bir resim olarak çizilir. Her karede yalnızca bu resim çizilir:
 *  - renk değişimi: resme ton döndürme filtresi (ColorMatrix) uygulanır,
 *  - dalga: resim büyüyen bir daireyle kırpılarak çizilir (tam ekran ara katman / karışım modu YOK).
 * Ayrıca açılıştan önce, görünmez şekilde bir kez çizilip ekran kartı programları ısıtılır (ilk karedeki takılmayı önler).
 */
@Composable
fun ScreenEdgeGlow(active: StateFlow<Boolean>, cornerPx: Float) {
    val on by active.collectAsState()
    val density = LocalDensity.current.density
    var size by remember { mutableStateOf(IntSize.Zero) }
    var glow by remember { mutableStateOf<ImageBitmap?>(null) }
    var warm by remember { mutableStateOf(false) }
    val reveal = remember { Animatable(0f) }   // 0 -> 1: sağdan büyüyen dalga
    val fade = remember { Animatable(0f) }     // genel görünürlük

    // Parlama resmi asistan açılmadan ÖNCE (pencere eklenir eklenmez) arka planda hazırlanır
    LaunchedEffect(size, cornerPx) {
        if (size.width > 1 && size.height > 1) {
            glow = withContext(Dispatchers.Default) { buildGlowBitmap(size, cornerPx, density) }
            warm = true; delay(350); warm = false     // görünmez ısınma çizimi
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
        val g = glow
        val visible = on || fade.value > 0.001f
        // Görünmezken (ve ısınma bitince) hiçbir şey çizilmez, sonsuz animasyon çalışmaz
        if (g != null && (visible || warm)) EdgeGlowCanvas(g, size, reveal, fade, warmOnly = warm && !visible)
    }
}

private const val LAYERS = 22               // parlama katman sayısı (çok katman = çizgisiz, yumuşak geçiş)
private const val GLOW_DEPTH_DP = 64f       // parlamanın ekran kenarından içeri uzanma derinliği
private const val LAYER_ALPHA = 0.048f
private const val BITMAP_SCALE = 0.25f      // resim çözünürlüğü (yumuşak olduğu için düşük çözünürlük yeter)
private const val EDGE_LAYERS = 5           // dalga cephesinin yumuşaklığı için iç içe kırpma sayısı

private val GLOW_COLOR_LIST: List<Color> by lazy { List(13) { i -> siriColor(i / 12f) } }

/** Renkli, kenarda yoğun / içeride sönen parlama resmi. */
private fun buildGlowBitmap(size: IntSize, cornerPx: Float, density: Float): ImageBitmap {
    val k = BITMAP_SCALE
    val wS = max((size.width * k).toInt(), 2)
    val hS = max((size.height * k).toInt(), 2)
    val bmp = ImageBitmap(wS, hS, ImageBitmapConfig.Argb8888)
    val canvas = GfxCanvas(bmp)
    val paint = Paint().apply {
        style = PaintingStyle.Stroke
        blendMode = BlendMode.Plus
        isAntiAlias = true
        shader = SweepGradientShader(Offset(wS / 2f, hS / 2f), GLOW_COLOR_LIST)
        alpha = LAYER_ALPHA
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

/** CSS hue-rotate matrisi: parlamanın renklerini döndürür (beyaz / gri sabit kalır). */
private fun setHueRotate(cm: ColorMatrix, turns: Float) {
    val a = (turns * 2.0 * PI).toFloat()
    val c = cos(a); val s = sin(a)
    val v = cm.values
    v[0] = 0.213f + c * 0.787f - s * 0.213f; v[1] = 0.715f - c * 0.715f - s * 0.715f; v[2] = 0.072f - c * 0.072f + s * 0.928f; v[3] = 0f; v[4] = 0f
    v[5] = 0.213f - c * 0.213f + s * 0.143f; v[6] = 0.715f + c * 0.285f + s * 0.140f; v[7] = 0.072f - c * 0.072f - s * 0.283f; v[8] = 0f; v[9] = 0f
    v[10] = 0.213f - c * 0.213f - s * 0.787f; v[11] = 0.715f - c * 0.715f + s * 0.715f; v[12] = 0.072f + c * 0.928f + s * 0.072f; v[13] = 0f; v[14] = 0f
    v[15] = 0f; v[16] = 0f; v[17] = 0f; v[18] = 1f; v[19] = 0f
}

@Composable
private fun EdgeGlowCanvas(
    glow: ImageBitmap, size: IntSize, reveal: Animatable<Float, *>, fade: Animatable<Float, *>, warmOnly: Boolean,
) {
    val t = rememberInfiniteTransition(label = "edgeGlow")
    val hue = t.animateFloat(0f, 1f, infiniteRepeatable(tween(8000, easing = LinearEasing)), label = "hue")
    val pulse = t.animateFloat(0.88f, 1f, infiniteRepeatable(tween(1800, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "pulse")
    val dst = remember(size) { IntSize(size.width, size.height) }
    val cm = remember { ColorMatrix() }
    val clip = remember { Path() }

    Canvas(Modifier.fillMaxSize()) {
        val w = size.width.toFloat(); val h = size.height.toFloat()
        val vis = if (warmOnly) 0.02f else fade.value            // ısınma çizimi: neredeyse görünmez
        if (vis <= 0.001f) return@Canvas
        val p = if (warmOnly) 0.4f else reveal.value
        setHueRotate(cm, hue.value)
        val filter = ColorFilter.colorMatrix(cm)
        val a = vis * pulse.value

        // Dalga: merkezi sağ kenarın ortası olan büyüyen daire. Yumuşak cephe için iç içe birkaç kırpma,
        // her biri kendi alfasıyla çizilir (toplam alfa dıştan içe 0 -> 1 artar).
        val wx = w; val wy = h / 2f
        val maxR = hypot(w, h / 2f) * 1.25f
        val soft = maxR * 0.30f
        val r = maxR * (0.03f + 0.97f * p) + soft * 0.2f

        if (p >= 1f) {
            drawImage(glow, dstSize = dst, alpha = a, colorFilter = filter, filterQuality = FilterQuality.High)
        } else {
            for (j in 0 until EDGE_LAYERS) {
                val rj = r - soft * j / (EDGE_LAYERS - 1).toFloat()
                if (rj <= 1f) break
                // hedef toplam alfa (j+1)/N; bu katmanın alfası: (c_j - c_{j-1}) / (1 - c_{j-1})
                val cPrev = j / EDGE_LAYERS.toFloat()
                val cNow = (j + 1) / EDGE_LAYERS.toFloat()
                val layerA = (cNow - cPrev) / (1f - cPrev)
                clip.reset()
                clip.addOval(Rect(Offset(wx, wy), rj))
                clipPath(clip) {
                    drawImage(glow, dstSize = dst, alpha = a * layerA, colorFilter = filter, filterQuality = FilterQuality.High)
                }
            }
            // Dalga cephesinde geçici parlak halka
            val ringA = (1f - p) * 0.55f * (if (p < 0.08f) p / 0.08f else 1f) * (if (warmOnly) 0.02f else fade.value)
            if (ringA > 0.001f) {
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
