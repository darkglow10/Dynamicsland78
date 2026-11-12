package com.example.dynamicisland

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlin.math.roundToInt

private val ANIM_STYLES = listOf(
    "Yaylı" to "Dengeli, hafif yaylanan geçiş",
    "Yumuşak" to "Yavaş ve akıcı kayma",
    "Hızlı" to "Kısa ve seri geçiş",
    "Zıplamalı" to "Açılırken hafifçe zıplar",
    "Sıvı" to "Önce genişlik, sonra yükseklik",
)

private val LYRIC_ANIMS = listOf(
    "Solma" to "Eski satır silinir, yenisi belirir",
    "Yukarı kayma" to "Satırlar yukarı doğru kayar",
    "Yakınlaşma" to "Satır büyüyerek gelir",
    "Yaylı zıplama" to "Yeni satır alttan yaylanarak gelir",
    "Yatay kayma" to "Satırlar yandan kayar",
    "Anında" to "Geçiş animasyonu yok",
    "Adadan çıkış" to "Satırın tamamı adanın içinden küçük çıkar, büyüyerek yerine gelir; eski satır adaya geri girer",
    "Adadan tek tek" to "Kelimeler şarkıda sırası gelince tek tek adadan küçük çıkıp büyüyerek yerine gelir",
    "Soldan açılma" to "Satır soldan sağa açılarak belirir",
    "Çevirme (3D)" to "Eski satır yatay eksende döner, yenisi dönerek gelir",
    "Odak çekme" to "Yeni satır büyük başlayıp netleşerek yerine oturur",
)

@Composable
fun SettingsScreen(lyricsPage: Boolean = false) {
    val ctx = LocalContext.current
    val repo = remember { SettingsRepository(ctx.applicationContext) }
    var initial by remember { mutableStateOf<IslandSettings?>(null) }
    LaunchedEffect(Unit) { initial = repo.flow.first() }
    val init = initial ?: return
    SettingsContent(init, lyricsPage) { repo.save(it) }
}

