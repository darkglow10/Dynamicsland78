package com.example.dynamicisland

import android.app.Dialog
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
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
import kotlin.math.abs
import kotlin.math.roundToInt

private const val RESIZE_HIDE_MS = 80L   // pencere küçülürken içeriğin gizli kalma süresi

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

    // Konum otomatik düzeltme: pencere içeriği sistem tarafından kaydırılırsa ölçüp telafi ederiz
    // Şarkı sözleri
    private val lyrics = MutableStateFlow<LyricsState>(LyricsState.Idle)
    private val mediaState = MutableStateFlow<IslandContent.Media?>(null)
    private var lyricsKey: String? = null
    private var lyricsJob: Job? = null
    private var outsideView: ComposeView? = null
    private var outsideLp: WindowManager.LayoutParams? = null
    private var outsideWm: WindowManager? = null      // pencerenin eklendiği WindowManager (erişilebilirlik ya da servis)
    private var outsideDy = 0                         // sistemin pencereyi kaydırdığı miktar (kalibrasyon)
    private var outsideTries = 0
    private var outsideWantY = 0

    private var wantX = 0
    private var wantY = 0                 // adanın pencere üstünün olması gereken ekran Y'si
    private var calibDy = 0               // ölçülen kayma (px)
    private var calibTries = 0
    private val calibrateRunnable = Runnable { calibrate() }
    private val showAgain = Runnable { composeView?.animate()?.alpha(1f)?.setDuration(120)?.start() }
    private var resizeListener: View.OnLayoutChangeListener? = null

    private data class WinKey(val kind: Int, val expanded: Boolean, val lyr: Boolean, val s: IslandSettings)

    override fun onCreate() {
        savedStateController.performAttach()
        savedStateController.performRestore(null)
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        density = resources.displayMetrics.density
        settings = SettingsRepository(applicationContext).flow
            .stateIn(scope, SharingStarted.Eagerly, IslandSettings())
        // Arka plandan (örn. sistem START_STICKY ile yeniden başlatınca) foreground'a geçiş reddedilebilir;
        // istisna yakalanmazsa süreç çöker ve erişilebilirlik servisi "hatalı çalışıyor" olarak işaretlenir.
        if (!runCatching { startAsForeground() }.isSuccess) { stopSelf(); return }
        runCatching { detectCutout() }
        safeAttachOverlay()
        // Erişilebilirlik servisi açılıp kapanınca pencere türünü yeniden seç
        IslandAccessibilityService.onChanged = { runCatching { reattach() } }
        scope.launch { IslandBus.events.collect(::handle) }
        // Durdur → Başlat sonrası: zaten çalan müziği (değişmediği için yeniden gelmeyen) tekrar iste
        IslandNotificationListener.republish(this)
        // Bildirim dinleyicisi sistem tarafından yeniden bağlanırken zaman alabilir (özellikle uzun süre sonra):
        // müzik gelene kadar birkaç kez tekrar iste.
        scope.launch {
            var attempt = 0
            for (wait in longArrayOf(1_500, 3_000, 6_000, 12_000)) {
                delay(wait)
                if (lastMedia != null) break
                IslandNotificationListener.republish(this@IslandOverlayService, force = attempt++ >= 1)
            }
        }
        // Ayar değişince: söz aramasını, kapalı ada genişliğini ve dış söz penceresini güncelle
        scope.launch { settings.collect { s -> ensureLyrics(); update { it }; syncOutside(s) } }
        scope.launch { lyrics.collect { update { it } } }
        // Pencere geometrisi yalnızca durum/ayar DEĞİŞİNCE güncellenir (kare başına değil)
        scope.launch {
            combine(ui, settings) { u, s -> WinKey(kindOf(u.content), u.expanded, u.compactLyrics, s) }
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

    /** ui'yi günceller ve "kapalı adada söz gösterilsin mi" bayrağını yeniden hesaplar. */
    private fun update(f: (IslandUiState) -> IslandUiState) {
        val n = f(ui.value)
        val s = settings.value
        val want = s.lyricsEnabled && s.lyricsCompact && lyrics.value is LyricsState.Ready &&
            lastMedia?.playing == true && n.content is IslandContent.Media
        ui.value = n.copy(compactLyrics = want)
    }

    private fun onMedia(m: IslandContent.Media?) {
        if (m == lastMedia) return
        lastMedia = m
        mediaState.value = m
        ensureLyrics()
        val cur = ui.value
        when {
            cur.content is IslandContent.Notif -> update { it }
            m == null || (!m.playing && !cur.expanded) -> { collapseJob?.cancel(); rest() }
            else -> update { it.copy(content = m) }
        }
    }

    /** Parça (başlık/sanatçı/süre) değişince sözleri arar; özellik kapalıysa hiçbir istek atılmaz. */
    private fun ensureLyrics() {
        val s = settings.value
        val m = lastMedia
        val wanted = s.lyricsEnabled && (s.lyricsInPanel || s.lyricsCompact || s.lyricsOutside)
        if (!wanted || m == null || m.title.isBlank()) {
            lyricsJob?.cancel(); lyricsKey = null
            if (lyrics.value != LyricsState.Idle) lyrics.value = LyricsState.Idle
            return
        }
        val key = "${m.title}|${m.artist}|${m.durationMs / 1000}"
        if (key == lyricsKey) return
        lyricsKey = key
        lyricsJob?.cancel()
        LyricsRepository.cached(key)?.let { lyrics.value = it; return }
        lyrics.value = LyricsState.Loading
        lyricsJob = scope.launch {
            val r = LyricsRepository.fetch(key, m.title, m.artist, m.durationMs)
            if (lyricsKey == key) lyrics.value = r
        }
    }

    private fun showTemporarily(content: IslandContent, holdMs: Long) {
        update { IslandUiState(content, expanded = true) }
        scheduleCollapse(holdMs)
    }

    private fun scheduleCollapse(holdMs: Long) {
        collapseJob?.cancel()
        collapseJob = scope.launch { delay(holdMs); collapseAndRest() }
    }

    private suspend fun collapseAndRest() {
        update { it.copy(expanded = false) }
        delay(settings.value.settleMs())
        rest()
    }

    private fun rest() {
        val m = lastMedia
        update { if (m != null && m.playing) IslandUiState(m, false) else IslandUiState() }
    }

    private fun onPillTap() {
        val cur = ui.value
        when (cur.content) {
            is IslandContent.Media ->
                if (cur.expanded) {
                    collapseJob?.cancel(); collapseJob = scope.launch { collapseAndRest() }
                } else {
                    update { it.copy(expanded = true) }
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

    /** Panelin sağ alt köşesindeki yayın simgesi: sistemin ses / medya çıkışı paneli. */
    private fun openOutputPanel() {
        runCatching {
            startActivity(Intent(android.provider.Settings.Panel.ACTION_VOLUME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
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

    /**
     * Erişilebilirlik servisi bağlıysa ada TYPE_ACCESSIBILITY_OVERLAY ile çizilir: bu pencere türü
     * durum çubuğunun ÜSTÜNDE durur ve dokunuş alır. Değilse (ya da hata olursa) eski
     * TYPE_APPLICATION_OVERLAY'e döner; o zaman durum çubuğu bölgesinde dokunuş çalışmaz.
     */
    private fun attachOverlay() {
        val a11y = IslandAccessibilityService.instance
        if (a11y != null) {
            try {
                buildOverlay(a11y, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY)
                return
            } catch (e: Exception) {
                detachOverlay()
            }
        }
        buildOverlay(this, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
    }

    /** attachOverlay'i hiçbir hata dışarı sızmayacak şekilde çağırır. */
    private fun safeAttachOverlay() {
        runCatching { attachOverlay() }.onFailure {
            runCatching { detachOverlay() }
            runCatching { buildOverlay(this, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY) }
        }
    }

    private fun reattach() {
        runCatching { detachOverlay() }
        // Erişilebilirlik bağlantısı değişince dış söz penceresinin türü de değişir: yeniden kur.
        // Önce söz penceresi, sonra ada eklenir; böylece ada her zaman üstte kalır ve sözler adanın "içinden" çıkar.
        runCatching { detachOutside() }
        runCatching { syncOutside(settings.value) }
        safeAttachOverlay()
        scope.launch { syncWindow(WinKey(kindOf(ui.value.content), ui.value.expanded, ui.value.compactLyrics, settings.value)) }
    }

    private fun detachOverlay() {
        composeView?.removeCallbacks(calibrateRunnable)
        composeView?.let { v ->
            v.disposeComposition()
            v.setViewTreeLifecycleOwner(null)
            v.setViewTreeSavedStateRegistryOwner(null)
        }
        runCatching { dialog?.dismiss() }
        dialog = null
        composeView = null
        calibDy = 0; calibTries = 0
        curW = 1; curH = 1
    }

    private fun buildOverlay(host: Context, type: Int) {
        val view = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@IslandOverlayService)
            setViewTreeSavedStateRegistryOwner(this@IslandOverlayService)
            setContent {
                IslandRoot(
                    state = ui, settingsFlow = settings, lyricsFlow = lyrics,
                    onTap = ::onPillTap, onCommand = ::onCommand, onOutput = ::openOutputPanel,
                )
            }
        }
        composeView = view

        // Dialog penceresi: Window.setBackgroundBlurRadius SADECE pencere sınırlarını bulanıklaştırır
        // (WindowManager FLAG_BLUR_BEHIND ise tüm ekranı bulanıklaştırıyordu).
        val d = Dialog(ContextThemeWrapper(host, android.R.style.Theme_Translucent_NoTitleBar))
        d.setCancelable(false)
        d.setCanceledOnTouchOutside(false)
        d.setContentView(view, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        d.window?.apply {
            setType(type)
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
            lp.fitInsetsTypes = 0          // pencere çerçevesi durum çubuğuna göre kaymasın
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            attributes = lp
        }
        d.window?.decorView?.let { stripInsets(it) }   // show'dan ÖNCE: ilk inset dağıtımı da yutulur
        d.show()
        d.window?.decorView?.let { stripInsets(it) }   // show'dan SONRA: kalan dolguları sıfırla
        dialog = d
    }

    /** Dialog'un decor/içerik katmanlarına eklediği tüm sistem çubuğu dolgularını sıfırlar. */
    private fun stripInsets(dv: View) {
        fun zero() {
            dv.setPadding(0, 0, 0, 0)
            (dv as? ViewGroup)?.let { g ->
                for (i in 0 until g.childCount) g.getChildAt(i).setPadding(0, 0, 0, 0)
            }
            dv.findViewById<View>(android.R.id.content)?.setPadding(0, 0, 0, 0)
        }
        dv.setOnApplyWindowInsetsListener { _, _ ->
            zero()
            android.view.WindowInsets.CONSUMED
        }
        zero()
        dv.requestApplyInsets()
    }

    private fun setGeometry(w: Int, h: Int, x: Int, y: Int, recalibrate: Boolean = true) {
        val win = dialog?.window ?: return
        val big = w > 1 && h > 1
        wantX = x; wantY = y
        val ay = if (big) y - calibDy else y
        val ah = if (big) h + calibDy else h
        val cur = win.attributes
        if (cur.width == w && cur.height == ah && cur.x == x && cur.y == ay) return
        // Yeni nesne: aynı nesneyi geri atamak WindowManager güncellemesini tetiklemez
        val lp = WindowManager.LayoutParams().also { it.copyFrom(cur) }
        lp.width = w; lp.height = ah; lp.x = x; lp.y = ay
        // Pencere KÜÇÜLÜRKEN sistem eski kareyi bir an yanlış yerde gösterebiliyor (yana kayma):
        // o birkaç kare boyunca içeriği gizleyip hemen geri getiriyoruz.
        if (big && curW > 1 && w < curW) {
            composeView?.let { v ->
                v.animate().cancel()
                v.removeCallbacks(showAgain)
                resizeListener?.let { v.removeOnLayoutChangeListener(it) }
                v.alpha = 0f
                // Sistem yeni boyutu gerçekten uygulayınca (yerleşim değişince) kısa süre sonra göster;
                // böylece eski/yanlış konumdaki kareler görünmez ve ada yana kayarak gelmez.
                val l = object : View.OnLayoutChangeListener {
                    override fun onLayoutChange(
                        view: View, left: Int, top: Int, right: Int, bottom: Int,
                        oldLeft: Int, oldTop: Int, oldRight: Int, oldBottom: Int,
                    ) {
                        if (right - left == oldRight - oldLeft) return
                        view.removeOnLayoutChangeListener(this)
                        if (resizeListener === this) resizeListener = null
                        view.removeCallbacks(showAgain)
                        view.postDelayed(showAgain, RESIZE_HIDE_MS)
                    }
                }
                resizeListener = l
                v.addOnLayoutChangeListener(l)
                v.postDelayed(showAgain, 500)      // güvenlik: yerleşim değişmezse yine de göster
            }
        }
        win.attributes = lp
        curW = w; curH = h
        if (big) {
            if (recalibrate) calibTries = 0
            composeView?.let { it.removeCallbacks(calibrateRunnable); it.postDelayed(calibrateRunnable, 150) }
        }
    }

    /**
     * Ada gerçekte olması gereken yerde mi? Değilse (sistem içeriği aşağı kaydırdıysa)
     * pencereyi kayma kadar yukarı alıp yükseltir; böylece ada ne olursa olsun tam görünür.
     */
    private fun calibrate() {
        val v = composeView ?: return
        if (curW <= 1 || curH <= 1 || !v.isAttachedToWindow) return
        val loc = IntArray(2)
        v.getLocationOnScreen(loc)
        val dy = loc[1] - wantY
        if (abs(dy) <= 2 || calibTries >= 3) return
        calibTries++
        calibDy = (calibDy + dy).coerceIn(-300, 600)
        setGeometry(curW, curH, wantX, wantY, recalibrate = false)
    }

    /** Boşta görünen adaya dokunuş yakalamasın: durum çubuğu altından normal çalışsın. */
    private fun setTouchable(on: Boolean) {
        val win = dialog?.window ?: return
        val flag = WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        val blocked = (win.attributes.flags and flag) != 0
        if (on && blocked) win.clearFlags(flag)
        else if (!on && !blocked) win.addFlags(flag)
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
        val cw = dp(compactWidthFor(s, k.lyr && k.kind == 2)); val ch = dp(s.compactHeightDp)
        val music = k.kind == 2
        val ew = dp(s.expandedWidthDp)
        val eh = dp(s.expandedHeightDp + if (music) s.musicExtraDp() else 0f)

        val screenW = wm.currentWindowMetrics.bounds.width()
        val baseX = cutCenterXOffsetPx + dp(s.offsetXDp)
        val y = (cutCenterYPx - ch / 2).coerceAtLeast(0) + dp(s.offsetYDp) - pad
        val maxShift = ((screenW - ew - 2 * pad) / 2).coerceAtLeast(0)
        val xExp = baseX.coerceIn(-maxShift, maxShift)
        val settle = s.settleMs()
        val blurOk = pad == 0        // pay varsa pencere adadan büyük olur; o zaman blur kapalı tutulur

        setTouchable(k.kind != 0)
        when {
            k.kind == 0 && s.showIdle -> {
                // Boşta da görünür: küçük ada boyutunda, dokunuşsuz
                setBlur(false, s)
                if (curW > cw + 2 * pad || curH > ch + 2 * pad) delay(settle)
                setGeometry(cw + 2 * pad, ch + 2 * pad, baseX, y)
                if (blurOk) setBlur(true, s)
            }
            k.kind == 0 -> { setBlur(false, s); setGeometry(1, 1, baseX, y) }
            k.expanded -> {
                setBlur(false, s)
                setGeometry(ew + 2 * pad, eh + 2 * pad, xExp, y)
                if (blurOk) { delay(settle); setBlur(true, s) }
            }
            else -> {
                setBlur(false, s)
                if (curW > cw + 2 * pad || curH > ch + 2 * pad) delay(settle)
                setGeometry(cw + 2 * pad, ch + 2 * pad, baseX, y)
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
    // ---------------------------------------------------------------- Adanın dışındaki şarkı sözleri
    /** Ayrı, dokunmatik olmayan bir overlay penceresi: sözler adayı açmadan da görünür. */
    private val outsideOrigin = kotlinx.coroutines.flow.MutableStateFlow(LyricOrigin())

    private fun syncOutside(s: IslandSettings) {
        if (!(s.lyricsEnabled && s.lyricsOutside)) { detachOutside(); return }
        val pad = dp(24f)
        val fs = resources.configuration.fontScale
        val w = dp(s.lyricsOutsideWidthDp) + 2 * pad
        val contentH = dp(s.lyricsOutsideSizeSp * fs * 1.3f * s.lyricsOutsideLines)
        val x = dp(s.lyricsOutsideXDp)
        var y = dp(s.lyricsOutsideYDp) - pad
        var h = contentH + 2 * pad

        // "Adadan çıkış" animasyonları: sözler adadan başlayıp yerine gelir. Pencere adaya kadar yukarı uzatılır
        // (aksi halde pencere sınırında kesilirdi); söz alanı altta ve eski konumunda kalır.
        val islandCy = cutCenterYPx + dp(s.offsetYDp)
        val textCy = dp(s.lyricsOutsideYDp) + contentH / 2
        if (s.lyricsFromIsland()) {
            val wantTop = (islandCy - dp(10f)).coerceAtLeast(0)
            val extra = (y - wantTop).coerceAtLeast(0)
            y -= extra; h += extra
            val screenW = wm.currentWindowMetrics.bounds.width()
            outsideOrigin.value = LyricOrigin(
                dx = (cutCenterXOffsetPx + dp(s.offsetXDp) - x).toFloat(),
                dy = (islandCy - textCy).toFloat(),
                // Adanın ekran üzerindeki merkezi: söz alanının GERÇEK konumu ölçülüp farkı oradan hesaplanır
                ix = screenW / 2f + cutCenterXOffsetPx + dp(s.offsetXDp),
                iy = islandCy.toFloat(),
            )
        } else {
            outsideOrigin.value = LyricOrigin()
        }

        outsideWantY = y
        val existing = outsideView
        val lp = outsideLp
        if (existing != null && lp != null) {
            val ey = y - outsideDy; val eh = h + outsideDy
            if (lp.width != w || lp.height != eh || lp.x != x || lp.y != ey) {
                lp.width = w; lp.height = eh; lp.x = x; lp.y = ey
                runCatching { (outsideWm ?: wm).updateViewLayout(existing, lp) }
                scheduleOutsideCalibrate()
            }
            return
        }

        val view = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@IslandOverlayService)
            setViewTreeSavedStateRegistryOwner(this@IslandOverlayService)
            setContent { OutsideLyricsRoot(ui, settings, lyrics, mediaState, outsideOrigin) }
        }
        fun params(type: Int) = WindowManager.LayoutParams(
            w, h, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            this.x = x; this.y = y
            fitInsetsTypes = 0          // durum çubuğu payı kadar aşağı itilmesin
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }

        // Normal uygulama overlay'i durum çubuğunun ALTINDA kalır: sözler adaya (durum çubuğu hizası) hiç ulaşamazdı.
        // Erişilebilirlik overlay'i ise adanın kendisi gibi durum çubuğunun üstünde çizilir.
        var host: WindowManager = wm
        var lp2 = params(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        var added = false
        val a11y = IslandAccessibilityService.instance
        if (a11y != null) {
            runCatching {
                val h2 = a11y.getSystemService(WINDOW_SERVICE) as WindowManager
                val p2 = params(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY)
                h2.addView(view, p2)
                host = h2; lp2 = p2; added = true
            }
        }
        if (!added) added = runCatching { wm.addView(view, lp2) }.isSuccess

        if (added) {
            outsideView = view; outsideLp = lp2; outsideWm = host
            outsideDy = 0
            scheduleOutsideCalibrate()
            // Ada (varsa) en üstte kalsın: yeni eklenen söz penceresi adanın üstüne binmesin
            if (composeView != null) bringIslandToFront()
        } else {
            view.disposeComposition()
        }
    }

    /** Adayı söz penceresinden sonra yeniden ekler (ikisi de erişilebilirlik overlay'i ise sonradan eklenen üstte olur). */
    private fun bringIslandToFront() {
        runCatching { detachOverlay() }
        safeAttachOverlay()
        scope.launch { syncWindow(WinKey(kindOf(ui.value.content), ui.value.expanded, ui.value.compactLyrics, settings.value)) }
    }

    /** Sistem pencereyi istediğimizden farklı bir yere koyduysa (durum çubuğu payı vb.) farkı kadar geri çeker. */
    private val outsideCalibrate = object : Runnable {
        override fun run() {
            val v = outsideView ?: return
            val lp = outsideLp ?: return
            val host = outsideWm ?: return
            if (!v.isAttachedToWindow) return
            val loc = IntArray(2)
            v.getLocationOnScreen(loc)
            val dy = loc[1] - outsideWantY
            if (abs(dy) <= 2 || outsideTries >= 3) return
            outsideTries++
            outsideDy += dy
            lp.y -= dy; lp.height += dy
            runCatching { host.updateViewLayout(v, lp) }
            v.postDelayed(this, 200)
        }
    }

    private fun scheduleOutsideCalibrate() {
        outsideTries = 0
        outsideView?.let { it.removeCallbacks(outsideCalibrate); it.postDelayed(outsideCalibrate, 250) }
    }

    private fun detachOutside() {
        val v = outsideView ?: return
        v.removeCallbacks(outsideCalibrate)
        runCatching { (outsideWm ?: wm).removeView(v) }
        v.disposeComposition()
        v.setViewTreeLifecycleOwner(null)
        v.setViewTreeSavedStateRegistryOwner(null)
        outsideView = null; outsideLp = null; outsideWm = null
        outsideDy = 0
    }

    override fun onDestroy() {
        IslandAccessibilityService.onChanged = null
        lyricsJob?.cancel()
        detachOutside()
        collapseJob?.cancel()
        scope.cancel()
        detachOverlay()
        iconCache.evictAll()
        super.onDestroy()
    }

}
