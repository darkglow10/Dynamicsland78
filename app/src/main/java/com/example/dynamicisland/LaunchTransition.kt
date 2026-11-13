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
import androidx.compose.runtime.snapshotFlow
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Adanın (kapalı halde) ekran üzerindeki konumu; hepsi piksel. */
class LaunchSpec(val cx: Float, val top: Float, val w: Float, val h: Float)

// ---- Ayar noktaları
private const val POP_MS = 520             // adadan çıkıp ekranı kaplama süresi
private const val HOLD_MS = 300L           // ekran kaplandıktan sonra siyahın bekleme süresi (uygulama açılırken)
private const val FADE_MS = 240            // siyahın kalkması
private const val START_SCALE = 0.12f      // adanın içinden çıkarkenki başlangıç boyutu (şarkı sözü animasyonuyla aynı)

/** Şarkı sözlerindeki "Adadan tek tek" animasyonunun aynı eğrisi: hızlı çıkış + hafif taşıp yerine oturma. */
private val PopEasing = CubicBezierEasing(0.2f, 1.25f, 0.4f, 1f)

/**
 * "Adadan tek tek" sözleriyle aynı mantık: ekran boyutundaki siyah katman adanın merkezinde küçük (%12) başlar,
 * pop eğrisiyle büyüyerek ve ekranın merkezine kayarak tüm ekranı kaplar.
 * Ekran tamamen kapanınca [onCovered] çağrılır (uygulamayı o an aç); siyah bir süre sonra solar ve [onFinished] gelir.
 */
@Composable
fun LaunchTransition(spec: LaunchSpec, onCovered: () -> Unit, onFinished: () -> Unit) {
    val covered by rememberUpdatedState(onCovered)
    val finished by rememberUpdatedState(onFinished)
    val a = remember { Animatable(0f) }
    val fade = remember { Animatable(1f) }
    var origin by remember { mutableStateOf(Offset.Zero) }

    LaunchedEffect(Unit) {
        val pop = launch { a.animateTo(1f, tween(POP_MS, easing = PopEasing)) }
        snapshotFlow { a.value }.first { it >= 1f }     // ekran tamamen siyah oldu
        covered()
        pop.join()
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
        val sw = size.width
        val sh = size.height
        val p = a.value

        val sc = START_SCALE + (1f - START_SCALE) * p
        val w = (sw + 2 * m) * sc
        val h = (sh + 2 * m) * sc
        val ix = spec.cx
        val iy = spec.top + spec.h / 2f
        val cx = ix + (sw / 2f - ix) * p               // adanın merkezinden ekranın merkezine
        val cy = iy + (sh / 2f - iy) * p
        val pc = p.coerceIn(0f, 1f)
        val alpha = (p * 3f).coerceIn(0f, 1f)
        val radius = minOf(36f * d * (1f - pc * pc), minOf(w, h) / 2f)

        translate(-origin.x, -origin.y) {
            if (p >= 1f) {
                drawRect(Color.Black, Offset(-m, -m), Size(sw + 2 * m, sh + 2 * m))
            } else {
                drawRoundRect(Color.Black.copy(alpha = alpha), Offset(cx - w / 2f, cy - h / 2f), Size(w, h), CornerRadius(radius))
            }
        }
    }
}