@Composable
private fun SettingsContent(init: IslandSettings, lyricsPage: Boolean, onSave: suspend (IslandSettings) -> Unit) {
    var draft by remember { mutableStateOf(init) }
    var version by remember { mutableIntStateOf(0) }
    var mode by remember { mutableIntStateOf(1) }            // 0 bildirim, 1 müzik
    var expanded by remember { mutableStateOf(lyricsPage) }

    // Slider sürüklenirken değil, 250 ms sakinleşince kaydet
    LaunchedEffect(draft) { delay(250); onSave(draft) }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        // ---- Sabit canlı önizleme (kaydırırken de görünür kalır)
        PreviewCard(draft, mode, { mode = it }, expanded) { expanded = !expanded }

        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            if (lyricsPage) {
                LyricsSections(draft, version) { draft = it }
                IslandActionButton("Söz ayarlarını varsayılana döndür", { draft = draft.withLyricsDefaults(); version++ })
                Spacer(Modifier.height(28.dp))
                return@Column
            }

            // ---- Yerleşim
            GroupLabel("Yerleşim")
            SectionCard("Kapalı ada", "Kamera çevresindeki küçük hal") {
                SliderRow("Genişlik", draft.compactWidthDp, 60f..220f, "${draft.compactWidthDp.toInt()} dp") { draft = draft.copy(compactWidthDp = it) }
                SliderRow("Yükseklik", draft.compactHeightDp, 20f..60f, "${draft.compactHeightDp.toInt()} dp") { draft = draft.copy(compactHeightDp = it) }
            }
            SectionCard("Açık ada", "Dokununca açılan genişletilmiş panel") {
                SliderRow("Genişlik", draft.expandedWidthDp, 260f..400f, "${draft.expandedWidthDp.toInt()} dp") { draft = draft.copy(expandedWidthDp = it) }
                SliderRow("Yükseklik", draft.expandedHeightDp, 72f..120f, "${draft.expandedHeightDp.toInt()} dp") { draft = draft.copy(expandedHeightDp = it) }
            }
            SectionCard("Konum", "Kamera deliğine göre ince ayar") {
                SliderRow("Yatay", draft.offsetXDp, -60f..60f, "${draft.offsetXDp.toInt()} dp") { draft = draft.copy(offsetXDp = it) }
                SliderRow("Dikey", draft.offsetYDp, -60f..40f, "${draft.offsetYDp.toInt()} dp") { draft = draft.copy(offsetYDp = it) }
            }
            SectionCard("Görünürlük", "Ada ne zaman görünsün") {
                SwitchRow("Boştayken de göster", "Müzik ve bildirim yokken ada kamera çevresinde durur", draft.showIdle) { draft = draft.copy(showIdle = it) }
            }

            // ---- Görünüm
            GroupLabel("Görünüm")
            SectionCard("Renk", "Adanın genel renk davranışı") {
                SwitchRow("Albüm kapağı rengi", "Ada rengi, çalan şarkının kapağına göre değişir",
                    draft.dynamicColor) { draft = draft.copy(dynamicColor = it) }
                SectionDivider()
                SubHeader("Vurgu / cam rengi")
                key(version) { ColorPicker(draft.accentArgb) { draft = draft.copy(accentArgb = it) } }
            }

            SectionCard("Albüm arka planı", "Müzik çalarken adanın içi kapak görseliyle dolar") {
                SwitchRow("Albüm kapağı arka planı", "Kapak bulanık ve hafif karartılmış olarak adanın içine yayılır",
                    draft.artBgEnabled) { draft = draft.copy(artBgEnabled = it) }
                if (draft.artBgEnabled) {
                    SwitchRow("Kapalı adada da göster", "Kapatırsan arka plan yalnızca açılmış adada görünür",
                        draft.artBgCompact) { draft = draft.copy(artBgCompact = it) }
                }
            }

            SectionCard("Liquid Glass", "Kapalıyken ada düz, opak siyahtır") {
                SwitchRow("Cam efekti", null, draft.glassEnabled) { draft = draft.copy(glassEnabled = it) }
                if (draft.glassEnabled) {
                    SliderRow("Şeffaflık", draft.glassAlpha, 0.10f..0.90f, "%${(draft.glassAlpha * 100).toInt()}") { draft = draft.copy(glassAlpha = it) }
                    SliderRow("Arka plan bulanıklığı", draft.blurRadiusPx.toFloat(), 0f..80f, "${draft.blurRadiusPx} px") { draft = draft.copy(blurRadiusPx = it.toInt()) }
                    if (draft.windowPadDp() > 0f) {
                        Text("Not: Parıltı veya Zıplamalı animasyon açıkken bulanıklık devre dışı kalır.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, lineHeight = 17.sp)
                    }
                }
            }

            SectionCard("Parıltı (Glow)", "Renk, çalan şarkının kapağından alınır") {
                SwitchRow("Parıltı efekti", "Kapalı ve açık halde adanın etrafında", draft.glowEnabled) { draft = draft.copy(glowEnabled = it) }
                if (draft.glowEnabled) {
                    SliderRow("Yoğunluk", draft.glowIntensity, 0.2f..1f, "%${(draft.glowIntensity * 100).toInt()}") { draft = draft.copy(glowIntensity = it) }
                }
            }

            SectionCard("İlerleme çubuğu parıltısı", "Açık adadaki müzik panelinde, şarkı çubuğunda") {
                SwitchRow("Parıltı efekti", "Şarkı ilerleme çubuğu ışık yayar", draft.progressGlowEnabled) { draft = draft.copy(progressGlowEnabled = it) }
                if (draft.progressGlowEnabled) {
                    SubHeader("Parıltı nerede olsun")
                    SegmentedTabs(listOf("Tüm çubuk", "Çubuğun ucu"), if (draft.progressGlowTipOnly) 1 else 0) {
                        draft = draft.copy(progressGlowTipOnly = it == 1)
                    }
                    if (draft.progressGlowTipOnly) {
                        Text("Uç parıltısı her zaman çubuğun içinde kalır, dışarı taşmaz.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, lineHeight = 17.sp)
                    } else {
                        SwitchRow("Parıltı çubuğun içinde kalsın", "Kapalıyken ışık çubuğun dışına yayılır; açıkken dışarı taşmaz",
                            draft.progressGlowInside) { draft = draft.copy(progressGlowInside = it) }
                    }
                    SliderRow("Yoğunluk", draft.progressGlowIntensity, 0.2f..1f, "%${(draft.progressGlowIntensity * 100).toInt()}") { draft = draft.copy(progressGlowIntensity = it) }
                    SectionDivider()
                    SwitchRow("Albüm kapağı rengi", "Parıltı, çalan şarkının kapağına göre değişir",
                        draft.progressGlowArtColor) { draft = draft.copy(progressGlowArtColor = it) }
                    SubHeader(if (draft.progressGlowArtColor) "Kapak yoksa kullanılacak renk" else "Parıltı rengi")
                    key(version) { ColorPicker(draft.progressGlowColorArgb) { draft = draft.copy(progressGlowColorArgb = it) } }
                }
            }

            SectionCard("Çerçeve", "Albüm renginden bağımsız, istediğin renk") {
                SwitchRow("Çerçeve çizgisi", null, draft.borderEnabled) { draft = draft.copy(borderEnabled = it) }
                if (draft.borderEnabled) {
                    SliderRow("Kalınlık", draft.borderWidthDp, 0.5f..3f, "${"%.1f".format(draft.borderWidthDp)} dp") { draft = draft.copy(borderWidthDp = it) }
                    SubHeader("Çerçeve rengi")
                    key(version) { ColorPicker(draft.borderColorArgb) { draft = draft.copy(borderColorArgb = it) } }
                }
            }

            // ---- Hareket
            GroupLabel("Hareket")
            SectionCard("Animasyon", "Açılma ve küçülme geçişi") {
                SwitchRow("Animasyonlar", "Kapatırsan ada anında açılır/kapanır", draft.animEnabled) { draft = draft.copy(animEnabled = it) }
                if (draft.animEnabled) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        ANIM_STYLES.forEachIndexed { i, (name, desc) ->
                            RadioRow(name, desc, draft.animStyle == i) { draft = draft.copy(animStyle = i) }
                        }
                    }
                }
            }

            IslandActionButton("Varsayılanlara dön", { draft = IslandSettings(); version++ })
            Spacer(Modifier.height(28.dp))
        }
    }
}

