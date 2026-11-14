package com.example.dynamicisland

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionOnScreen
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.pow

/**
 * Adanın (kapalı halde) ekran üzerindeki konumu (piksel) ve animasyon hızı.
 * speed: 1 = normal, 2 = iki kat hızlı, 0.5 = yarı hızlı (ayarlardan gelir).
 */
class LaunchSpec(val cx: Float, val top: Float, val w: Float, val h: Float, val speed: Float = 1f)

// ---- Ayar noktaları
private const val BASE_MS = 650            // hız 1x iken adanın altından çıkıp ekranı kaplama süresi
private const val HOLD_MS = 350L           // ekran kaplandıktan sonra siyahın bekleme süresi (uygulama açılırken)
private const val FADE_MS = 240            // siyahın kalkması
private const val CORNER_DP = 44f          // büyürken köşe yuvarlaklığı (telefon ekran köşesine yakın)
private const val MARGIN_DP = 20f          // son boyutun ekranı taşma payı (köşe yarıçapının en az ~%30'u olmalı)
private const val START_W_DP = 46f         // adanın altından çıkan küçük şeklin başlangıç genişliği
private const val START_H_DP = 30f         // ... ve yüksekliği

// Yavaş başlar, yaklaştıkça hızlanır, sonda yumuşakça oturur
private val ApproachEasing = CubicBezierEasing(0.55f, 0f, 0.25f, 1f)

/**
 * Adanın altından küçük bir siyah şekil çıkar ve ekrana yaklaşıyormuş gibi gitgide büyüyerek tüm ekranı kaplar.
 * Büyüme üstel (katlanarak) olduğu için başta yavaş, yaklaştıkça hızlı hissedilir.
 * Ekran tamamen kapanınca [onCovered] çağrılır (uygulamayı o an aç); siyah bir süre sonra solar ve [onFinished] gelir.
 */
@Composable
fun LaunchTransition(spec: LaunchSpec, onCovered: () -> Unit, onFinished: () -> Unit) {
    val covered by rememberUpdatedState(onCovered)
    val finished by rememberUpdatedState(onFinished)
    val a = remember { Animatable(0f) }
    val fade = remember { Animatable(1f) }
    var origin by remember { mutableStateOf(Offset.Zero) }
    val ms = (BASE_MS / spec.speed.coerceIn(0.25f, 4f)).toInt()

    LaunchedEffect(Unit) {
        a.animateTo(1f, tween(ms, easing = ApproachEasing))
        covered()                                      // ekran tamamen siyah: uygulamayı aç
        delay(HOLD_MS)
        fade.animateTo(0f, tween(FADE_MS))
        finished()
    }

    Canvas(
        Modifier.fillMaxSize()
            .onGloballyPositioned { origin = it.positionOnScreen() }
            .graphicsLayer { alpha = fade.value }
    ) {
        val d = density
        val m = MARGIN_DP * d                          // ekran dışına taşma payı: yuvarlak köşeler ekranın dışında kalır
        val sw = size.width + 2 * m
        val sh = size.height + 2 * m
        val t = a.value

        // Başlangıç: adanın alt kenarında küçük bir şekil; bitiş: tüm ekran
        val w0 = START_W_DP * d
        val h0 = START_H_DP * d
        val cx0 = spec.cx
        val cy0 = spec.top + spec.h + h0 * 0.15f
        val w = w0 * (sw / w0).pow(t)                  // üstel büyüme: yaklaşma hissi
        val h = h0 * (sh / h0).pow(t)
        val cx = cx0 + (size.width / 2f - cx0) * t
        val cy = cy0 + (size.height / 2f - cy0) * t
        val alpha = (t * 12f).coerceIn(0f, 1f)         // ilk anda yumuşakça belirir
        val radius = minOf(CORNER_DP * d, minOf(w, h) / 2f)   // köşe yarıçapı büyürken sabit kalır: sivrilme olmaz

        translate(-origin.x, -origin.y) {
            if (t >= 1f) {
                drawRect(Color.Black, Offset(-m, -m), Size(sw, sh))
            } else {
                drawRoundRect(Color.Black.copy(alpha = alpha), Offset(cx - w / 2f, cy - h / 2f), Size(w, h), CornerRadius(radius))
            }
        }
    }
}

