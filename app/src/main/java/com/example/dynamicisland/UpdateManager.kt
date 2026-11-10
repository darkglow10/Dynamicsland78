package com.example.dynamicisland

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

data class ReleaseInfo(
    val tag: String,
    val version: String,
    val apkUrl: String,
    val sizeBytes: Long,
    val notes: String,
)

object UpdateManager {
    private const val OWNER = "whiteglow178"
    private const val REPO = "Dynamic-Island"
    private const val API_URL = "https://api.github.com/repos/$OWNER/$REPO/releases/latest"
    private const val UA = "DynamicIsland-Updater"

    fun currentVersion(ctx: Context): String =
        runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }
            .getOrNull() ?: "0"

    // ------------------------------------------------ Sürüm karşılaştırma
    private fun parse(v: String): List<Int> =
        v.trim().removePrefix("v").removePrefix("V").substringBefore("-")
            .split(".").map { part -> part.filter { it.isDigit() }.toIntOrNull() ?: 0 }

    fun isNewer(remote: String, local: String): Boolean {
        val r = parse(remote)
        val l = parse(local)
        for (i in 0 until maxOf(r.size, l.size)) {
            val a = r.getOrElse(i) { 0 }
            val b = l.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return false
    }

    // ------------------------------------------------ GitHub kontrolü
    /** Yeni sürüm varsa ReleaseInfo, yoksa null döner. Hata olursa exception fırlatır. */
    suspend fun checkForUpdate(ctx: Context): ReleaseInfo? = withContext(Dispatchers.IO) {
        val conn = (URL(API_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 15_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", UA)
        }
        try {
            when (conn.responseCode) {
                200 -> {}
                404 -> error("Yayınlanmış bir sürüm bulunamadı (404)")
                403 -> error("GitHub istek sınırı aşıldı, biraz sonra tekrar dene")
                else -> error("Sunucu hatası: ${conn.responseCode}")
            }
            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val tag = json.getString("tag_name")
            if (!isNewer(tag, currentVersion(ctx))) return@withContext null

            val assets = json.getJSONArray("assets")
            var apkUrl: String? = null
            var size = 0L
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.getString("name").endsWith(".apk", ignoreCase = true)) {
                    apkUrl = a.getString("browser_download_url")
                    size = a.optLong("size", 0L)
                    break
                }
            }
            if (apkUrl == null) error("Bu sürümde APK dosyası yok")

            ReleaseInfo(
                tag = tag,
                version = tag.removePrefix("v").removePrefix("V"),
                apkUrl = apkUrl,
                sizeBytes = size,
                notes = json.optString("body", ""),
            )
        } finally {
            conn.disconnect()
        }
    }

    // ------------------------------------------------ İndirme
    suspend fun download(ctx: Context, info: ReleaseInfo, onProgress: (Float) -> Unit): File =
        withContext(Dispatchers.IO) {
            // provider_paths.xml içindeki cache-path "updates/" ile eşleşir
            val dir = File(ctx.cacheDir, "updates").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            val tmp = File(dir, "update.apk.part")
            val out = File(dir, "update.apk")

            val conn = (URL(info.apkUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 30_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", UA)
            }
            try {
                if (conn.responseCode != 200) error("İndirme hatası: ${conn.responseCode}")
                val total = conn.contentLengthLong.takeIf { it > 0 } ?: info.sizeBytes
                conn.inputStream.use { input ->
                    tmp.outputStream().use { output ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        var lastPct = -1
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            output.write(buf, 0, n)
                            done += n
                            if (total > 0) {
                                val pct = (done * 100 / total).toInt()
                                if (pct != lastPct) {
                                    lastPct = pct
                                    onProgress(pct / 100f)
                                }
                            }
                        }
                    }
                }
                if (!tmp.renameTo(out)) error("Dosya kaydedilemedi")
                out
            } finally {
                conn.disconnect()
            }
        }

    // ------------------------------------------------ Kurulum
    fun canInstall(ctx: Context): Boolean = ctx.packageManager.canRequestPackageInstalls()

    fun openInstallPermissionSettings(ctx: Context) {
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${ctx.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(intent)
    }

    fun install(ctx: Context, apk: File) {
        // Manifest'teki ${applicationId}.fileprovider ile aynı
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", apk)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(intent)
    }
}

