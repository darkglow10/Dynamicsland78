package com.example.dynamicisland

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.lerp as lerpRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.util.lerp
import kotlinx.coroutines.delay

/** Adanın (kapalı halde) ekran üzerindeki konumu; hepsi piksel. */
class LaunchSpec(val cx: Float, val top: Float, val w: Float, val h: Float)

// ---- Ayar noktaları (hissi buradan ince ayar yapabilirsin)
private const val EMERGE_MS = 240          // adadan küçük siyah şeklin çıkışı
private const val SNAP_MS = 110            // üstten geriye ani kırılma
private const val FILL_MS = 420            // ekranı kaplama
private const val HOLD_MS = 420L           // uygulama açıldıktan sonra siyahın bekleme süresi
private const val FADE_MS = 240            // siyahın kalkması
private const val BLOB_EXTRA_W_DP = 48f    // çıkan şeklin adaya göre ek genişliği
private const val BLOB_EXTRA_H_DP = 84f    // çıkan şeklin adaya göre ek yüksekliği
private const val SNAP_EXTRA_W_DP = 14f    // kırılma sonrası ek genişlik
private const val SNAP_EXTRA_H_DP = 30f    // kırılma sonrası ek yükseklik

private val FillEasing = CubicBezierEasing(0.55f, 0f, 0.10f, 1f)

/**
 * Adanın içinden çıkan siyah ekran: küçük çıkar, üstten geriye kırılır, sonra tüm ekranı kaplar.
 * Ekran tamamen kapanınca [onCovered] çağrılır (uygulamayı o an aç); siyah bir süre sonra solar ve [onFinished] gelir.
 */
@Composable
fun LaunchTransition(spec: LaunchSpec, onCovered: () -> Unit, onFinished: () -> Unit) {
    val covered by rememberUpdatedState(onCovered)
    val finished by rememberUpdatedState(onFinished)
    val a = remember { Animatable(0f) }
    val b = remember { Animatable(0f) }
    val c = remember { Animatable(0f) }
    val fade = remember { Animatable(1f) }
    var origin by remember { mutableStateOf(Offset.Zero) }

    LaunchedEffect(Unit) {
        a.animateTo(1f, tween(EMERGE_MS, easing = FastOutSlowInEasing))
        b.animateTo(1f, tween(SNAP_MS, easing = LinearOutSlowInEasing))
        c.animateTo(1f, tween(FILL_MS, easing = FillEasing))
        covered()
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
        val sw = size.width
        val sh = size.height
        val m = 24f                                    // taşma payı: kenarda boşluk kalmasın

        fun rect(w: Float, h: Float) = Rect(spec.cx - w / 2f, spec.top, spec.cx + w / 2f, spec.top + h)
        val r0 = rect(spec.w, spec.h)
        val r1 = rect(spec.w + BLOB_EXTRA_W_DP * d, spec.h + BLOB_EXTRA_H_DP * d)
        val r2 = rect(spec.w + SNAP_EXTRA_W_DP * d, spec.h + SNAP_EXTRA_H_DP * d)
        val full = Rect(-m, -m, sw + m, sh + m)

        val pa = a.value; val pb = b.value; val pc = c.value
        val r = when {
            pc > 0f -> lerpRect(r2, full, pc)
            pb > 0f -> lerpRect(r1, r2, pb)
            else -> lerpRect(r0, r1, pa)
        }
        val radius = when {
            pc > 0f -> lerp(minOf(36f * d, r2.height / 2f), 40f * d, pc) * (1f - pc * pc)
            else -> minOf(36f * d, r.height / 2f)
        }

        translate(-origin.x, -origin.y) {
            if (pc >= 1f) {
                drawRect(Color.Black, Offset(-m, -m), Size(sw + 2 * m, sh + 2 * m))
            } else {
                drawRoundRect(Color.Black, Offset(r.left, r.top), Size(r.width, r.height), CornerRadius(radius))
            }
        }
    }
}
