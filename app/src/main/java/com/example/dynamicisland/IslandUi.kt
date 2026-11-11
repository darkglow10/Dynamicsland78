package com.example.dynamicisland

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.max
import kotlin.math.min

const val MUSIC_EXTRA_DP = 100f

@Immutable
data class Geo(val cw: Dp, val ch: Dp, val ew: Dp, val eh: Dp)

fun compactWidthFor(cfg: IslandSettings, compactLyrics: Boolean): Float =
    if (compactLyrics) maxOf(cfg.compactWidthDp, cfg.lyricsCompactWidthDp) else cfg.compactWidthDp

fun islandGeo(cfg: IslandSettings, music: Boolean, compactLyrics: Boolean = false) = Geo(
    cw = compactWidthFor(cfg, compactLyrics && music).dp,
    ch = cfg.compactHeightDp.dp,
    ew = cfg.expandedWidthDp.dp,
    eh = (cfg.expandedHeightDp + if (music) cfg.musicExtraDp() else 0f).dp,
)

// ---------------------------------------------------------------- Animasyon (5 stil + kapalı)
/** Genişlik ve yükseklik ilerlemesi ayrı: "Sıvı" stilinde farklı zamanlanır, diğerlerinde aynıdır. */
@Stable
class IslandProgress(val w: State<Float>, val h: State<Float>) {
    val mean: Float get() = (w.value + h.value) / 2f
}

private fun specFor(cfg: IslandSettings, expanding: Boolean, isWidth: Boolean): FiniteAnimationSpec<Float> {
    if (!cfg.animEnabled) return snap()
    return when (cfg.animStyle) {
        0 -> spring(dampingRatio = 0.9f, stiffness = Spring.StiffnessMediumLow)           // Yaylı
        1 -> tween(520, easing = FastOutSlowInEasing)                                      // Yumuşak
        2 -> tween(220, easing = LinearOutSlowInEasing)                                    // Hızlı
        3 -> spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessMediumLow)           // Zıplamalı
        else -> {                                                                          // Sıvı
            val first = if (expanding) isWidth else !isWidth       // açılırken önce genişlik, kapanırken önce yükseklik
            tween(durationMillis = 340, delayMillis = if (first) 0 else 140, easing = FastOutSlowInEasing)
        }
    }
}

@Composable
fun rememberIslandProgress(cfg: IslandSettings, expanded: Boolean): IslandProgress {
    val target = if (expanded) 1f else 0f
    val pw = animateFloatAsState(target, specFor(cfg, expanded, true), label = "pw")
    val ph = animateFloatAsState(target, specFor(cfg, expanded, false), label = "ph")
    return remember(pw, ph) { IslandProgress(pw, ph) }
}

@Composable
fun IslandRoot(
    state: StateFlow<IslandUiState>,
    settingsFlow: StateFlow<IslandSettings>,
    lyricsFlow: StateFlow<LyricsState>,
    onTap: () -> Unit,
    onCommand: (MediaCommand) -> Unit,
    onOutput: () -> Unit,
) {
    val s by state.collectAsState()
    val cfg by settingsFlow.collectAsState()
    val lyrics by lyricsFlow.collectAsState()
    val content = s.content
    val media = content as? IslandContent.Media
    val progress = rememberIslandProgress(cfg, s.expanded)
    val lyricsOn = cfg.lyricsEnabled && (cfg.lyricsInPanel || cfg.lyricsCompact)
    val frame = rememberLyricFrame(
        media, lyrics, cfg.lyricsOffsetMs.toLong(),
        active = lyricsOn && lyrics is LyricsState.Ready, fine = cfg.lyricsFillEnabled,
    )

    // Pencere, ada + parıltı payı kadar sabit boyutlu; ada üst-ortada büyür/küçülür
    Box(Modifier.fillMaxSize().padding(cfg.windowPadDp().dp), contentAlignment = Alignment.TopCenter) {
        // Boştayken de göster açıksa Idle içerikte de (boş) ada çizilir; kapalıysa hiçbir şey çizilmez.
        if (content !is IslandContent.Idle || cfg.showIdle) {
            val music = content is IslandContent.Media
            // Sözler görününce kapalı ada yumuşakça genişler / sözler bitince daralır
            val cwSpec: AnimationSpec<Dp> = if (cfg.animEnabled) tween<Dp>(380, easing = FastOutSlowInEasing) else snap<Dp>()
            val cw by animateDpAsState(compactWidthFor(cfg, s.compactLyrics && music).dp, cwSpec, label = "cw")
            val geo = islandGeo(cfg, music, s.compactLyrics).copy(cw = cw)
            IslandPill(content, progress, geo, cfg, lyrics, frame, s.compactLyrics, onTap, onCommand, onOutput)
        }
    }
}

// ---------------------------------------------------------------- Renk / yüzey / parıltı
private val PillShape = RoundedCornerShape(36.dp)
private val SheenBrush = Brush.verticalGradient(
    listOf(Color.White.copy(0.22f), Color.White.copy(0.05f), Color.Transparent, Color.White.copy(0.08f))
)

private fun darkTone(c: Color): Color {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(c.toArgb(), hsv)
    hsv[1] = hsv[1].coerceIn(0.5f, 1f)
    hsv[2] = 0.26f
    return Color(android.graphics.Color.HSVToColor(hsv))
}

private fun bodyColorFor(cfg: IslandSettings, art: Color?, accent: Color): Color {
    val tint: Color? = if (cfg.dynamicColor) art else null
    return when {
        cfg.glassEnabled -> (tint ?: accent).copy(alpha = cfg.glassAlpha)
        tint != null -> darkTone(tint)
        else -> Color.Black
    }
}

