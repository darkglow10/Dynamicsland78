package com.example.dynamicisland

import android.os.SystemClock
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

// ---------------------------------------------------------------- Equalizer (sağ kenar)
@Composable
fun Equalizer(color: Color) {
    val t = rememberInfiniteTransition(label = "eq")
    val durations = intArrayOf(380, 520, 300, 460)
    val bars = durations.map { d ->
        t.animateFloat(0.25f, 1f, infiniteRepeatable(tween(d, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "bar$d")
    }
    Row(Modifier.height(14.dp), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
        bars.forEach { s ->
            Box(
                Modifier.width(3.dp).fillMaxHeight()
                    .graphicsLayer { scaleY = s.value }          // draw fazı: recomposition yok, GPU
                    .background(color, RoundedCornerShape(2.dp))
            )
        }
    }
}

// ---------------------------------------------------------------- Genişletilmiş müzik paneli
@Composable
fun MusicPanel(m: IslandContent.Media, accent: Color, onCommand: (MediaCommand) -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            AlbumArt(m.art, 52.dp)                                   // sol köşe
            Column(Modifier.weight(1f)) {
                Text(m.title.ifBlank { "Bilinmeyen parça" }, color = Color.White, fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(m.artist, color = Color(0xFFC8C8C8), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }

        MusicProgress(m, accent) { onCommand(MediaCommand.SeekTo(it)) }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            CtlButton(Ctl.Prev, 24.dp) { onCommand(MediaCommand.Previous) }
            CtlButton(if (m.playing) Ctl.Pause else Ctl.Play, 30.dp) { onCommand(MediaCommand.PlayPause) }
            CtlButton(Ctl.Next, 24.dp) { onCommand(MediaCommand.Next) }
        }
    }
}

@Composable
fun AlbumArt(art: ImageBitmap?, size: Dp) {
    val shape = RoundedCornerShape(12.dp)
    if (art != null) {
        Image(bitmap = art, contentDescription = null, contentScale = ContentScale.Crop,
            modifier = Modifier.size(size).clip(shape))
    } else {
        Box(Modifier.size(size).clip(shape).background(Color.White.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
            Text("♪", color = Color.White.copy(alpha = 0.7f), fontSize = 22.sp)
        }
    }
}

private fun fmt(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return "%02d:%02d".format(s / 60, s % 60)
}

@Composable
private fun MusicProgress(m: IslandContent.Media, accent: Color, onSeek: (Long) -> Unit) {
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    var drag by remember { mutableStateOf<Float?>(null) }

    // Sadece panel görünürken VE müzik çalarken yarım saniyede bir tikler; panel kapanınca coroutine biter.
    LaunchedEffect(m.playing, m.updatedAt, m.positionMs) {
        now = SystemClock.elapsedRealtime()
        while (m.playing) { delay(500); now = SystemClock.elapsedRealtime() }
    }

    val dur = m.durationMs
    val d = drag
    val frac = d ?: if (dur > 0) m.positionAt(now).toFloat() / dur else 0f
    val shownMs = if (d != null) (d * dur).toLong() else m.positionAt(now)

    Column {
        SeekBar(frac.coerceIn(0f, 1f), dur > 0, accent) { f, commit ->
            if (commit) { drag = null; onSeek((f * dur).toLong()) } else drag = f
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(fmt(shownMs), color = Color(0xFFC8C8C8), fontSize = 11.sp)
            Text(fmt(dur), color = Color(0xFFC8C8C8), fontSize = 11.sp)
        }
    }
}

@Composable
private fun SeekBar(fraction: Float, enabled: Boolean, accent: Color, onChange: (Float, Boolean) -> Unit) {
    var w by remember { mutableIntStateOf(1) }
    val cb by rememberUpdatedState(onChange)
    Canvas(
        Modifier.fillMaxWidth().height(24.dp)
            .onSizeChanged { w = it.width.coerceAtLeast(1) }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures { cb((it.x / w).coerceIn(0f, 1f), true) }
            }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                var last = 0f
                detectHorizontalDragGestures(
                    onDragStart = { last = (it.x / w).coerceIn(0f, 1f); cb(last, false) },
                    onDragEnd = { cb(last, true) },
                    onDragCancel = { cb(last, true) },
                ) { change, _ ->
                    change.consume()
                    last = (change.position.x / w).coerceIn(0f, 1f); cb(last, false)
                }
            }
    ) {
        val cy = size.height / 2
        val th = 5.dp.toPx()
        drawRoundRect(Color.White.copy(0.25f), Offset(0f, cy - th / 2), Size(size.width, th), CornerRadius(th / 2))
        drawRoundRect(accent, Offset(0f, cy - th / 2), Size(size.width * fraction, th), CornerRadius(th / 2))
    }
}

// ---------------------------------------------------------------- Kontrol ikonları (Canvas; ek ikon kütüphanesi gerekmez)
enum class Ctl { Play, Pause, Next, Prev }

@Composable
private fun CtlButton(kind: Ctl, size: Dp, onClick: () -> Unit) {
    Box(Modifier.size(48.dp).clip(CircleShape).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            val s = this.size.width
            fun tri(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float) {
                val p = Path().apply { moveTo(x1 * s, y1 * s); lineTo(x2 * s, y2 * s); lineTo(x3 * s, y3 * s); close() }
                drawPath(p, Color.White)
            }
            fun bar(x: Float, w: Float) = drawRoundRect(Color.White, Offset(x * s, 0.12f * s), Size(w * s, 0.76f * s), CornerRadius(0.04f * s))
            when (kind) {
                Ctl.Play -> tri(0.22f, 0.10f, 0.22f, 0.90f, 0.90f, 0.50f)
                Ctl.Pause -> { bar(0.20f, 0.22f); bar(0.58f, 0.22f) }
                Ctl.Next -> { tri(0.08f, 0.15f, 0.08f, 0.85f, 0.68f, 0.50f); bar(0.76f, 0.14f) }
                Ctl.Prev -> { tri(0.92f, 0.15f, 0.92f, 0.85f, 0.32f, 0.50f); bar(0.10f, 0.14f) }
            }
        }
    }
}
