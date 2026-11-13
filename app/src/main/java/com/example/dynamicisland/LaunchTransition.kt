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
        val m = 24f                                    // taşma payı: kenarda boşluk kalmasın
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
        val radius = minOf(26f * d * (1f - t), minOf(w, h) / 2f)

        translate(-origin.x, -origin.y) {
            if (t >= 1f) {
                drawRect(Color.Black, Offset(-m, -m), Size(sw, sh))
            } else {
                drawRoundRect(Color.Black.copy(alpha = alpha), Offset(cx - w / 2f, cy - h / 2f), Size(w, h), CornerRadius(radius))
            }
        }
    }
}