// ---- Müzik uygulamasından çıkış: panel adaya küçülür
private const val SHRINK_BASE_MS = 460     // hız 1x iken küçülme süresi
private const val SHRINK_FADE_MS = 140     // adaya varınca küçük şeklin silinmesi
private const val SHRINK_APPEAR_MS = 120   // panelin yumuşakça belirmesi (ani kararma olmasın)
private const val SWALLOW_AT = 0.8f        // küçülmenin bu noktasında ada şarkıyı yutmaya başlar
private const val START_W_FRAC = 0.58f     // panelin başlangıç genişliği (ekran genişliğine oranı)
private const val START_H_FRAC = 0.46f     // ... yüksekliği (ekran yüksekliğine oranı)
private const val START_CY_FRAC = 0.56f    // ... ve dikey merkezi

private val ShrinkEasing = CubicBezierEasing(0.4f, 0f, 0.6f, 1f)

/**
 * Ekranın ortasından belirip adaya doğru küçülen siyah panel (tam ekranı kaplamaz).
 * [onSwallow]: panel adaya yaklaşınca çağrılır (ada o an açılıp şarkıyı gösterir). Bitince [onFinished] gelir.
 */
@Composable
fun ShrinkToIsland(spec: LaunchSpec, onSwallow: () -> Unit, onFinished: () -> Unit) {
    val swallow by rememberUpdatedState(onSwallow)
    val finished by rememberUpdatedState(onFinished)
    val a = remember { Animatable(0f) }            // 0 = başlangıç kartı, 1 = adanın altında küçük şekil
    val appear = remember { Animatable(0f) }
    val fade = remember { Animatable(1f) }
    var origin by remember { mutableStateOf(Offset.Zero) }
    val ms = (SHRINK_BASE_MS / spec.speed.coerceIn(0.25f, 4f)).toInt()

    LaunchedEffect(Unit) {
        launch { appear.animateTo(1f, tween(SHRINK_APPEAR_MS)) }
        var fired = false
        a.animateTo(1f, tween(ms, easing = ShrinkEasing)) {
            if (!fired && value >= SWALLOW_AT) { fired = true; swallow() }
        }
        if (!fired) swallow()
        fade.animateTo(0f, tween(SHRINK_FADE_MS))
        finished()
    }

    Canvas(
        Modifier.fillMaxSize()
            .onGloballyPositioned { origin = it.positionOnScreen() }
            .graphicsLayer { alpha = appear.value * fade.value }
    ) {
        val d = density
        val t = a.value

        // Başlangıç: ekranın ortasında kart; bitiş: adanın alt kenarında küçük şekil
        val w0 = size.width * START_W_FRAC
        val h0 = size.height * START_H_FRAC
        val cx0 = size.width / 2f
        val cy0 = size.height * START_CY_FRAC
        val w1 = START_W_DP * d
        val h1 = START_H_DP * d
        val cx1 = spec.cx
        val cy1 = spec.top + spec.h + h1 * 0.15f
        val w = w0 * (w1 / w0).pow(t)                  // üstel küçülme
        val h = h0 * (h1 / h0).pow(t)
        val cx = cx0 + (cx1 - cx0) * t
        val cy = cy0 + (cy1 - cy0) * t
        val radius = minOf(CORNER_DP * d, minOf(w, h) / 2f)

        translate(-origin.x, -origin.y) {
            drawRoundRect(Color.Black, Offset(cx - w / 2f, cy - h / 2f), Size(w, h), CornerRadius(radius))
        }
    }
}