// ====================================================================== UI
private sealed interface UpdateState {
    object Idle : UpdateState
    object Checking : UpdateState
    object UpToDate : UpdateState
    data class Available(val info: ReleaseInfo) : UpdateState
    data class Downloading(val progress: Float) : UpdateState
    data class Ready(val file: File, val hint: String?) : UpdateState
    data class Error(val message: String) : UpdateState
}

private fun friendlyError(e: Exception): String = when (e) {
    is IOException -> "Bağlantı hatası. İnternetini kontrol edip tekrar dene."
    else -> e.message ?: "Bilinmeyen hata"
}

@Composable
fun UpdateCard() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val current = remember { UpdateManager.currentVersion(ctx) }
    var state by remember { mutableStateOf<UpdateState>(UpdateState.Idle) }

    fun tryInstall(file: File) {
        if (!UpdateManager.canInstall(ctx)) {
            UpdateManager.openInstallPermissionSettings(ctx)
            state = UpdateState.Ready(
                file,
                "Açılan ekranda 'Bu kaynaktan izin ver' seçeneğini aç, sonra geri dönüp tekrar 'Şimdi Kur'a bas.",
            )
        } else {
            runCatching { UpdateManager.install(ctx, file) }
                .onFailure { state = UpdateState.Error("Kurulum başlatılamadı: ${it.message}") }
        }
    }

    fun startDownload(info: ReleaseInfo) {
        state = UpdateState.Downloading(0f)
        scope.launch {
            try {
                val file = UpdateManager.download(ctx, info) { state = UpdateState.Downloading(it) }
                state = UpdateState.Ready(file, null)
                tryInstall(file)
            } catch (e: Exception) {
                state = UpdateState.Error(friendlyError(e))
            }
        }
    }

    fun check() {
        state = UpdateState.Checking
        scope.launch {
            state = try {
                val info = UpdateManager.checkForUpdate(ctx)
                if (info == null) UpdateState.UpToDate else UpdateState.Available(info)
            } catch (e: Exception) {
                UpdateState.Error(friendlyError(e))
            }
        }
    }

    SectionCard("GÜNCELLEME", "Yüklü sürüm: $current") {
        val st = state
        val dim = MaterialTheme.colorScheme.onSurfaceVariant

        when (st) {
            is UpdateState.Checking -> Text("Kontrol ediliyor…", color = dim, fontSize = 13.sp)
            is UpdateState.UpToDate -> Text("Uygulaman güncel.", color = dim, fontSize = 13.sp)
            is UpdateState.Error -> Text(st.message, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
            is UpdateState.Available -> {
                val mb = if (st.info.sizeBytes > 0) " (%.1f MB)".format(st.info.sizeBytes / 1048576.0) else ""
                Text("Yeni sürüm var: ${st.info.version}$mb", fontSize = 14.sp)
                if (st.info.notes.isNotBlank()) {
                    Text(st.info.notes.trim().take(300), color = dim, fontSize = 12.sp)
                }
                Button(onClick = { startDownload(st.info) }, modifier = Modifier.fillMaxWidth()) {
                    Text("İndir ve Kur")
                }
            }
            is UpdateState.Downloading -> {
                LinearProgressIndicator(progress = { st.progress }, modifier = Modifier.fillMaxWidth())
                Text("İndiriliyor… %${(st.progress * 100).toInt()}", color = dim, fontSize = 13.sp)
            }
            is UpdateState.Ready -> {
                Text(st.hint ?: "İndirme tamamlandı.", color = dim, fontSize = 13.sp)
                Button(onClick = { tryInstall(st.file) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Şimdi Kur")
                }
            }
            is UpdateState.Idle -> {}
        }

        val busy = st is UpdateState.Checking || st is UpdateState.Downloading
        OutlinedButton(onClick = { check() }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            Text("Güncellemeleri Kontrol Et")
        }
    }
}
