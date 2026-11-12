package com.example.dynamicisland

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            IslandTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { AppRoot() }
            }
        }
    }
}

private fun Context.hasOverlay() = Settings.canDrawOverlays(this)
private fun Context.hasListener() =
    NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)
private fun Context.hasAccessibility(): Boolean {
    val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
    return enabled.contains("$packageName/${IslandAccessibilityService::class.java.name}")
}
private fun Context.hasPostNotif() =
    Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == 0

@Composable
private fun AppRoot() {
    val cs = MaterialTheme.colorScheme
    var tab by remember { mutableIntStateOf(0) }
    Column(
        Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(Modifier.padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("Dynamic Island", color = cs.onBackground, fontSize = 26.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp)
            Text("Kamera çevresinde canlı ada", color = cs.onSurfaceVariant, fontSize = 13.sp)
        }
        SegmentedTabs(listOf("Kurulum", "Ayarlar", "Sözler"), tab) { tab = it }
        Box(Modifier.weight(1f)) {
            when (tab) {
                0 -> SetupScreen()
                1 -> key(1) { SettingsScreen(lyricsPage = false) }
                else -> key(2) { SettingsScreen(lyricsPage = true) }
            }
        }
    }
}

@Composable
private fun SetupScreen() {
    val ctx = LocalContext.current
    var overlay by remember { mutableStateOf(ctx.hasOverlay()) }
    var listener by remember { mutableStateOf(ctx.hasListener()) }
    var a11y by remember { mutableStateOf(ctx.hasAccessibility()) }
    var postNotif by remember { mutableStateOf(ctx.hasPostNotif()) }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        overlay = ctx.hasOverlay(); listener = ctx.hasListener()
        a11y = ctx.hasAccessibility(); postNotif = ctx.hasPostNotif()
    }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { postNotif = it }

    // Dört izin de verilmeden "hazır" denmez ve Başlat açılmaz
    val missing = buildList {
        if (!overlay) add("Üstte gösterme")
        if (!listener) add("Bildirim erişimi")
        if (!postNotif) add("Bildirim gösterme")
        if (!a11y) add("Erişilebilirlik")
    }
    val canStart = missing.isEmpty()

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            StatusBanner(missing)

            SectionCard("İzinler", "Her izin Ayarlar'da tek dokunuşla verilir") {
                PermRow("Üstte gösterme", "Adayı diğer uygulamaların üzerinde çizer", overlay, true) {
                    ctx.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}")))
                }
                SectionDivider()
                PermRow("Bildirim erişimi", "Bildirimleri ve müzik oturumunu okur", listener, true) {
                    ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                }
                SectionDivider()
                PermRow("Bildirim gösterme", "Servis bildirimi için gerekli", postNotif, true) {
                    if (Build.VERSION.SDK_INT >= 33) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
                SectionDivider()
                PermRow("Erişilebilirlik", "Adaya dokunabilmek için gerekli", a11y, true) {
                    ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }
            }

            SectionCard("İpucu", "Sideload (APK) kurulumlarında") {
                Text(
                    "Android 13+ üzerinde bildirim erişimi ve erişilebilirlik anahtarları gri görünebilir. " +
                        "Uygulama bilgisi → ⋮ → \"Kısıtlı ayarlara izin ver\" seçeneğini açıp tekrar dene.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp, lineHeight = 19.sp,
                )
                IslandActionButton("Uygulama bilgisini aç", {
                    ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}")))
                })
            }
            Spacer(Modifier.height(8.dp))
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            ActionBtn(
                "Başlat", primary = true, enabled = canStart, modifier = Modifier.weight(1f),
                onClick = {
                    ContextCompat.startForegroundService(ctx, Intent(ctx, IslandOverlayService::class.java))
                    IslandNotificationListener.republish(ctx)   // servis zaten açıksa da mevcut müziği tekrar yayınla
                },
            )
            ActionBtn(
                "Durdur", primary = false, enabled = true, modifier = Modifier.weight(0.6f),
                onClick = { ctx.stopService(Intent(ctx, IslandOverlayService::class.java)) },
            )
        }
    }
}

@Composable
private fun ActionBtn(text: String, primary: Boolean, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(16.dp)
    val bg = when { !enabled -> cs.surfaceVariant; primary -> cs.primary; else -> cs.surfaceVariant }
    val fg = when { !enabled -> cs.onSurfaceVariant.copy(alpha = 0.5f); primary -> cs.onPrimary; else -> cs.onSurface }
    Box(
        modifier.height(54.dp).clip(shape).background(bg)
            .border(1.dp, if (primary && enabled) Color.Transparent else cs.outline, shape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = fg, fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
}

@Composable
private fun StatusBanner(missing: List<String>) {
    val cs = MaterialTheme.colorScheme
    val ready = missing.isEmpty()
    val title = if (ready) "Her şey hazır" else "Eksik izinler var"
    val desc = if (ready) "Başlat'a dokunarak adayı çalıştırabilirsin."
        else "Başlat'ı açmak için şunları ver: " + missing.joinToString(", ") + "."
    val dot = when { ready -> Color(0xFF4CD964); missing.size == 1 -> Color(0xFFFFB340); else -> Color(0xFFFF6B5E) }
    val shape = RoundedCornerShape(22.dp)
    Row(
        Modifier.fillMaxWidth().clip(shape)
            .background(if (ready) cs.primaryContainer.copy(alpha = 0.55f) else cs.surface)
            .border(1.dp, if (ready) cs.primary.copy(alpha = 0.35f) else cs.outlineVariant, shape)
            .padding(18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(Modifier.size(12.dp).background(dot, CircleShape))
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, color = cs.onSurface, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Text(desc, color = cs.onSurfaceVariant, fontSize = 13.sp, lineHeight = 18.sp)
        }
    }
}

@Composable
private fun PermRow(title: String, desc: String, granted: Boolean, required: Boolean, onOpen: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val ok = Color(0xFF4CD964)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(
            Modifier.size(10.dp).background(
                when { granted -> ok; required -> Color(0xFFFFB340); else -> cs.outline }, CircleShape
            )
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, color = cs.onSurface, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Text(desc, color = cs.onSurfaceVariant, fontSize = 12.sp, lineHeight = 16.sp)
        }
        if (granted) {
            Text(
                "Hazır", color = ok, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clip(RoundedCornerShape(50)).background(ok.copy(alpha = 0.14f))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        } else {
            Text(
                "Aç", color = cs.onPrimaryContainer, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clip(RoundedCornerShape(50)).background(cs.primaryContainer)
                    .clickable(onClick = onOpen).padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }
}
