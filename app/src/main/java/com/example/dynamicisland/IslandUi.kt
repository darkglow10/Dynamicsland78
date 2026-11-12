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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
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
private val GlassMaxRadius = 36.dp

private fun darkTone(c: Color): Color {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(c.toArgb(), hsv)
    // Eskiden çok koyuydu (0.26): artık kapak rengi daha canlı görünür, beyaz yazı yine rahat okunur.
    // Gri/siyah kapaklar kırmızıya boyanmasın diye gri kalır.
    val gray = hsv[1] < 0.12f
    hsv[1] = if (gray) hsv[1] else hsv[1].coerceIn(0.55f, 0.95f)
    hsv[2] = if (gray) 0.30f else 0.50f
    return Color(android.graphics.Color.HSVToColor(hsv))
}

/** Ekolayzır için: albüm rengi aynı tonda ama en parlak ve biraz pastel; koyu ada gövdesinden net ayrılır. */
private fun brightTone(c: Color): Color {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(c.toArgb(), hsv)
    hsv[1] = (hsv[1] * 0.85f).coerceIn(0f, 0.8f)
    hsv[2] = 1f
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

// ---------------------------------------------------------------- Liquid Glass katmanları
/**
 * Liquid Glass — içeriğin ARKASINA çizilen katmanlar:
 *  1) üstten alta ışık geçişi (camın kalınlığı), alt kenarda hafif ışık toplanması
 *  2) sol üstte yumuşak speküler parlama
 *  3) kenarlara doğru incelen iç ışık halkaları (lens / kırılma hissi)
 */
private fun DrawScope.drawGlassBase() {
    val w = size.width; val h = size.height
    if (w <= 1f || h <= 1f) return
    val r = min(GlassMaxRadius.toPx(), h / 2f)

    drawRect(
        Brush.verticalGradient(
            0f to Color.White.copy(0.17f),
            0.40f to Color.White.copy(0.04f),
            0.72f to Color.Transparent,
            1f to Color.White.copy(0.09f),
        )
    )
    drawRect(
        Brush.radialGradient(
            0f to Color.White.copy(0.22f),
            0.6f to Color.White.copy(0.05f),
            1f to Color.Transparent,
            center = Offset(w * 0.20f, h * 0.02f),
            radius = max(w * 0.60f, h),
        )
    )
    // Alt-sağ köşede çok hafif ikincil parlama (ışığın cam içinden geçip toplandığı yer)
    drawRect(
        Brush.radialGradient(
            0f to Color.White.copy(0.08f),
            1f to Color.Transparent,
            center = Offset(w * 0.88f, h * 1.0f),
            radius = max(w * 0.35f, h * 0.9f),
        )
    )

    val rings = 6
    val step = 1.4.dp.toPx()
    for (i in 0 until rings) {
        val inset = i * step + step / 2f
        val a = 0.12f * (1f - i / rings.toFloat())
        drawRoundRect(
            color = Color.White.copy(alpha = a),
            topLeft = Offset(inset, inset),
            size = Size((w - 2 * inset).coerceAtLeast(0f), (h - 2 * inset).coerceAtLeast(0f)),
            cornerRadius = CornerRadius((r - inset).coerceAtLeast(0f)),
            style = Stroke(width = step),
        )
    }
}

/**
 * Liquid Glass — içeriğin ÜSTÜNE çizilen ince kenar ışığı (specular rim):
 * sol-üstte parlak, ortada sönük, sağ-altta tekrar parlak; iOS'taki cam kenarı gibi.
 */
private fun DrawScope.drawGlassRim() {
    val w = size.width; val h = size.height
    if (w <= 1f || h <= 1f) return
    val r = min(GlassMaxRadius.toPx(), h / 2f)

    val sw = 1.3.dp.toPx()
    val rim = Brush.linearGradient(
        0f to Color.White.copy(0.90f),
        0.28f to Color.White.copy(0.20f),
        0.62f to Color.White.copy(0.07f),
        1f to Color.White.copy(0.55f),
        start = Offset.Zero, end = Offset(w, h),
    )
    drawRoundRect(
        brush = rim,
        topLeft = Offset(sw / 2f, sw / 2f),
        size = Size(w - sw, h - sw),
        cornerRadius = CornerRadius((r - sw / 2f).coerceAtLeast(0f)),
        style = Stroke(width = sw),
    )

    // İç ikinci halka: kenarın "kalınlığını" verir
    val sw2 = 0.7.dp.toPx()
    val off = sw + sw2 / 2f
    drawRoundRect(
        color = Color.White.copy(alpha = 0.10f),
        topLeft = Offset(off, off),
        size = Size((w - 2 * off).coerceAtLeast(0f), (h - 2 * off).coerceAtLeast(0f)),
        cornerRadius = CornerRadius((r - off).coerceAtLeast(0f)),
        style = Stroke(width = sw2),
    )

    // Üst kenarda ortası parlak, uçları kaybolan speküler çizgi
    if (w > 2.2f * r) {
        val y = 1.8.dp.toPx()
        val x0 = r * 0.9f; val x1 = w - r * 0.9f
        drawLine(
            brush = Brush.horizontalGradient(
                0f to Color.Transparent,
                0.35f to Color.White.copy(0.55f),
                0.65f to Color.White.copy(0.30f),
                1f to Color.Transparent,
                startX = x0, endX = x1,
            ),
            start = Offset(x0, y), end = Offset(x1, y),
            strokeWidth = 1.1.dp.toPx(), cap = StrokeCap.Round,
        )
    }
}

/** Renk State olarak taşınır, yalnızca çizim fazında okunur => animasyonda recomposition yok. */
private fun Modifier.islandSurface(cfg: IslandSettings, body: State<Color>): Modifier {
    var m = this.clip(PillShape).drawBehind { drawRect(body.value) }
    if (cfg.glassEnabled) m = m.drawBehind { drawGlassBase() }
    if (cfg.borderEnabled) m = m.border(cfg.borderWidthDp.dp, Color(cfg.borderColorArgb), PillShape)
    // Kenar ışığı, içeriğin (yazı/kapak) üstüne çizilir; kullanıcı çerçevesi yine de en üstte kalır
    if (cfg.glassEnabled) m = m.drawWithContent { drawContent(); drawGlassRim() }
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
    val eqColor = remember(highlight) { brightTone(highlight) }

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
        // Albüm kapağı arka planı: ada içini kaplar, yazıların okunması için hafif karartılır
        val backdropArt = media?.art
        if (cfg.artBgEnabled && backdropArt != null) {
            ArtBackdrop(backdropArt, cfg.artBgCompact, progress)
        }

        if (panelVisible) {
            Box(
                Modifier
                    .requiredSize(geo.ew, geo.eh)
                    .graphicsLayer { alpha = ((progress.mean - 0.45f) / 0.55f).coerceIn(0f, 1f) }
            ) {
                when (content) {
                    is IslandContent.Notif -> NotifContent(content, accent)
                    is IslandContent.Media -> MusicPanel(content, eqColor, cfg, lyrics, frame, onCommand, onOutput)
                    IslandContent.Idle -> Unit
                }
            }
        }

        if (content is IslandContent.Media && compactVisible) {
            // Sabit (kapalı ada) genişliğinde kutu: ada küçülürken kapak ve equalizer kenarlarla birlikte kaymaz
            Box(Modifier.requiredSize(geo.cw, geo.ch).align(Alignment.TopCenter)) {
                val fade = Modifier.graphicsLayer { alpha = (1f - progress.mean * 2f).coerceIn(0f, 1f) }
                val artSize = (geo.ch - 12.dp).coerceAtLeast(14.dp)
                content.art?.let { art ->
                    Image(
                        bitmap = art, contentDescription = null, contentScale = ContentScale.Crop,
                        filterQuality = FilterQuality.High,
                        modifier = Modifier.align(Alignment.CenterStart).padding(start = 8.dp)
                            .size(artSize).clip(RoundedCornerShape(7.dp)).then(fade),
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
                    if (content.playing) Equalizer(eqColor)
                    else Box(Modifier.size(6.dp).background(Color.White.copy(alpha = 0.40f), CircleShape))
                }

            }
        }
    }
}

/** Kapağı ada içine net (bulanıklaştırılmamış) çizer; yazılar okunsun diye üstüne koyu katman biner. */
@Composable
private fun ArtBackdrop(art: ImageBitmap, showInCompact: Boolean, progress: IslandProgress) {
    Box(
        Modifier.fillMaxSize().graphicsLayer {
            // Kapalı adada kapalıysa: ada açılırken belirir, küçülürken solar
            alpha = if (showInCompact) 1f else ((progress.mean - 0.25f) / 0.6f).coerceIn(0f, 1f)
        }
    ) {
        Image(
            bitmap = art, contentDescription = null, contentScale = ContentScale.Crop,
            filterQuality = FilterQuality.High, modifier = Modifier.fillMaxSize(),
        )
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.50f)))
    }
}

@Composable
private fun NotifContent(c: IslandContent.Notif, accent: Color) {
    val iconShape = RoundedCornerShape(13.dp)
    Row(
        Modifier.fillMaxSize().padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        val icon = c.icon
        if (icon != null) {
            Image(
                bitmap = icon, contentDescription = null,
                modifier = Modifier.size(46.dp).clip(iconShape)
                    .border(1.dp, Color.White.copy(alpha = 0.10f), iconShape),
            )
        } else {
            Box(Modifier.size(46.dp).background(accent, iconShape), contentAlignment = Alignment.Center) {
                Text(
                    c.title.trim().take(1).uppercase().ifEmpty { "•" },
                    color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp,
                )
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            if (c.title.isNotBlank()) {
                Text(c.title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.5.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (c.text.isNotBlank()) {
                Text(c.text, color = Color.White.copy(alpha = 0.70f), fontSize = 13.5.sp, lineHeight = 18.sp,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
