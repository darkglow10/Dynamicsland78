package com.example.dynamicisland

import android.app.Notification
import android.content.ComponentName
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class IslandNotificationListener : NotificationListenerService() {

    private val msm by lazy { getSystemService(MediaSessionManager::class.java) }
    private var controller: MediaController? = null
    private var lastPublished: IslandContent.Media? = null
    private var scope: CoroutineScope? = null

    // Albüm kapağı önbelleği: aynı parça için tekrar tekrar ölçeklenmez
    private var artKey: String? = null
    private var art: ImageBitmap? = null
    private var artColor: Int = 0

    private val controllerCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = publishMedia()
        override fun onPlaybackStateChanged(state: PlaybackState?) = publishMedia()
        override fun onSessionDestroyed() { bind(null) }
    }
    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { bind(it) }

    override fun onListenerConnected() {
        scope?.cancel()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).also { sc ->
            sc.launch { IslandBus.commands.collect(::handleCommand) }
        }
        val cn = ComponentName(this, IslandNotificationListener::class.java)
        msm.addOnActiveSessionsChangedListener(sessionsListener, cn)
        bind(msm.getActiveSessions(cn))
    }

    override fun onListenerDisconnected() = release()
    override fun onDestroy() { release(); super.onDestroy() }

    private fun release() {
        scope?.cancel(); scope = null
        runCatching { msm.removeOnActiveSessionsChangedListener(sessionsListener) }
        controller?.unregisterCallback(controllerCallback)
        controller = null
        lastPublished = null
        artKey = null; art = null; artColor = 0
    }

    private fun handleCommand(cmd: MediaCommand) {
        val c = controller ?: return
        val tc = c.transportControls
        when (cmd) {
            MediaCommand.PlayPause ->
                if (c.playbackState?.state == PlaybackState.STATE_PLAYING) tc.pause() else tc.play()
            MediaCommand.Next -> tc.skipToNext()
            MediaCommand.Previous -> tc.skipToPrevious()
            is MediaCommand.SeekTo -> tc.seekTo(cmd.ms)
        }
    }

    private fun bind(list: List<MediaController>?) {
        val next = list?.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: list?.firstOrNull()
        if (next?.sessionToken != controller?.sessionToken) {
            controller?.unregisterCallback(controllerCallback)
            controller = next
            next?.registerCallback(controllerCallback)
        }
        publishMedia()
    }

    private fun publishMedia() {
        val c = controller
        val md = c?.metadata
        val ps = c?.playbackState
        val media = if (c == null || md == null) null else IslandContent.Media(
            title = md.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty(),
            artist = md.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty(),
            playing = ps?.state == PlaybackState.STATE_PLAYING,
            durationMs = md.getLong(MediaMetadata.METADATA_KEY_DURATION).coerceAtLeast(0L),
            positionMs = ps?.position?.coerceAtLeast(0L) ?: 0L,
            speed = ps?.playbackSpeed ?: 1f,
            updatedAt = ps?.lastPositionUpdateTime?.takeIf { it > 0 } ?: SystemClock.elapsedRealtime(),
            art = resolveArt(md),
            artColor = artColor,
        )
        if (media == lastPublished) return
        lastPublished = media
        IslandBus.post(IslandEvent.MediaChanged(media))
    }

    // ---------------------------------------------------------------- Albüm kapağı
    private fun resolveArt(md: MediaMetadata): ImageBitmap? {
        // 1) Doğrudan bitmap veren uygulamalar (Spotify, YouTube Music vb.)
        val bmp = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: md.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        if (bmp != null) {
            val key = "b${System.identityHashCode(bmp)}"
            if (key != artKey) {
                artKey = key
                val small = bmp.scaledMax(160)
                art = small.asImageBitmap()
                artColor = dominantColor(small)
            }
            return art
        }
        // 2) Sadece URI veren uygulamalar: arka planda yükle, gelince yeniden yayınla
        val uri = md.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI)
            ?: md.getString(MediaMetadata.METADATA_KEY_ART_URI)
            ?: md.getString(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI)
        if (uri != null) {
            if (uri != artKey) { artKey = uri; art = null; artColor = 0; loadArtAsync(uri) }
            return art
        }
        artKey = null; art = null; artColor = 0
        return null
    }

    private fun loadArtAsync(uri: String) {
        scope?.launch {
            val decoded = withContext(Dispatchers.IO) { decodeUri(uri)?.let { it to dominantColor(it) } }
            if (decoded != null && artKey == uri) {
                art = decoded.first.asImageBitmap()
                artColor = decoded.second
                publishMedia()
            }
        }
    }

    private fun decodeUri(uri: String): Bitmap? = runCatching {
        val u = Uri.parse(uri)
        if (u.scheme == "content" || u.scheme == "file" || u.scheme == "android.resource") {
            contentResolver.openInputStream(u)?.use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = 2 })
            }?.scaledMax(160)
        } else null
    }.getOrNull()

    /** Kapaktaki en baskın CANLI rengi bulur (ton kovalarına doygunluk x parlaklık ağırlığıyla oy verilir). 0 = bulunamadı. */
    private fun dominantColor(src: Bitmap): Int {
        val soft = runCatching { src.copy(Bitmap.Config.ARGB_8888, false) }.getOrNull() ?: return 0
        val n = 24
        val bmp = runCatching { Bitmap.createScaledBitmap(soft, n, n, true) }.getOrNull() ?: return 0
        val px = IntArray(n * n)
        bmp.getPixels(px, 0, n, 0, 0, n, n)
        val wSum = FloatArray(12); val hSum = FloatArray(12); val sSum = FloatArray(12); val vSum = FloatArray(12)
        val hsv = FloatArray(3)
        for (c in px) {
            android.graphics.Color.colorToHSV(c, hsv)
            if (hsv[1] < 0.2f || hsv[2] < 0.2f) continue          // gri / çok koyu pikselleri atla
            val w = hsv[1] * hsv[2]
            val b = (hsv[0] / 30f).toInt().coerceIn(0, 11)
            wSum[b] += w; hSum[b] += w * hsv[0]; sSum[b] += w * hsv[1]; vSum[b] += w * hsv[2]
        }
        var best = -1; var bw = 0f
        for (i in 0 until 12) if (wSum[i] > bw) { bw = wSum[i]; best = i }
        if (best < 0 || bw < 1.5f) return 0
        val out = floatArrayOf(
            hSum[best] / bw,
            (sSum[best] / bw).coerceIn(0.45f, 1f),
            (vSum[best] / bw).coerceIn(0.6f, 1f),
        )
        return android.graphics.Color.HSVToColor(out)
    }

    private fun Bitmap.scaledMax(max: Int): Bitmap {
        val m = maxOf(width, height)
        if (m <= max) return this
        val f = max.toFloat() / m
        return runCatching {
            Bitmap.createScaledBitmap(this, (width * f).toInt().coerceAtLeast(1), (height * f).toInt().coerceAtLeast(1), true)
        }.getOrDefault(this)
    }

    // ---------------------------------------------------------------- Bildirimler
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val n = sbn.notification
        if (sbn.packageName == packageName || sbn.isOngoing ||
            n.flags and Notification.FLAG_GROUP_SUMMARY != 0 ||
            n.category == Notification.CATEGORY_TRANSPORT ||
            n.category == Notification.CATEGORY_PROGRESS
        ) return
        val ex = n.extras
        val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = ex.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        if (title.isBlank() && text.isBlank()) return
        IslandBus.post(IslandEvent.Notification(sbn.packageName, title, text))
    }
}
