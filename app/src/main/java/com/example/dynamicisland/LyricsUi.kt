package com.example.dynamicisland

import android.os.SystemClock
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.abs

// ---------------------------------------------------------------- Senkron: hangi satır, ne kadar söylendi
@Stable
class LyricFrame(val index: State<Int>, val fraction: State<Float>)

fun lineIndexAt(lines: List<LyricLine>, t: Long): Int {
    var lo = 0
    var hi = lines.size - 1
    var ans = -1
    while (lo <= hi) {
        val mid = (lo + hi) ushr 1
        if (lines[mid].timeMs <= t) { ans = mid; lo = mid + 1 } else hi = mid - 1
    }
    return ans
}

/** Satırın ne kadarının söylendiği (0..1): sonraki satıra kadar ya da karakter sayısından tahmin edilen süre. */
fun lineFraction(lines: List<LyricLine>, i: Int, t: Long): Float {
    if (i < 0) return 0f
    val l = lines[i]
    if (l.text.isBlank()) return 0f
    val est = (l.text.length * 95L).coerceAtLeast(1500L)
    val next = lines.getOrNull(i + 1)?.timeMs ?: Long.MAX_VALUE
    val end = minOf(next, l.timeMs + est)
    val dur = (end - l.timeMs).coerceAtLeast(300L)
    return ((t - l.timeMs).toFloat() / dur).coerceIn(0f, 1f)
}

/**
 * Çalan şarkının konumundan (sistem saatiyle) satır + dolum oranını üretir. State olarak döner:
 * dolum yalnızca çizim fazında okunur, kare başına recomposition olmaz.
 */
@Composable
fun rememberLyricFrame(
    media: IslandContent.Media?,
    state: LyricsState,
    offsetMs: Long,
    active: Boolean = true,
    fine: Boolean = true,
    loopMs: Long = 0L,
): LyricFrame {
    val index = remember { mutableIntStateOf(-1) }
    val fraction = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(media, state, offsetMs, active, fine, loopMs) {
        val lines = (state as? LyricsState.Ready)?.lines
        if (media == null || lines.isNullOrEmpty()) {
            index.intValue = -1; fraction.floatValue = 0f
            return@LaunchedEffect
        }
        fun step() {
            var t = media.positionAt(SystemClock.elapsedRealtime()) + offsetMs
            if (loopMs > 0L) t = ((t % loopMs) + loopMs) % loopMs
            val i = lineIndexAt(lines, t)
            val f = lineFraction(lines, i, t)
            if (index.intValue != i) index.intValue = i
            if (abs(fraction.floatValue - f) > 0.002f || f == 0f || f == 1f) fraction.floatValue = f
        }
        step()
        if (active && media.playing) {
            if (fine) {
                var last = 0L
                while (true) {
                    val ms = withFrameMillis { it }
                    if (ms - last >= 30) { last = ms; step() }   // ~30 fps yeter
                }
            } else {
                while (true) { delay(150); step() }
            }
        }
    }
    return remember { LyricFrame(index, fraction) }
}

// ---------------------------------------------------------------- Satır geçiş animasyonu
@Composable
fun LyricTransition(
    index: Int,
    cfg: IslandSettings,
    modifier: Modifier = Modifier,
    contentAlignment: Alignment = Alignment.CenterStart,
    content: @Composable (Int) -> Unit,
) {
    if (!cfg.lyricsAnimEnabled || cfg.lyricsAnimStyle == 5) {
        Box(modifier, contentAlignment = contentAlignment) { content(index) }
        return
    }
    val d = cfg.lyricsAnimMs.coerceIn(80, 1200)
    val style = cfg.lyricsAnimStyle
    AnimatedContent(
        targetState = index,
        modifier = modifier,
        contentAlignment = contentAlignment,
        label = "lyric",
        transitionSpec = {
            val t: ContentTransform = when (style) {
                0 -> fadeIn(tween(d, delayMillis = d / 4)) togetherWith fadeOut(tween(d / 2))
                1 -> (slideInVertically(tween(d)) { it / 2 } + fadeIn(tween(d))) togetherWith
                    (slideOutVertically(tween(d)) { -it / 2 } + fadeOut(tween(d / 2)))
                2 -> (scaleIn(tween(d), initialScale = 0.8f) + fadeIn(tween(d))) togetherWith
                    (scaleOut(tween(d), targetScale = 1.15f) + fadeOut(tween(d / 2)))
                3 -> (slideInVertically(spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMediumLow)) { it } + fadeIn(tween(d / 2))) togetherWith
                    (slideOutVertically(tween(d)) { -it } + fadeOut(tween(d / 2)))
                else -> (slideInHorizontally(tween(d)) { it / 3 } + fadeIn(tween(d))) togetherWith
                    (slideOutHorizontally(tween(d)) { -it / 3 } + fadeOut(tween(d / 2)))
            }
            t.using(SizeTransform(clip = false))
        },
    ) { i -> content(i) }
}