// ---------------------------------------------------------------- Önizleme
private const val SAMPLE_COLOR = 0xFFFF5F9E.toInt()

private fun sampleArt(): ImageBitmap {
    val bmp = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bmp)
    val paint = android.graphics.Paint().apply {
        shader = android.graphics.LinearGradient(0f, 0f, 64f, 64f, SAMPLE_COLOR, 0xFF7B5CFF.toInt(), android.graphics.Shader.TileMode.CLAMP)
    }
    canvas.drawRect(0f, 0f, 64f, 64f, paint)
    return bmp.asImageBitmap()
}

private val SAMPLE_LINES = listOf(
    LyricLine(0, "Gecenin sessizliğinde yürürken"),
    LyricLine(3500, "Yıldızlar yolumu aydınlatıyor"),
    LyricLine(7000, "Her adımda biraz daha yaklaşıyorum"),
    LyricLine(10500, "Bu şarkı hiç bitmesin istiyorum"),
)
private val SAMPLE_STATE = LyricsState.Ready(SAMPLE_LINES, true, "örnek")
private const val SAMPLE_LOOP = 14_000L

@Composable
private fun PreviewCard(cfg: IslandSettings, mode: Int, onMode: (Int) -> Unit, expanded: Boolean, onToggle: () -> Unit) {
    val art = remember { sampleArt() }
    val music = mode == 1
    val sample = remember(mode) {
        if (mode == 0) IslandContent.Notif("x", "Ayşe", "Akşam yemeğe geliyor musun?")
        else IslandContent.Media("Örnek Şarkı", "Sanatçı", true, 210_000, 75_000, 1f, SystemClock.elapsedRealtime(), art = art, artColor = SAMPLE_COLOR)
    }
    val compactLy = music && cfg.lyricsEnabled && cfg.lyricsCompact
    val frame = rememberLyricFrame(
        sample as? IslandContent.Media, SAMPLE_STATE, cfg.lyricsOffsetMs.toLong(),
        active = music && cfg.lyricsEnabled, fine = cfg.lyricsFillEnabled, loopMs = SAMPLE_LOOP,
    )
    val progress = rememberIslandProgress(cfg, expanded)
    val geo = islandGeo(cfg, music, compactLy)
    val boxH by animateDpAsState(if (expanded) geo.eh + 64.dp else 160.dp, tween(320, easing = FastOutSlowInEasing), label = "previewH")
    val showOutside = music && !expanded && cfg.lyricsEnabled && cfg.lyricsOutside
    val shape = RoundedCornerShape(28.dp)

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(
            Modifier.fillMaxWidth().height(boxH).clip(shape)
                .background(Brush.linearGradient(listOf(Color(0xFF1F4E6B), Color(0xFF3A3A78), Color(0xFF5A3470))))
                .border(1.dp, Color.White.copy(alpha = 0.10f), shape)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onToggle),
            contentAlignment = Alignment.TopCenter,
        ) {
            Box(Modifier.padding(top = 20.dp)) {
                IslandPill(sample, progress, geo, cfg, SAMPLE_STATE, frame, compactLy, onToggle, {}, {})
            }
            if (showOutside) {
                OutsideLyricText(
                    cfg, SAMPLE_STATE, frame,
                    Modifier.align(Alignment.BottomCenter).padding(bottom = 46.dp)
                        .width(cfg.lyricsOutsideWidthDp.dp.coerceAtMost(320.dp)),
                    artColor = SAMPLE_COLOR,
                )
            }
            Text(
                if (expanded) "Küçültmek için dokun" else "Açmak için dokun",
                color = Color.White.copy(alpha = 0.78f), fontSize = 11.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp)
                    .clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = 0.30f))
                    .padding(horizontal = 12.dp, vertical = 5.dp),
            )
        }
        SegmentedTabs(listOf("Bildirim", "Müzik"), mode, onMode)
    }
}

