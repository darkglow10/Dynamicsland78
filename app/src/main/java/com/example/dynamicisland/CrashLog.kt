package com.example.dynamicisland

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Uygulama sürecinin neden kapandığını (çökme mi, sistem öldürdü mü) kaydeder ve Kurulum ekranında gösterir. */
class DynamicIslandApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
    }
}

object CrashLog {
    private const val FILE = "last_crash.txt"
    private fun stamp(t: Long = System.currentTimeMillis()) =
        SimpleDateFormat("dd.MM HH:mm:ss", Locale.getDefault()).format(Date(t))

    fun install(ctx: Context) {
        val app = ctx.applicationContext
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching {
                val sw = StringWriter()
                e.printStackTrace(PrintWriter(sw))
                File(app.filesDir, FILE).writeText("${stamp()}  (${t.name})\n$sw")
            }
            prev?.uncaughtException(t, e)
        }
    }

    fun lastCrash(ctx: Context): String? =
        runCatching { File(ctx.filesDir, FILE).takeIf { it.exists() }?.readText() }.getOrNull()

    fun clear(ctx: Context) {
        runCatching { File(ctx.filesDir, FILE).delete() }
    }

    private fun reasonName(r: Int): String = when (r) {
        1 -> "Uygulama kendi kapandı"
        2 -> "Sinyalle öldürüldü"
        3 -> "Bellek azlığı (sistem kapattı)"
        4 -> "ÇÖKME (yakalanmayan hata)"
        5 -> "Native çökme"
        6 -> "ANR (yanıt vermedi)"
        7 -> "Başlatma hatası"
        8 -> "İzin değişti"
        9 -> "Aşırı kaynak kullanımı"
        10 -> "Kullanıcı kapattı"
        11 -> "Kullanıcı durdurdu (Zorla durdur)"
        12 -> "Bağımlılık öldü"
        13 -> "Diğer (sistem/pil yöneticisi)"
        14 -> "Dondurucu (arka plan)"
        15 -> "Paket güncellendi"
        16 -> "Paket durumu değişti"
        else -> "Bilinmiyor ($r)"
    }

    /** Son süreç sonlanma nedenleri (Android 11+). */
    fun exitReasons(ctx: Context): List<String> {
        if (Build.VERSION.SDK_INT < 30) return emptyList()
        return runCatching {
            val am = ctx.getSystemService(ActivityManager::class.java)
            am.getHistoricalProcessExitReasons(ctx.packageName, 0, 6).map {
                "${stamp(it.timestamp)}  ${reasonName(it.reason)}" + (it.description?.takeIf { d -> d.isNotBlank() }?.let { d -> " — $d" } ?: "")
            }
        }.getOrDefault(emptyList())
    }
}
