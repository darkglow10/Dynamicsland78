package com.example.dynamicisland

import android.graphics.Bitmap
import android.os.SystemClock
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

private val ANIM_STYLES = listOf(
    "Yaylı" to "Dengeli, hafif yaylanan geçiş",
    "Yumuşak" to "Yavaş ve akıcı kayma",
    "Hızlı" to "Kısa ve seri geçiş",
    "Zıplamalı" to "Açılırken hafifçe zıplar",
    "Sıvı" to "Önce genişlik, sonra yükseklik",
)

@Composable
fun SettingsScreen() {
    val ctx = LocalContext.current
    val repo = remember { SettingsRepository(ctx.applicationContext) }
    var initial by remember { mutableStateOf<IslandSettings?>(null) }
    LaunchedEffect(Unit) { initial = repo.flow.first() }
    val init = initial ?: return
    SettingsContent(init) { repo.save(it) }
}

@Composable
private fun SettingsContent(init: IslandSettings, onSave: suspend (IslandSettings) -> Unit) {
    var draft by remember { mutableStateOf(init) }
    var version by remember { mutableIntStateOf(0) }
    var mode by remember { mutableIntStateOf(1) }            // 0 bildirim, 1 müzik
    var expanded by remember { mutableStateOf(false) }
    val ctx = LocalContext.current
    val versionName = remember {
        runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }
            .getOrNull() ?: "?"
    }

    // Slider sürüklenirken değil, 250 ms sakinleşince kaydet
    LaunchedEffect(draft) { delay(250); onSave(draft) }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // ---- Sabit canlı önizleme (kaydırırken de görünür kalır)
        PreviewCard(draft, mode, { mode = it }, expanded) { expanded = !expanded }

        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // ---- Boyut
            SectionCard("BOYUT", "Ölçüler dp cinsindendir") {
                SubHeader("Kapalı (küçük) hal")
                SliderRow("Genişlik", draft.compactWidthDp, 60f..220f, "${draft.compactWidthDp.toInt()} dp") { draft = draft.copy(compactWidthDp = it) }
                SliderRow("Yükseklik", draft.compactHeightDp, 20f..60f, "${draft.compactHeightDp.toInt()} dp") { draft = draft.copy(compactHeightDp = it) }
                SectionDivider()
                SubHeader("Açık (genişletilmiş) hal")
                SliderRow("Genişlik", draft.expandedWidthDp, 260f..400f, "${draft.expandedWidthDp.toInt()} dp") { draft = draft.copy(expandedWidthDp = it) }
                SliderRow("Yükseklik", draft.expandedHeightDp, 72f..120f, "${draft.expandedHeightDp.toInt()} dp") { draft = draft.copy(expandedHeightDp = it) }
                SectionDivider()
                SubHeader("Konum ince ayarı")
                SliderRow("Yatay", draft.offsetXDp, -60f..60f, "${draft.offsetXDp.toInt()} dp") { draft = draft.copy(offsetXDp = it) }
                SliderRow("Dikey", draft.offsetYDp, -20f..40f, "${draft.offsetYDp.toInt()} dp") { draft = draft.copy(offsetYDp = it) }
            }

            // ---- Renk
            SectionCard("RENK", "Adanın genel renk davranışı") {
                SwitchRow("Albüm kapağı rengi", "Ada rengi, çalan şarkının kapağına göre değişir",
                    draft.dynamicColor) { draft = draft.copy(dynamicColor = it) }
                SectionDivider()
                SubHeader("Vurgu / cam rengi")
                key(version) { ColorPicker(draft.accentArgb) { draft = draft.copy(accentArgb = it) } }
            }

            // ---- Cam
            SectionCard("LIQUID GLASS", "Kapalıyken ada düz, opak siyahtır") {
                SwitchRow("Cam efekti", null, draft.glassEnabled) { draft = draft.copy(glassEnabled = it) }
                if (draft.glassEnabled) {
                    SliderRow("Şeffaflık", draft.glassAlpha, 0.10f..0.90f, "%${(draft.glassAlpha * 100).toInt()}") { draft = draft.copy(glassAlpha = it) }
                    SliderRow("Arka plan bulanıklığı", draft.blurRadiusPx.toFloat(), 0f..80f, "${draft.blurRadiusPx} px") { draft = draft.copy(blurRadiusPx = it.toInt()) }
                    if (draft.windowPadDp() > 0f) {
                        Text("Not: Parıltı veya Zıplamalı animasyon açıkken bulanıklık devre dışı kalır.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                    }
                }
            }

            // ---- Parıltı
            SectionCard("PARILTI (GLOW)", "Renk, çalan şarkının kapağından alınır") {
                SwitchRow("Parıltı efekti", "Kapalı ve açık halde adanın etrafında", draft.glowEnabled) { draft = draft.copy(glowEnabled = it) }
                if (draft.glowEnabled) {
                    SliderRow("Yoğunluk", draft.glowIntensity, 0.2f..1f, "%${(draft.glowIntensity * 100).toInt()}") { draft = draft.copy(glowIntensity = it) }
                }
            }

            // ---- Çerçeve
            SectionCard("ÇERÇEVE", "Albüm renginden bağımsız, istediğin renk") {
                SwitchRow("Çerçeve çizgisi", null, draft.borderEnabled) { draft = draft.copy(borderEnabled = it) }
                if (draft.borderEnabled) {
                    SliderRow("Kalınlık", draft.borderWidthDp, 0.5f..3f, "${"%.1f".format(draft.borderWidthDp)} dp") { draft = draft.copy(borderWidthDp = it) }
                    SubHeader("Çerçeve rengi")
                    key(version) { ColorPicker(draft.borderColorArgb) { draft = draft.copy(borderColorArgb = it) } }
                }
            }

            // ---- Animasyon
            SectionCard("ANİMASYON", "Açılma ve küçülme geçişi") {
                SwitchRow("Animasyonlar", "Kapatırsan ada anında açılır/kapanır", draft.animEnabled) { draft = draft.copy(animEnabled = it) }
                if (draft.animEnabled) {
                    ANIM_STYLES.forEachIndexed { i, (name, desc) ->
                        RadioRow(name, desc, draft.animStyle == i) { draft = draft.copy(animStyle = i) }
                    }
                }
            }

            // ---- Güncelleme
            SectionCard("GÜNCELLEME", "Yüklü sürüm: $versionName") {
                Button(
                    onClick = {
                        // TODO (sonraki adım): gerçek güncelleme kontrolü buraya bağlanacak
                        Toast.makeText(ctx, "Güncelleme kontrolü yakında eklenecek", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Güncellemeleri Kontrol Et")
                }
            }

            OutlinedButton(onClick = { draft = IslandSettings(); version++ }, modifier = Modifier.fillMaxWidth()) {
                Text("Varsayılanlara dön")
            }
            Spacer(Modifier.height(16.dp))
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

@Composable
private fun PreviewCard(cfg: IslandSettings, mode: Int, onMode: (Int) -> Unit, expanded: Boolean, onToggle: () -> Unit) {
    val art = remember { sampleArt() }
    val sample = remember(mode) {
        if (mode == 0) IslandContent.Notif("x", "Ayşe", "Akşam yemeğe geliyor musun?")
        else IslandContent.Media("Örnek Şarkı", "Sanatçı", true, 210_000, 75_000, 1f, SystemClock.elapsedRealtime(), art = art, artColor = SAMPLE_COLOR)
    }
    val progress = rememberIslandProgress(cfg, expanded)

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(
            Modifier.fillMaxWidth().height(180.dp).clip(RoundedCornerShape(20.dp))
                .background(Brush.linearGradient(listOf(Color(0xFF2B5876), Color(0xFF4E4376)))),
            contentAlignment = Alignment.TopCenter,
        ) {
            Box(Modifier.padding(top = 20.dp)) {
                IslandPill(sample, progress, islandGeo(cfg, mode == 1), cfg, onToggle, {})
            }
            Text("Önizlemeye dokunarak aç / kapat", color = Color.White.copy(alpha = 0.55f), fontSize = 11.sp,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(mode == 0, { onMode(0) }, { Text("Bildirim") })
            FilterChip(mode == 1, { onMode(1) }, { Text("Müzik") })
        }
    }
}

// ---------------------------------------------------------------- Renk seçici (HSV)
@Composable
fun ColorPicker(argb: Int, onColor: (Int) -> Unit) {
    val hsv0 = remember { FloatArray(3).also { android.graphics.Color.colorToHSV(argb, it) } }
    var h by remember { mutableFloatStateOf(hsv0[0]) }
    var s by remember { mutableFloatStateOf(hsv0[1]) }
    var v by remember { mutableFloatStateOf(hsv0[2]) }
    fun emit() = onColor(Color.hsv(h, s, v).toArgb())

    val presets = remember { listOf(0xFF0A84FF, 0xFF30D158, 0xFFFF453A, 0xFFFF9F0A, 0xFFBF5AF2, 0xFFFFFFFF).map { Color(it) } }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            presets.forEach { c ->
                Box(
                    Modifier.size(32.dp).background(c, CircleShape).border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                        .clickable {
                            val t = FloatArray(3); android.graphics.Color.colorToHSV(c.toArgb(), t)
                            h = t[0]; s = t[1]; v = t[2]; emit()
                        }
                )
            }
            Spacer(Modifier.weight(1f))
            Box(Modifier.size(32.dp).background(Color.hsv(h, s, v), RoundedCornerShape(8.dp)).border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp)))
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
        Modifier.fillMaxWidth().height(28.dp).clip(RoundedCornerShape(14.dp))
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
                .offset { IntOffset((hue / 360f * w - 10.dp.toPx()).toInt(), 0) }
                .size(20.dp).border(2.dp, Color.White, CircleShape)
        )
    }
}
