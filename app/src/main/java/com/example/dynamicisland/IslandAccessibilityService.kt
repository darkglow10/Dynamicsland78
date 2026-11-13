package com.example.dynamicisland

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import androidx.core.app.NotificationManagerCompat

/**
 * Üç iş yapar:
 * 1) Adanın penceresine ev sahipliği yapar: TYPE_ACCESSIBILITY_OVERLAY durum çubuğunun üstünde
 *    durur ve dokunuş alır (normal uygulama overlay'i durum çubuğunun altında kalır).
 * 2) Yedek bildirim olayı: yalnızca TYPE_NOTIFICATION_STATE_CHANGED dinler. Bildirim erişimi
 *    (NotificationListener) açıksa çift bildirim olmaması için sessiz kalır.
 * 3) Google Asistan / Gemini algılama: asistan penceresi açılınca / kapanınca [IslandEvent.AssistantChanged] yayınlar.
 */
class IslandAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile var instance: IslandAccessibilityService? = null
        /** Servis bağlanınca / kopunca overlay servisine haber verir (ana thread). */
        @Volatile var onChanged: (() -> Unit)? = null

        /** Asistan / Gemini arayüzünü barındıran paketler. Cihazında başka bir asistan varsa buraya ekle. */
        private val ASSISTANT_PKGS = setOf(
            "com.google.android.googlequicksearchbox",   // Google uygulaması (Asistan + Gemini kaplaması)
            "com.google.android.apps.bard",              // Gemini uygulaması
            "com.google.android.apps.googleassistant",
        )
        /** Asistan açıkken de olay üretebilen, asistanın kapandığı anlamına GELMEYEN paketler. */
        private val IGNORED_PKGS = setOf("com.android.systemui")
    }

    private val handler = Handler(Looper.getMainLooper())
    private var assistantOn = false
    private var eventSaysOn = false      // pencere listesi alınamazsa olay tabanlı yedek

    private val check = object : Runnable {
        override fun run() {
            runCatching {
                setAssistant(assistantVisible())
                // Asistan kapanırken her zaman olay gelmeyebilir: açıkken kısa aralıkla kontrol et
                if (assistantOn) handler.postDelayed(this, 400)
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        // Pencere listesini okuyabilmek için (XML'e ek olarak, çalışma anında da): asistan penceresi bununla görülür
        runCatching {
            serviceInfo?.let { info ->
                info.flags = info.flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
                serviceInfo = info
            }
        }
        instance = this
        notifyChanged()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        stopAssistantWatch()
        instance = null
        notifyChanged()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        stopAssistantWatch()
        instance = null
        super.onDestroy()
    }

    private fun stopAssistantWatch() {
        handler.removeCallbacks(check)
        eventSaysOn = false
        runCatching { setAssistant(false) }
    }

    /**
     * Overlay servisinin geri çağrısında oluşan HİÇBİR hata buraya sızmamalı: erişilebilirlik servisi ile
     * overlay servisi aynı süreçte çalışır; yakalanmayan tek bir istisna süreci çökertir ve sistem
     * erişilebilirlik servisini "Bu hizmet hatalı çalışıyor / Çalışmıyor" diye kalıcı işaretler.
     */
    private fun notifyChanged() {
        runCatching { onChanged?.invoke() }
    }

    private fun setAssistant(on: Boolean) {
        if (on == assistantOn) return
        assistantOn = on
        IslandBus.post(IslandEvent.AssistantChanged(on))
    }

    /** Ekranda asistan penceresi var mı? Önce pencere listesine bakar; alınamazsa son pencere olaylarına güvenir. */
    private fun assistantVisible(): Boolean {
        val ws = runCatching { windows }.getOrNull()
        if (!ws.isNullOrEmpty()) {
            // Yalnızca asistanın KAPLAMA penceresi sayılır (alttaki "Gemini'a sorun" barı). Google / Gemini uygulaması
            // normal bir uygulama olarak açıldığında penceresi TYPE_APPLICATION olur ve sayılmaz.
            return ws.any { w ->
                w.type != AccessibilityWindowInfo.TYPE_APPLICATION &&
                    runCatching { w.root?.packageName?.toString()?.let { it in ASSISTANT_PKGS } == true }.getOrDefault(false)
            }
        }
        return eventSaysOn
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        runCatching {
            val e = event ?: return
            when (e.eventType) {
                AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED -> {
                    if (NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)) return
                    val text = e.text.joinToString(" ").trim()
                    if (text.isEmpty()) return
                    IslandBus.post(IslandEvent.Notification(e.packageName?.toString().orEmpty(), "", text))
                }
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, AccessibilityEvent.TYPE_WINDOWS_CHANGED -> {
                    val pkg = e.packageName?.toString()
                    if (e.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && pkg != null) {
                        // Yedek (pencere listesi yokken): ekran sınıfı bir Activity ise normal uygulama açılmıştır, asistan değil
                        if (pkg in ASSISTANT_PKGS) eventSaysOn = e.className?.contains("Activity") != true
                        else if (pkg != packageName && pkg !in IGNORED_PKGS) eventSaysOn = false
                    }
                    // Olay yağmurunu tek kontrole indir
                    handler.removeCallbacks(check)
                    handler.postDelayed(check, 120)
                }
            }
        }
    }

    override fun onInterrupt() = Unit
}
