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
    var tab by remember { mutableIntStateOf(0) }
    Column(
        Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Dynamic Island", color = MaterialTheme.colorScheme.onBackground, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        SegmentedTabs(listOf("Kurulum", "Ayarlar"), tab) { tab = it }
        Box(Modifier.weight(1f)) {
            if (tab == 0) SetupScreen() else SettingsScreen()
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

    val canStart = overlay && postNotif

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            StatusBanner(canStart, listener)

            SectionCard("İZİNLER", "Her izin Ayarlar'da tek dokunuşla verilir") {
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
                PermRow("Erişilebilirlik (dokunma için gerekli)", "Adanın durum çubuğunda dokunulabilmesi için", a11y, false) {
                    ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }
            }

            SectionCard("İPUCU", "Sideload (APK) kurulumlarında") {
                Text(
                    "Android 13+ üzerinde bildirim erişimi ve erişilebilirlik anahtarları gri görünebilir. " +
                        "Uygulama bilgisi → ⋮ → \"Kısıtlı ayarlara izin ver\" seçeneğini açıp tekrar dene.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp,
                )
                OutlinedButton(onClick = {
                    ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}")))
                }) { Text("Uygulama bilgisini aç") }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            Button(
                enabled = canStart,
                modifier = Modifier.weight(1f).height(52.dp),
                onClick = { ContextCompat.startForegroundService(ctx, Intent(ctx, IslandOverlayService::class.java)) },
            ) { Text("Başlat", fontSize = 16.sp) }
            OutlinedButton(
                modifier = Modifier.height(52.dp),
                onClick = { ctx.stopService(Intent(ctx, IslandOverlayService::class.java)) },
            ) { Text("Durdur") }
        }
    }
}

@Composable
private fun StatusBanner(canStart: Boolean, listener: Boolean) {
    val cs = MaterialTheme.colorScheme
    val (title, desc) = when {
        canStart && listener -> "Her şey hazır" to "Başlat'a dokunarak adayı çalıştırabilirsin."
        canStart -> "Neredeyse hazır" to "Bildirim erişimi olmadan bildirim ve müzik gösterilmez."
        else -> "Eksik izinler var" to "Aşağıdaki zorunlu izinleri ver, sonra Başlat'a dokun."
    }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))
            .background(if (canStart && listener) cs.primaryContainer else cs.surfaceVariant).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(title, color = cs.onSurface, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        Text(desc, color = cs.onSurfaceVariant, fontSize = 13.sp)
    }
}

@Composable
private fun PermRow(title: String, desc: String, granted: Boolean, required: Boolean, onOpen: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(
            Modifier.size(10.dp).background(
                when { granted -> Color(0xFF4CD964); required -> Color(0xFFFFB340); else -> cs.outline }, CircleShape
            )
        )
        Column(Modifier.weight(1f)) {
            Text(title, color = cs.onSurface, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Text(desc, color = cs.onSurfaceVariant, fontSize = 12.sp)
        }
        if (granted) Text("Hazır", color = Color(0xFF4CD964), fontSize = 13.sp, fontWeight = FontWeight.Medium)
        else FilledTonalButton(onClick = onOpen) { Text("Aç") }
    }
}