// ---------------------------------------------------------------- Şarkı sözü ayarları
@Composable
private fun LyricsSections(draft: IslandSettings, version: Int, set: (IslandSettings) -> Unit) {
    val cs = MaterialTheme.colorScheme
    SectionCard("Şarkı sözleri", "Önce LRCLIB, bulunamazsa NetEase'te aranır") {
        SwitchRow("Şarkı sözleri",
            "Başlık, sanatçı ve süre sunucuya gönderilir. Bildirimi olan her oynatıcıyla çalışır (Spotify, YouTube Music, Yandex Müzik, VK…).",
            draft.lyricsEnabled) { set(draft.copy(lyricsEnabled = it)) }
        if (draft.lyricsEnabled) {
            Text("Senkron söz yoksa düz sözler şarkı süresine yayılır. Aranırken \"Şarkı sözü aranıyor…\", bulunamazsa \"Şarkı sözü bulunamadı\" yazar.",
                color = cs.onSurfaceVariant, fontSize = 12.sp, lineHeight = 17.sp)
        }
    }
    if (!draft.lyricsEnabled) return

    SectionCard("Nerede görünsün", "İstediğin kadarını aynı anda açabilirsin") {
        SwitchRow("Genişletilmiş adada", "Şarkı adı ile ilerleme çubuğu arasında", draft.lyricsInPanel) { set(draft.copy(lyricsInPanel = it)) }
        SectionDivider()
        SwitchRow("Kapalı adada (ortada)", "Albüm kapağı ile equalizer arasındaki boşlukta; ada sözler için genişler", draft.lyricsCompact) { set(draft.copy(lyricsCompact = it)) }
        SectionDivider()
        SwitchRow("Adanın dışında", "Adayı açmadan, ekranın üstünde ayrı görünür", draft.lyricsOutside) { set(draft.copy(lyricsOutside = it)) }
    }

    if (draft.lyricsInPanel) SectionCard("Genişletilmiş ada", "Panelin içindeki sözler") {
        SliderRow("Yazı boyutu", draft.lyricsPanelSizeSp, 12f..26f, "${draft.lyricsPanelSizeSp.toInt()} sp") { set(draft.copy(lyricsPanelSizeSp = it)) }
        SliderRow("En fazla satır", draft.lyricsPanelLines.toFloat(), 1f..3f, "${draft.lyricsPanelLines}") { set(draft.copy(lyricsPanelLines = it.roundToInt().coerceIn(1, 3))) }
    }

    if (draft.lyricsCompact) SectionCard("Kapalı ada", "Tek satır; sığmazsa söylendikçe yana kayar") {
        SliderRow("Yazı boyutu", draft.lyricsCompactSizeSp, 8f..18f, "${draft.lyricsCompactSizeSp.toInt()} sp") { set(draft.copy(lyricsCompactSizeSp = it)) }
        SliderRow("Ada genişliği (sözler varken)", draft.lyricsCompactWidthDp, 120f..300f, "${draft.lyricsCompactWidthDp.toInt()} dp") { set(draft.copy(lyricsCompactWidthDp = it)) }
        Text("Not: Kamera deliği adanın tam ortasında olduğu için orta kısımdaki harfler deliğin arkasında kalabilir; genişlik arttıkça okunabilirlik artar.",
            color = cs.onSurfaceVariant, fontSize = 12.sp, lineHeight = 17.sp)
    }

    if (draft.lyricsOutside) SectionCard("Adanın dışı", "Ayrı, dokunulmaz söz katmanı (ada genişleyince gizlenir)") {
        SliderRow("Yazı boyutu", draft.lyricsOutsideSizeSp, 12f..40f, "${draft.lyricsOutsideSizeSp.toInt()} sp") { set(draft.copy(lyricsOutsideSizeSp = it)) }
        SliderRow("En fazla satır", draft.lyricsOutsideLines.toFloat(), 1f..3f, "${draft.lyricsOutsideLines}") { set(draft.copy(lyricsOutsideLines = it.roundToInt().coerceIn(1, 3))) }
        SliderRow("Genişlik", draft.lyricsOutsideWidthDp, 180f..420f, "${draft.lyricsOutsideWidthDp.toInt()} dp") { set(draft.copy(lyricsOutsideWidthDp = it)) }
        SliderRow("Yükseklik (ekranın üstünden)", draft.lyricsOutsideYDp, 0f..760f, "${draft.lyricsOutsideYDp.toInt()} dp") { set(draft.copy(lyricsOutsideYDp = it)) }
        SliderRow("Yatay konum", draft.lyricsOutsideXDp, -150f..150f, "${draft.lyricsOutsideXDp.toInt()} dp") { set(draft.copy(lyricsOutsideXDp = it)) }
    }

    SectionCard("Renk", "Üç yerde de ortak") {
        SwitchRow("Albüm kapağı rengi", "Söz rengi, çalan şarkının kapağına göre değişir",
            draft.lyricsDynamicColor) { set(draft.copy(lyricsDynamicColor = it)) }
        SectionDivider()
        SubHeader(if (draft.lyricsDynamicColor) "Kapak yoksa kullanılacak renk" else "Söz rengi")
        key(version) { ColorPicker(draft.lyricsColorArgb) { set(draft.copy(lyricsColorArgb = it)) } }
        SliderRow("Söylenmemiş kısım", draft.lyricsDimAlpha, 0.1f..0.8f, "%${(draft.lyricsDimAlpha * 100).toInt()}") { set(draft.copy(lyricsDimAlpha = it)) }
    }

    SectionCard("Dolum ve senkron", "Satır şarkıyla birlikte soldan sağa dolar") {
        SwitchRow("Söylendikçe dolsun", "Kapatırsan satırın tamamı parlak görünür", draft.lyricsFillEnabled) { set(draft.copy(lyricsFillEnabled = it)) }
        SectionDivider()
        val off = draft.lyricsOffsetMs
        SliderRow("Senkron kaydırma", off.toFloat(), -2000f..2000f, (if (off > 0) "+$off" else "$off") + " ms") {
            set(draft.copy(lyricsOffsetMs = (it / 50f).roundToInt() * 50))
        }
        Text("+ değer sözleri daha erken, − değer daha geç gösterir.", color = cs.onSurfaceVariant, fontSize = 12.sp)
    }

    SectionCard("Parıltı (Glow)", "Söylenen kısmın etrafında") {
        SwitchRow("Parıltı efekti", null, draft.lyricsGlowEnabled) { set(draft.copy(lyricsGlowEnabled = it)) }
        if (draft.lyricsGlowEnabled) {
            SliderRow("Seviye", draft.lyricsGlowLevel, 0.1f..1f, "%${(draft.lyricsGlowLevel * 100).toInt()}") { set(draft.copy(lyricsGlowLevel = it)) }
        }
    }

    SectionCard("Satır geçiş animasyonu", "Bir satırdan diğerine geçerken") {
        SwitchRow("Geçiş animasyonları", "Kapatırsan satırlar anında değişir", draft.lyricsAnimEnabled) { set(draft.copy(lyricsAnimEnabled = it)) }
        if (draft.lyricsAnimEnabled) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                LYRIC_ANIMS.forEachIndexed { i, (name, desc) ->
                    RadioRow(name, desc, draft.lyricsAnimStyle == i) { set(draft.copy(lyricsAnimStyle = i)) }
                }
            }
            if (draft.lyricsAnimStyle == 6 || draft.lyricsAnimStyle == 7) {
                Text(
                    "Not: Sözler, \"Adanın dışında\" açıkken adanın içinden çıkar. Panel ve kapalı adada yerinde küçülüp büyür.",
                    color = cs.onSurfaceVariant, fontSize = 12.sp, lineHeight = 17.sp,
                )
            }
            if (draft.lyricsAnimStyle != 5) {
                SliderRow("Süre", draft.lyricsAnimMs.toFloat(), 120f..900f, "${draft.lyricsAnimMs} ms") { set(draft.copy(lyricsAnimMs = it.toInt())) }
            }
        }
    }
}