// ---------------------------------------------------------------- Tek satır: karaoke dolumu + parıltı
/**
 * Satırı iki katmanda çizer: soluk taban + söylenen kısım kadar parlak üst katman (satır sarsa da soldan sağa,
 * satır satır ilerler). scroll=true: tek satır; sığmazsa dolumla birlikte yana kayar.
 */
@Composable
fun LyricLineText(
    text: String,
    lineIndex: Int,
    frame: LyricFrame,
    cfg: IslandSettings,
    sizeSp: Float,
    maxLines: Int,
    scroll: Boolean,
    align: TextAlign,
    modifier: Modifier = Modifier,
    outside: Boolean = false,
) {
    if (text.isBlank()) return
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    BoxWithConstraints(
        modifier,
        contentAlignment = if (align == TextAlign.Start) Alignment.CenterStart else Alignment.Center,
    ) {
        val maxPx = with(density) { maxWidth.roundToPx() }
        val style = remember(sizeSp, align) {
            TextStyle(fontSize = sizeSp.sp, lineHeight = (sizeSp * 1.25f).sp, fontWeight = FontWeight.SemiBold, textAlign = align)
        }
        val layout = remember(text, style, maxPx, maxLines, scroll) {
            measurer.measure(
                text = text,
                style = style,
                overflow = if (scroll) TextOverflow.Clip else TextOverflow.Ellipsis,
                softWrap = !scroll,
                maxLines = if (scroll) 1 else maxLines,
                constraints = Constraints(maxWidth = if (scroll) Constraints.Infinity else maxPx),
            )
        }
        val textW = layout.size.width
        val textH = layout.size.height
        val canvasW = if (scroll) maxPx else minOf(textW, maxPx)
        val color = Color(cfg.lyricsColorArgb)
        val dim = color.copy(alpha = cfg.lyricsDimAlpha)
        val glow = if (cfg.lyricsGlowEnabled) cfg.lyricsGlowLevel else 0f
        val shadow: Shadow? = when {
            glow > 0f -> Shadow(color.copy(alpha = 0.85f), Offset.Zero, 6f + glow * 30f)
            outside -> Shadow(Color.Black.copy(alpha = 0.55f), Offset(0f, 2f), 8f)   // glow kapalıyken okunurluk
            else -> null
        }

        Canvas(
            Modifier
                .size(with(density) { canvasW.toDp() }, with(density) { textH.toDp() })
                .then(if (scroll) Modifier.clipToBounds() else Modifier)
        ) {
            val cur = frame.index.value
            val progress = when {
                cur == lineIndex -> frame.fraction.value
                cur > lineIndex -> 1f
                else -> 0f
            }
            val f = if (cfg.lyricsFillEnabled) progress else 1f
            val overflow = (textW - size.width).coerceAtLeast(0f)
            val dx = if (!scroll) 0f else if (overflow > 0f) -overflow * progress else (size.width - textW) / 2f

            translate(left = dx) {
                if (f < 0.999f) drawText(layout, color = dim, shadow = if (outside) shadow?.takeIf { glow <= 0f } else null)
                if (f >= 0.999f) {
                    drawText(layout, color = color, shadow = shadow)
                } else if (f > 0f) {
                    val n = layout.lineCount
                    var total = 0f
                    for (i in 0 until n) total += layout.getLineRight(i) - layout.getLineLeft(i)
                    var remaining = f * total
                    for (i in 0 until n) {
                        if (remaining <= 0f) break
                        val l = layout.getLineLeft(i)
                        val r = layout.getLineRight(i)
                        val w = r - l
                        val take = minOf(remaining, w)
                        clipRect(
                            left = l - 12f,
                            top = layout.getLineTop(i),
                            right = if (take >= w) r + 12f else l + take,
                            bottom = layout.getLineBottom(i),
                        ) { drawText(layout, color = color, shadow = shadow) }
                        remaining -= w
                    }
                }
            }
        }
    }
}

