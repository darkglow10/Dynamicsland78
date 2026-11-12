package com.example.dynamicisland

import android.annotation.SuppressLint
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.ColorSpace
import android.graphics.PixelFormat
import android.graphics.Shader
import android.hardware.HardwareBuffer
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.view.WindowManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.NotificationCompat

/** Yakalanan son ekran karesi (GPU'da, kopyalamadan). */
class CaptureFrame(val shader: BitmapShader, val scale: Float)

object LiquidCapture {
    /** Her yeni karede değişir; çizim bunu okuyarak otomatik yenilenir. */
    var frame by mutableStateOf<CaptureFrame?>(null)
    var running by mutableStateOf(false)
}

/** Şeffaf, görünmez Activity: sadece "ekranı yakala" iznini ister ve servisi başlatır. */
class CapturePermissionActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mpm = getSystemService(MediaProjectionManager::class.java)
        @Suppress("DEPRECATION")
        startActivityForResult(mpm.createScreenCaptureIntent(), 77)
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 77 && resultCode == RESULT_OK && data != null) {
            val i = Intent(this, ScreenCaptureService::class.java)
                .putExtra(ScreenCaptureService.EXTRA_CODE, resultCode)
                .putExtra(ScreenCaptureService.EXTRA_DATA, data)
            startForegroundService(i)
        }
        finish()
    }
}

@SuppressLint("NewApi")
class ScreenCaptureService : Service() {
    companion object {
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        const val ACTION_STOP = "stop_capture"
        private const val SCALE = 0.5f   // yarı çözünürlük: cam efekti için yeterli, çok daha hafif
    }

    private val main = Handler(Looper.getMainLooper())
    private var thread: HandlerThread? = null
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private val held = ArrayDeque<Image>()
    private var lastTs = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    @Suppress("DEPRECATION")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopCapture(); stopSelf(); return START_NOT_STICKY }
        if (Build.VERSION.SDK_INT < 33) { stopSelf(); return START_NOT_STICKY }
        val code = intent?.getIntExtra(EXTRA_CODE, 0) ?: 0
        val data = intent?.getParcelableExtra<Intent>(EXTRA_DATA)
        if (data == null) { stopSelf(); return START_NOT_STICKY }

        // Android 14+: MediaProjection'dan ÖNCE foreground'a geçilmeli
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("capture", "Ekran yakalama", NotificationManager.IMPORTANCE_MIN))
        val n = NotificationCompat.Builder(this, "capture")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Liquid Glass gerçek zamanlı")
            .setOngoing(true).setPriority(NotificationCompat.PRIORITY_MIN).build()
        startForeground(2, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)

        runCatching { startCapture(code, data) }.onFailure { stopCapture(); stopSelf() }
        return START_NOT_STICKY
    }

    private fun startCapture(code: Int, data: Intent) {
        stopCapture()
        val mpm = getSystemService(MediaProjectionManager::class.java)
        val p = mpm.getMediaProjection(code, data)
        projection = p
        p.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { main.post { stopCapture(); stopSelf() } }
        }, main)

        val b = (getSystemService(WINDOW_SERVICE) as WindowManager).maximumWindowMetrics.bounds
        val w = (b.width() * SCALE).toInt().coerceAtLeast(2)
        val h = (b.height() * SCALE).toInt().coerceAtLeast(2)

        val t = HandlerThread("liquid-capture").also { it.start() }
        thread = t
        val handler = Handler(t.looper)

        val ir = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 4, HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE)
        reader = ir
        ir.setOnImageAvailableListener({ r -> onFrame(r) }, handler)

        display = p.createVirtualDisplay(
            "liquid", w, h, resources.displayMetrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, ir.surface, null, handler,
        )
        LiquidCapture.running = true
    }

    private fun onFrame(r: ImageReader) {
        val img = try { r.acquireLatestImage() } catch (_: Exception) { null } ?: return
        val now = SystemClock.uptimeMillis()
        if (now - lastTs < 14) { img.close(); return }   // ~60 FPS üst sınırı
        lastTs = now
        val hb = img.hardwareBuffer
        val bmp = if (hb != null) Bitmap.wrapHardwareBuffer(hb, ColorSpace.get(ColorSpace.Named.SRGB)) else null
        if (bmp == null) { img.close(); return }
        val shader = BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        synchronized(held) {
            held.addLast(img)
            // Ekranda kullanılan son birkaç kare açık kalır (üzerine yazılmasın)
            while (held.size > 3) runCatching { held.removeFirst().close() }
        }
        main.post { if (LiquidCapture.running) LiquidCapture.frame = CaptureFrame(shader, SCALE) }
    }

    private fun stopCapture() {
        LiquidCapture.running = false
        LiquidCapture.frame = null
        runCatching { display?.release() }; display = null
        runCatching { reader?.setOnImageAvailableListener(null, null) }
        synchronized(held) { while (held.isNotEmpty()) runCatching { held.removeFirst().close() } }
        runCatching { reader?.close() }; reader = null
        runCatching { projection?.stop() }; projection = null
        thread?.quitSafely(); thread = null
    }

    override fun onDestroy() { stopCapture(); super.onDestroy() }
}