// ---------------------------------------------------------------- Renk seçici (HSV)
@Composable
fun ColorPicker(argb: Int, onColor: (Int) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val hsv0 = remember { FloatArray(3).also { android.graphics.Color.colorToHSV(argb, it) } }
    var h by remember { mutableFloatStateOf(hsv0[0]) }
    var s by remember { mutableFloatStateOf(hsv0[1]) }
    var v by remember { mutableFloatStateOf(hsv0[2]) }
    fun emit() = onColor(Color.hsv(h, s, v).toArgb())

    val presets = remember { listOf(0xFF0A84FF, 0xFF30D158, 0xFFFF453A, 0xFFFF9F0A, 0xFFBF5AF2, 0xFFFFFFFF).map { Color(it) } }
    val current = Color.hsv(h, s, v)

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            presets.forEach { c ->
                val picked = c.toArgb() == current.toArgb()
                Box(
                    Modifier.size(34.dp)
                        .border(if (picked) 2.dp else 0.dp, if (picked) cs.primary else Color.Transparent, CircleShape)
                        .padding(3.dp)
                        .clip(CircleShape).background(c)
                        .border(1.dp, Color.White.copy(alpha = 0.12f), CircleShape)
                        .clickable(role = Role.Button) {
                            val t = FloatArray(3); android.graphics.Color.colorToHSV(c.toArgb(), t)
                            h = t[0]; s = t[1]; v = t[2]; emit()
                        }
                )
            }
            Spacer(Modifier.weight(1f))
            Box(Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).background(current).border(1.dp, cs.outline, RoundedCornerShape(10.dp)))
        }
        HueBar(h) { h = it; emit() }
        SliderRow("Doygunluk", s, 0f..1f, "%${(s * 100).toInt()}") { s = it; emit() }
        SliderRow("Parlaklık", v, 0f..1f, "%${(v * 100).toInt()}") { v = it; emit() }
    }
}