private fun lineText(r: LyricsState.Ready, i: Int): String =
    if (i < 0) "♪" else r.lines.getOrNull(i)?.text.orEmpty()

// ---------------------------------------------------------------- Genişletilmiş panel satırı
@Composable
fun PanelLyrics(cfg: IslandSettings, state: LyricsState, frame: LyricFrame, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(cfg.lyricsBlockDp().dp), contentAlignment = Alignment.CenterStart) {
        when (state) {
            is LyricsState.Ready -> LyricTransition(frame.index.value, cfg, Modifier.fillMaxWidth()) { i ->
                LyricLineText(lineText(state, i), i, frame, cfg, cfg.lyricsPanelSizeSp, cfg.lyricsPanelLines, false, TextAlign.Start, Modifier.fillMaxWidth())
            }
            LyricsState.Loading -> StatusText("Şarkı sözü aranıyor…")
            LyricsState.NotFound -> StatusText("Şarkı sözü bulunamadı")
            LyricsState.Idle -> Unit
        }
    }
}

@Composable
private fun StatusText(s: String) = Text(s, color = Color.White.copy(alpha = 0.5f), fontSize = 14.sp, fontWeight = FontWeight.Medium)

// ---------------------------------------------------------------- Kapalı adanın ortası (tek satır, yana kayar)
@Composable
fun CompactLyrics(cfg: IslandSettings, state: LyricsState, frame: LyricFrame, modifier: Modifier = Modifier) {
    val ready = state as? LyricsState.Ready ?: return
    Box(
        modifier
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                drawRect(
                    Brush.horizontalGradient(0f to Color.Transparent, 0.1f to Color.Black, 0.9f to Color.Black, 1f to Color.Transparent),
                    blendMode = BlendMode.DstIn,
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        LyricTransition(frame.index.value, cfg, Modifier.fillMaxWidth(), Alignment.Center) { i ->
            LyricLineText(lineText(ready, i), i, frame, cfg, cfg.lyricsCompactSizeSp, 1, true, TextAlign.Center, Modifier.fillMaxWidth())
        }
    }
}

// ---------------------------------------------------------------- Adanın dışındaki sözler
@Composable
fun OutsideLyricText(cfg: IslandSettings, state: LyricsState, frame: LyricFrame, modifier: Modifier = Modifier) {
    val ready = state as? LyricsState.Ready ?: return
    Box(modifier, contentAlignment = Alignment.Center) {
        LyricTransition(frame.index.value, cfg, Modifier.fillMaxWidth(), Alignment.Center) { i ->
            LyricLineText(
                lineText(ready, i), i, frame, cfg, cfg.lyricsOutsideSizeSp, cfg.lyricsOutsideLines,
                false, TextAlign.Center, Modifier.fillMaxWidth(), outside = true,
            )
        }
    }
}

/** Ayrı, dokunmatik olmayan overlay penceresinin içeriği. Ada genişleyip sözleri kendi içinde gösterirken gizlenir. */
@Composable
fun OutsideLyricsRoot(
    ui: StateFlow<IslandUiState>,
    settingsFlow: StateFlow<IslandSettings>,
    lyricsFlow: StateFlow<LyricsState>,
    mediaFlow: StateFlow<IslandContent.Media?>,
) {
    val cfg by settingsFlow.collectAsState()
    val lyrics by lyricsFlow.collectAsState()
    val media by mediaFlow.collectAsState()
    val u by ui.collectAsState()
    val hiddenByPanel = u.expanded && cfg.lyricsInPanel && u.content is IslandContent.Media
    val show = cfg.lyricsEnabled && cfg.lyricsOutside && media?.playing == true &&
        lyrics is LyricsState.Ready && !hiddenByPanel
    val frame = rememberLyricFrame(media, lyrics, cfg.lyricsOffsetMs.toLong(), active = show, fine = cfg.lyricsFillEnabled)
    val shownAlpha by animateFloatAsState(if (show) 1f else 0f, tween(250), label = "outA")
    if (shownAlpha > 0.01f) {
        Box(
            Modifier.fillMaxSize().padding(24.dp).graphicsLayer { this.alpha = shownAlpha },
            contentAlignment = Alignment.Center,
        ) { OutsideLyricText(cfg, lyrics, frame, Modifier.fillMaxWidth()) }
    }
}
