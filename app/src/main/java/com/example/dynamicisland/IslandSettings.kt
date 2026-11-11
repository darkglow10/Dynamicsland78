package com.example.dynamicisland

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

const val GLOW_PAD_DP = 14f     // parıltı için pencere kenar boşluğu
const val BOUNCE_PAD_DP = 16f   // "Zıplamalı" animasyonun taşma payı

@Immutable
data class IslandSettings(
    // Kapalı (küçük) hal
    val compactWidthDp: Float = 120f,
    val compactHeightDp: Float = 32f,
    // Açık hal
    val expandedWidthDp: Float = 340f,
    val expandedHeightDp: Float = 84f,
    // Konum ince ayarı
    val offsetXDp: Float = 0f,
    val offsetYDp: Float = 0f,
    // Renk
    val accentArgb: Int = 0xFF0A84FF.toInt(),
    val dynamicColor: Boolean = true,        // albüm kapağından renk
    // Liquid Glass
    val glassEnabled: Boolean = false,
    val glassAlpha: Float = 0.55f,
    val blurRadiusPx: Int = 40,
    // Parıltı
    val glowEnabled: Boolean = true,
    val glowIntensity: Float = 0.6f,
    // Çerçeve
    val borderEnabled: Boolean = false,
    val borderColorArgb: Int = 0xFFFFFFFF.toInt(),
    val borderWidthDp: Float = 1f,
    // Animasyon
    val animEnabled: Boolean = true,
    val animStyle: Int = 0,                  // 0 Yaylı, 1 Yumuşak, 2 Hızlı, 3 Zıplamalı, 4 Sıvı
    // Görünürlük
    val showIdle: Boolean = false,           // müzik/bildirim yokken de ada görünsün
) {
    /** Pencerenin ada etrafında bırakacağı pay (parıltı / zıplama taşması için). */
    fun windowPadDp(): Float = maxOf(
        if (glowEnabled) GLOW_PAD_DP else 0f,
        if (animEnabled && animStyle == 3) BOUNCE_PAD_DP else 0f,
    )

    /** Geçiş animasyonunun yaklaşık bitiş süresi (pencere boyutunu ona göre değiştiririz). */
    fun settleMs(): Long = if (!animEnabled) 60L else when (animStyle) {
        0 -> 520L; 1 -> 560L; 2 -> 300L; 3 -> 750L; else -> 650L
    }
}

private val Context.islandStore by preferencesDataStore(name = "island_settings")

class SettingsRepository(context: Context) {
    private val store = context.applicationContext.islandStore
    private object K {
        val CW = floatPreferencesKey("cw"); val CH = floatPreferencesKey("chh")
        val W = floatPreferencesKey("w"); val H = floatPreferencesKey("h")
        val OX = floatPreferencesKey("ox"); val OY = floatPreferencesKey("oy")
        val COLOR = intPreferencesKey("color"); val DYN = booleanPreferencesKey("dyn")
        val GLASS = booleanPreferencesKey("glass"); val ALPHA = floatPreferencesKey("alpha"); val BLUR = intPreferencesKey("blur")
        val GLOW = booleanPreferencesKey("glow"); val GLOW_I = floatPreferencesKey("glowI")
        val BORDER = booleanPreferencesKey("border"); val BORDER_C = intPreferencesKey("borderC"); val BORDER_W = floatPreferencesKey("borderW")
        val ANIM = booleanPreferencesKey("animOn"); val ANIM_S = intPreferencesKey("animStyle")
        val IDLE = booleanPreferencesKey("idle")
    }

    val flow: Flow<IslandSettings> = store.data.map { p ->
        val d = IslandSettings()
        IslandSettings(
            compactWidthDp = p[K.CW] ?: d.compactWidthDp,
            compactHeightDp = p[K.CH] ?: d.compactHeightDp,
            expandedWidthDp = p[K.W] ?: d.expandedWidthDp,
            expandedHeightDp = p[K.H] ?: d.expandedHeightDp,
            offsetXDp = p[K.OX] ?: d.offsetXDp,
            offsetYDp = p[K.OY] ?: d.offsetYDp,
            accentArgb = p[K.COLOR] ?: d.accentArgb,
            dynamicColor = p[K.DYN] ?: d.dynamicColor,
            glassEnabled = p[K.GLASS] ?: d.glassEnabled,
            glassAlpha = p[K.ALPHA] ?: d.glassAlpha,
            blurRadiusPx = p[K.BLUR] ?: d.blurRadiusPx,
            glowEnabled = p[K.GLOW] ?: d.glowEnabled,
            glowIntensity = p[K.GLOW_I] ?: d.glowIntensity,
            borderEnabled = p[K.BORDER] ?: d.borderEnabled,
            borderColorArgb = p[K.BORDER_C] ?: d.borderColorArgb,
            borderWidthDp = p[K.BORDER_W] ?: d.borderWidthDp,
            animEnabled = p[K.ANIM] ?: d.animEnabled,
            animStyle = p[K.ANIM_S] ?: d.animStyle,
            showIdle = p[K.IDLE] ?: d.showIdle,
        )
    }.distinctUntilChanged()

    suspend fun save(s: IslandSettings) {
        store.edit {
            it[K.CW] = s.compactWidthDp; it[K.CH] = s.compactHeightDp
            it[K.W] = s.expandedWidthDp; it[K.H] = s.expandedHeightDp
            it[K.OX] = s.offsetXDp; it[K.OY] = s.offsetYDp
            it[K.COLOR] = s.accentArgb; it[K.DYN] = s.dynamicColor
            it[K.GLASS] = s.glassEnabled; it[K.ALPHA] = s.glassAlpha; it[K.BLUR] = s.blurRadiusPx
            it[K.GLOW] = s.glowEnabled; it[K.GLOW_I] = s.glowIntensity
            it[K.BORDER] = s.borderEnabled; it[K.BORDER_C] = s.borderColorArgb; it[K.BORDER_W] = s.borderWidthDp
            it[K.ANIM] = s.animEnabled; it[K.ANIM_S] = s.animStyle
            it[K.IDLE] = s.showIdle
        }
    }
}