/** Renk State olarak taşınır, yalnızca çizim fazında okunur => animasyonda recomposition yok. */
private fun Modifier.islandSurface(cfg: IslandSettings, body: State<Color>): Modifier {
    var m = this.clip(PillShape).drawBehind { drawRect(body.value) }
    if (cfg.glassEnabled) m = m.background(SheenBrush)
    if (cfg.borderEnabled) m = m.border(cfg.borderWidthDp.dp, Color(cfg.borderColorArgb), PillShape)
    return m
}

/** Blur filtresi yerine kademeli (azalan opaklıkta) halkalar: GPU dostu, her cihazda çalışır. */
private fun Modifier.islandGlow(enabled: Boolean, color: State<Color>, intensity: Float): Modifier =
    if (!enabled) this else this.drawBehind {
        val rings = 6
        val step = GLOW_PAD_DP.dp.toPx() / rings
        val base = min(36.dp.toPx(), size.height / 2f)
        val c = color.value
        for (i in 1..rings) {
            val inflate = (i - 0.5f) * step
            val fall = 1f - (i - 1f) / rings
            val a = (intensity * 0.6f * fall * fall).coerceIn(0f, 1f)
            drawRoundRect(
                color = c.copy(alpha = a),
                topLeft = Offset(-inflate, -inflate),
                size = Size(size.width + 2 * inflate, size.height + 2 * inflate),
                cornerRadius = CornerRadius(base + inflate),
                style = Stroke(width = step),
            )
        }
    }

@Composable
fun IslandPill(
    content: IslandContent,
    progress: IslandProgress,
    geo: Geo,
    cfg: IslandSettings,
    lyrics: LyricsState,
    frame: LyricFrame,
    compactLyrics: Boolean,
    onTap: () -> Unit,
    onCommand: (MediaCommand) -> Unit,
    onOutput: () -> Unit,
) {
    val accent = remember(cfg.accentArgb) { Color(cfg.accentArgb) }
    val media = content as? IslandContent.Media
    val artColor = if (media != null && media.artColor != 0) Color(media.artColor) else null
    val highlight = if (cfg.dynamicColor && artColor != null) artColor else accent

    val body = animateColorAsState(bodyColorFor(cfg, artColor, accent), tween(450), label = "body")
    val glow = animateColorAsState(artColor ?: accent, tween(450), label = "glow")

    val panelVisible by remember(progress) { derivedStateOf { max(progress.w.value, progress.h.value) > 0.01f } }
    val compactVisible by remember(progress) { derivedStateOf { progress.mean < 0.5f } }

    Box(
        modifier = Modifier
            .layout { measurable, _ ->
                val w = lerp(geo.cw, geo.ew, progress.w.value).roundToPx().coerceAtLeast(1)
                val h = lerp(geo.ch, geo.eh, progress.h.value).roundToPx().coerceAtLeast(1)
                val placeable = measurable.measure(Constraints.fixed(w, h))
                layout(w, h) { placeable.place(0, 0) }
            }
            .islandGlow(cfg.glowEnabled, glow, cfg.glowIntensity)
            .islandSurface(cfg, body)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onTap),
        contentAlignment = Alignment.TopCenter,
    ) {
        if (panelVisible) {
            Box(
                Modifier
                    .requiredSize(geo.ew, geo.eh)
                    .graphicsLayer { alpha = ((progress.mean - 0.45f) / 0.55f).coerceIn(0f, 1f) }
            ) {
                when (content) {
                    is IslandContent.Notif -> NotifContent(content, accent)
                    is IslandContent.Media -> MusicPanel(content, highlight, cfg, lyrics, frame, onCommand, onOutput)
                    IslandContent.Idle -> Unit
                }
            }
        }

        if (content is IslandContent.Media && compactVisible) {
            val fade = Modifier.graphicsLayer { alpha = (1f - progress.mean * 2f).coerceIn(0f, 1f) }
            val artSize = (geo.ch - 12.dp).coerceAtLeast(14.dp)
            content.art?.let { art ->
                Image(
                    bitmap = art, contentDescription = null, contentScale = ContentScale.Crop,
                    modifier = Modifier.align(Alignment.CenterStart).padding(start = 8.dp)
                        .size(artSize).clip(RoundedCornerShape(6.dp)).then(fade),
                )
            }
            if (compactLyrics && cfg.lyricsEnabled && cfg.lyricsCompact && lyrics is LyricsState.Ready) {
                // Albüm kapağı ile equalizer arasındaki orta boşluk
                val startPad = if (content.art != null) 8.dp + artSize + 8.dp else 14.dp
                val endPad = 10.dp + 23.dp + 8.dp
                CompactLyrics(
                    cfg, lyrics, frame,
                    Modifier.align(Alignment.TopCenter).padding(start = startPad, end = endPad)
                        .fillMaxWidth().height(geo.ch).then(fade),
                    artColor = content.artColor,
                )
            }
            Box(Modifier.align(Alignment.CenterEnd).padding(end = 10.dp).then(fade)) {
                if (content.playing) Equalizer(highlight)
                else Box(Modifier.size(6.dp).background(Color.Gray, CircleShape))
            }
        }
    }
}

@Composable
private fun NotifContent(c: IslandContent.Notif, accent: Color) {
    Row(
        Modifier.fillMaxSize().padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val icon = c.icon
        if (icon != null) {
            Image(bitmap = icon, contentDescription = null, modifier = Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)))
        } else {
            Box(Modifier.size(40.dp).background(accent, RoundedCornerShape(10.dp)))
        }
        Column(Modifier.weight(1f)) {
            Text(c.title, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 15.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(c.text, color = Color(0xFFD6D6D6), fontSize = 13.sp,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}
