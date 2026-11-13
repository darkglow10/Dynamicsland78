package com.example.dynamicisland

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.accessibilityservice.AccessibilityServiceInfo
import androidx.core.app.NotificationManagerCompat

/**
 * İki iş yapar:
 * 1) Adanın penceresine ev sahipliği yapar: TYPE_ACCESSIBILITY_OVERLAY durum çubuğunun üstünde
 *    durur ve dokunuş alır (normal uygulama overlay'i durum çubuğunun altında kalır).
 * 2) Yedek bildirim olayı: yalnızca TYPE_NOTIFICATION_STATE_CHANGED dinler. Bildirim erişimi
 *    (NotificationListener) açıksa çift bildirim olmaması için sessiz kalır.
 */
class IslandAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile var instance: IslandAccessibilityService? = null
        /** Servis bağlanınca / kopunca overlay servisine haber verir (ana thread). */
        @Volatile var onChanged: (() -> Unit)? = null

        /** Asistan / Gemini penceresini barındıran paketler. */
        private val ASSISTANT_PKGS = setOf(
            "com.google.android.googlequicksearchbox",
            "com.google.android.apps.bard",
            "com.google.android.apps.googleassistant",
        )
        private const val TAG = "IslandAssistant"
    }

    private val handler = Handler(Looper.getMainLooper())
    private var assistantOn = false
    private val checkRunnable = Runnable { checkAssistant() }

    /**
     * Ekranda Google uygulamasına ait, tam ekran OLMAYAN bir pencere varsa asistan/Gemini çubuğu açıktır.
     * (Tam ekran Google uygulaması ya da Discover asistan sayılmaz.) Pencere bilgisi için
     * erişilebilirlik yapılandırmasında canRetrieveWindowContent ve flagRetrieveInteractiveWindows gerekir.
     */
    private fun checkAssistant() {
        runCatching {
            val screenH = resources.displayMetrics.heightPixels
            val r = Rect()
            var imeVisible = false
            var googleFull = false        // Google uygulamasının kendisi (tam ekran) açık mı
            var smallSystem = false       // sistem türünde küçük Google penceresi (asistan çubuğu)
            var smallApp = false          // uygulama türünde küçük Google penceresi (asistan çubuğu ya da metin seçim menüsü)
            for (w in windows) {
                if (w.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD) imeVisible = true
                val pkg = w.root?.packageName?.toString() ?: continue
                if (pkg !in ASSISTANT_PKGS) continue
                w.getBoundsInScreen(r)
                val full = r.height() >= screenH * 0.60f
                if (full) googleFull = true
                else if (w.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_SYSTEM) smallSystem = true
                else smallApp = true
                if (!assistantOn) Log.d(TAG, "pencere: pkg=$pkg type=${w.type} full=$full bounds=$r ime=$imeVisible")
            }
            // Google uygulamasında yazı yazarken / kelime seçerken (klavye açık ya da uygulama tam ekran) tetiklenmesin.
            // Zaten açıksa klavye açılması (Gemini'a yazmak) animasyonu kapatmaz.
            val found = (smallSystem || (smallApp && !googleFull)) && (assistantOn || !imeVisible)
            if (found != assistantOn) {
                assistantOn = found
                IslandBus.post(IslandEvent.AssistantChanged(found))
            }
        }
    }

    private fun scheduleCheck() {
        handler.removeCallbacks(checkRunnable)
        handler.postDelayed(checkRunnable, 120)
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        // XML'de yoksa da pencere olaylarını iste (yetki yine XML'deki canRetrieveWindowContent'ten gelir)
        runCatching {
            serviceInfo = serviceInfo?.apply {
                eventTypes = eventTypes or AccessibilityEvent.TYPE_WINDOWS_CHANGED or AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            }
        }
        notifyChanged()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        handler.removeCallbacks(checkRunnable)
        if (assistantOn) { assistantOn = false; IslandBus.post(IslandEvent.AssistantChanged(false)) }
        notifyChanged()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        handler.removeCallbacks(checkRunnable)
        instance = null
        super.onDestroy()
    }

    /**
     * Overlay servisinin geri çağrısında oluşan HİÇBİR hata buraya sızmamalı: erişilebilirlik servisi ile
     * overlay servisi aynı süreçte çalışır; yakalanmayan tek bir istisna süreci çökertir ve sistem
     * erişilebilirlik servisini "Bu hizmet hatalı çalışıyor / Çalışmıyor" diye kalıcı işaretler.
     */
    private fun notifyChanged() {
        runCatching { onChanged?.invoke() }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        runCatching {
            val type = event?.eventType
            if (type == AccessibilityEvent.TYPE_WINDOWS_CHANGED || type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                scheduleCheck(); return
            }
            if (type != AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) return
            if (NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)) return
            val text = event.text.joinToString(" ").trim()
            if (text.isEmpty()) return
            IslandBus.post(IslandEvent.Notification(event.packageName?.toString().orEmpty(), "", text))
        }
    }

    override fun onInterrupt() = Unit
}