@Composable
private fun HueBar(hue: Float, onHue: (Float) -> Unit) {
    val colors = remember { (0..360 step 30).map { Color.hsv(it.toFloat(), 1f, 1f) } }
    var w by remember { mutableIntStateOf(1) }
    val cb by rememberUpdatedState(onHue)
    Box(
        Modifier.fillMaxWidth().height(30.dp).clip(RoundedCornerShape(15.dp))
            .background(Brush.horizontalGradient(colors))
            .onSizeChanged { w = it.width.coerceAtLeast(1) }
            .pointerInput(Unit) { detectTapGestures { cb((it.x / w * 360f).coerceIn(0f, 360f)) } }
            .pointerInput(Unit) {
                detectHorizontalDragGestures { change, _ ->
                    change.consume(); cb((change.position.x / w * 360f).coerceIn(0f, 360f))
                }
            }
    ) {
        Box(
            Modifier.align(Alignment.CenterStart)
                .offset {
                    val thumb = 24.dp.toPx()
                    IntOffset((hue / 360f * w - thumb / 2f).toInt().coerceIn(0, (w - thumb.toInt()).coerceAtLeast(0)), 0)
                }
                .size(24.dp).border(2.5.dp, Color.White, CircleShape).background(Color.hsv(hue, 1f, 1f), CircleShape)
        )
    }
}
