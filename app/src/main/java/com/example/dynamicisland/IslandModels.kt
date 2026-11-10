package com.example.dynamicisland

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

@Immutable
sealed interface IslandContent {
    data object Idle : IslandContent

    data class Notif(
        val pkg: String, val title: String, val text: String,
        val icon: ImageBitmap? = null,
    ) : IslandContent

    data class Media(
        val title: String,
        val artist: String,
        val playing: Boolean,
        val durationMs: Long,
        val positionMs: Long,     // son bilinen konum
        val speed: Float,
        val updatedAt: Long,      // positionMs'in alındığı elapsedRealtime
        val art: ImageBitmap? = null, // albüm kapağı (küçültülmüş, önbellekli)
        val artColor: Int = 0,        // kapaktan çıkarılan baskın renk (0 = yok)
    ) : IslandContent {
        /** Sistem saatinden anlık konum tahmini: polling yerine hesaplama. */
        fun positionAt(now: Long): Long {
            val p = if (playing) positionMs + ((now - updatedAt) * speed).toLong() else positionMs
            return if (durationMs > 0) p.coerceIn(0L, durationMs) else p.coerceAtLeast(0L)
        }
    }
}

@Immutable
data class IslandUiState(
    val content: IslandContent = IslandContent.Idle,
    val expanded: Boolean = false,
)

sealed interface IslandEvent {
    data class Notification(val pkg: String, val title: String, val text: String) : IslandEvent
    data class MediaChanged(val media: IslandContent.Media?) : IslandEvent
}

/** Arayüz -> NotificationListener (medya kontrolü) komutları. */
sealed interface MediaCommand {
    data object PlayPause : MediaCommand
    data object Next : MediaCommand
    data object Previous : MediaCommand
    data class SeekTo(val ms: Long) : MediaCommand
}

object IslandBus {
    private val _events = MutableSharedFlow<IslandEvent>(extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val events: SharedFlow<IslandEvent> = _events.asSharedFlow()
    fun post(e: IslandEvent) { _events.tryEmit(e) }

    private val _commands = MutableSharedFlow<MediaCommand>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val commands: SharedFlow<MediaCommand> = _commands.asSharedFlow()
    fun send(c: MediaCommand) { _commands.tryEmit(c) }
}
