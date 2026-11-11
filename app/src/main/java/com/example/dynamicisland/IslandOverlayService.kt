package com.example.dynamicisland

import android.app.Dialog
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.ColorDrawable
import android.hardware.display.DisplayManager
import android.os.Build
import android.util.LruCache
import android.view.ContextThemeWrapper
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.ComposeView
import androidx.core.app.NotificationCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

class IslandOverlayService : LifecycleService(), SavedStateRegistryOwner {

    private val savedStateController = SavedStateRegistryController.create(this)
    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val ui = MutableStateFlow(IslandUiState())
    private lateinit var settings: StateFlow<IslandSettings>

    private lateinit var wm: WindowManager
    private var dialog: Dialog? = null
    private var composeView: ComposeView? = null
    private var collapseJob: Job? = null
    private var lastMedia: IslandContent.Media? = null
    private val iconCache = LruCache<String, ImageBitmap>(8)

    private var density = 1f
    private var cutCenterXOffsetPx = 0    // kamera deliğinin ekran merkezine göre yatay farkı
    private var cutCenterYPx = 0          // kamera deliğinin dikey merkezi
    private var curW = 1
    private var curH = 1

    private data class WinKey(val kind: Int, val expanded: Boolean, val s: IslandSettings)

    override fun onCreate() {
        savedStateController.performAttach()
        savedStateController.performRestore(null)
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        density = resources.displayMetrics.density
        settings = SettingsRepository(applicationContext).flow
            .stateIn(scope, SharingStarted.Eagerly, IslandSettings())
        startAsForeground()
        detectCutout()
        attachOverlay()
        scope.launch { IslandBus.events.collect(::handle) }
        // Pencere geometrisi yalnızca durum/ayar DEĞİŞİNCE güncellenir (kare başına değil)
        scope.launch {
            combine(ui, settings) { u, s -> WinKey(kindOf(u.content), u.expanded, s) }
                .distinctUntilChanged()
                .collectLatest { syncWindow(it) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        return START_STICKY
    }

    private fun dp(v: Float) = (v * density).roundToInt()
    private fun kindOf(c: IslandContent) = when (c) {
        IslandContent.Idle -> 0
        is IslandContent.Notif -> 1
        is IslandContent.Media -> 2
    }

    // ---------------------------------------------------------------- Olay mantığı
    private fun handle(e: IslandEvent) {
        when (e) {
            is IslandEvent.Notification -> scope.launch {
                val icon = iconFor(e.pkg)
                showTemporarily(IslandContent.Notif(e.pkg, e.title, e.text, icon), holdMs = 4_000)
            }
            is IslandEvent.MediaChanged -> onMedia(e.media)
        }
    }

    private fun onMedia(m: IslandContent.Media?) {
        if (m == lastMedia) return
        lastMedia = m
        val cur = ui.value
        when {
            cur.content is IslandContent.Notif -> Unit
            m == null || (!m.playing && !cur.expanded) -> { collapseJob?.cancel(); rest() }
            else -> ui.value = cur.copy(content = m)
        }
    }

    private fun showTemporarily(content: IslandContent, holdMs: Long) {
        ui.value = IslandUiState(content, expanded = true)
        scheduleCollapse(holdMs)
    }

    private fun scheduleCollapse(holdMs: Long) {
        collapseJob?.cancel()
        collapseJob = scope.launch { delay(holdMs); collapseAndRest() }
    }

    private suspend fun collapseAndRest() {
        ui.value = ui.value.copy(expanded = false)
        delay(settings.value.settleMs())
        rest()
    }

    private fun rest() {
        val m = lastMedia
        ui.value = if (m != null && m.playing) IslandUiState(m, false) else IslandUiState()
    }

    private fun onPillTap() {
        val cur = ui.value
        when (cur.content) {
            is IslandContent.Media ->
                if (cur.expanded) {
                    collapseJob?.cancel(); collapseJob = scope.launch { collapseAndRest() }
                } else {
                    ui.value = cur.copy(expanded = true)
                    scheduleCollapse(8_000)
                }
            is IslandContent.Notif -> { collapseJob?.cancel(); collapseJob = scope.launch { collapseAndRest() } }
            IslandContent.Idle -> Unit
        }
    }

    private fun onCommand(c: MediaCommand) {
        IslandBus.send(c)
        if (ui.value.expanded) scheduleCollapse(8_000)
    }

    private suspend fun iconFor(pkg: String): ImageBitmap? {
        iconCache.get(pkg)?.let { return it }
        val bmp = withContext(Dispatchers.Default) {
            runCatching { packageManager.getApplicationIcon(pkg).toBitmap(72, 72).asImageBitmap() }.getOrNull()
        }
        if (bmp != null) iconCache.put(pkg, bmp)
        return bmp
    }

    // ---------------------------------------------------------------- Pencere
    private fun detectCutout() {
        val metrics = wm.currentWindowMetrics
        val display = getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        val rect = (metrics.windowInsets.displayCutout ?: display?.cutout)?.boundingRectTop
        if (rect != null && !rect.isEmpty) {
            cutCenterXOffsetPx = rect.centerX() - metrics.bounds.width() / 2
            cutCenterYPx = rect.centerY()
        } else {
            cutCenterXOffsetPx = 0
            cutCenterYPx = dp(22f)
        }
    }

    private fun attachOverlay() {
        val view = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@IslandOverlayService)
            setViewTreeSavedStateRegistryOwner(this@IslandOverlayService)
            setContent {
                IslandRoot(state = ui, settingsFlow = settings, onTap = ::onPillTap, onCommand = ::onCommand)
            }
        }
        composeView = view

        // Dialog penceresi: Window.setBackgroundBlurRadius SADECE pencere sınırlarını bulanıklaştırır
        // (WindowManager FLAG_BLUR_BEHIND ise tüm ekranı bulanıklaştırıyordu).
        val d = Dialog(ContextThemeWrapper(this, android.R.style.Theme_Translucent_NoTitleBar))
        d.setCancelable(false)
        d.setCanceledOnTouchOutside(false)
        d.setContentView(view, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        d.window?.apply {
            setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            addFlags(
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
            )
            setGravity(Gravity.TOP or Gravity.CENTER_HORIZONTAL)
            setDecorFitsSystemWindows(false)
            decorView.setPadding(0, 0, 0, 0)
            decorView.setLayerType(View.LAYER_TYPE_HARDWARE, null)
            val lp = WindowManager.LayoutParams().also { it.copyFrom(attributes) }
            lp.width = 1; lp.height = 1; lp.x = 0; lp.y = 0
            lp.format = PixelFormat.TRANSLUCENT
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            attributes = lp
        }
        d.show()
        dialog = d
    }

    private fun setGeometry(w: Int, h: Int, x: Int, y: Int) {
        val win = dialog?.window ?: return
        val cur = win.attributes
        if (cur.width == w && cur.height == h && cur.x == x && cur.y == y) return
        // Yeni nesne: aynı nesneyi geri atamak WindowManager güncellemesini tetiklemez
        val lp = WindowManager.LayoutParams().also { it.copyFrom(cur) }
        lp.width = w; lp.height = h; lp.x = x; lp.y = y
        win.attributes = lp
        curW = w; curH = h
    }

    private fun setBlur(on: Boolean, s: IslandSettings) {
        if (Build.VERSION.SDK_INT < 31) return
        val r = if (on && s.glassEnabled && s.blurRadiusPx > 0 && wm.isCrossWindowBlurEnabled) s.blurRadiusPx else 0
        dialog?.window?.setBackgroundBlurRadius(r)
    }

    /**
     * Pencereyi SABİT piksel boyutuna getirir (WRAP_CONTENT yok): konum kayması ve
     * kare başına pencere yeniden ölçümü ortadan kalkar. Blur sadece pencere ile
     * ada aynı boyuttayken (animasyon bittiğinde) açılır; böylece sadece ada bölgesi bulanıklaşır.
     */
    private suspend fun syncWindow(k: WinKey) {
        val s = k.s
        val pad = dp(s.windowPadDp())                    // parıltı / zıplama taşması için pay
        val cw = dp(s.compactWidthDp); val ch = dp(s.compactHeightDp)
        val music = k.kind == 2
        val ew = dp(s.expandedWidthDp)
        val eh = dp(s.expandedHeightDp + if (music) MUSIC_EXTRA_DP else 0f)

        val screenW = wm.currentWindowMetrics.bounds.width()
        val baseX = cutCenterXOffsetPx + dp(s.offsetXDp)
        val y = ((cutCenterYPx - ch / 2).coerceAtLeast(0) + dp(s.offsetYDp) - pad).coerceAtLeast(-pad)
        val maxShift = ((screenW - ew - 2 * pad) / 2).coerceAtLeast(0)
        val xExp = baseX.coerceIn(-maxShift, maxShift)
        val settle = s.settleMs()
        val blurOk = pad == 0        // pay varsa pencere adadan büyük olur; o zaman blur kapalı tutulur

        when {
            k.kind == 0 -> { setBlur(false, s); setGeometry(1, 1, baseX, y) }
            k.expanded -> {
                setBlur(false, s)
                setGeometry(ew + 2 * pad, eh + 2 * pad, xExp, y)
                if (blurOk) { delay(settle); setBlur(true, s) }
            }
            else -> {
                setBlur(false, s)
                if (curW > cw + 2 * pad || curH > ch + 2 * pad) delay(settle)
                setGeometry(ew + 2 * pad, eh + 2 * pad, xExp, y)
                if (blurOk) setBlur(true, s)
            }
        }
    }

    private fun startAsForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("island", "Dynamic Island", NotificationManager.IMPORTANCE_MIN))
        val n = NotificationCompat.Builder(this, "island")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Dynamic Island çalışıyor")
            .setOngoing(true).setPriority(NotificationCompat.PRIORITY_MIN).build()
        startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
    }

    // ---------------------------------------------------------------- Temizlik
    override fun onDestroy() {
        collapseJob?.cancel()
        scope.cancel()
        composeView?.let { v ->
            v.disposeComposition()
            v.setViewTreeLifecycleOwner(null)
            v.setViewTreeSavedStateRegistryOwner(null)
        }
        runCatching { dialog?.dismiss() }
        dialog = null
        composeView = null
        iconCache.evictAll()
        super.onDestroy()
    }

}
