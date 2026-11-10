package com.example.dynamicisland

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import androidx.core.app.NotificationManagerCompat

/**
 * Sade erişilebilirlik servisi: yalnızca TYPE_NOTIFICATION_STATE_CHANGED dinler.
 * Bildirim erişimi (NotificationListener) açıksa çift bildirim olmaması için sessiz kalır;
 * yedek (fallback) görevi görür.
 */
class IslandAccessibilityService : AccessibilityService() {

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) return
        if (NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)) return
        val text = event.text.joinToString(" ").trim()
        if (text.isEmpty()) return
        IslandBus.post(IslandEvent.Notification(event.packageName?.toString().orEmpty(), "", text))
    }

    override fun onInterrupt() = Unit
}
