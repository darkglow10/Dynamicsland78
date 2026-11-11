package com.example.dynamicisland

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
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
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        onChanged?.invoke()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        onChanged?.invoke()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) return
        if (NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)) return
        val text = event.text.joinToString(" ").trim()
        if (text.isEmpty()) return
        IslandBus.post(IslandEvent.Notification(event.packageName?.toString().orEmpty(), "", text))
    }

    override fun onInterrupt() = Unit
}
