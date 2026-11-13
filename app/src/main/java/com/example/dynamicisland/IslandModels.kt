package com.example.dynamicisland

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

@Immutable
data class FavApp(val pkg: String, val label: String, val icon: ImageBitmap?)

@Immutable
sealed interface IslandContent {
    data object Idle : IslandContent
    data object Assistant : IslandContent
    data class Favorites(val apps: List<FavApp>) : IslandContent

    data class Notif(
        val pkg: String, val title: String, val text: String,
        val icon: ImageBitmap? = null,
        val intent: android.app.PendingIntent? = null,
    ) : IslandContent

    // Gelen Arama Modeli
    data class Call(
        val callerName: String,
        val phoneNumber: String,
        val isRinging: Boolean,
        val callDurationSeconds: Long = 0L,
    ) : IslandContent

    data class Media(
        val title: String,
        val artist: String,
        val playing: Boolean,
        val durationMs: Long,
        val positionMs: Long,
        val speed: Float,
        val updatedAt: Long,
        val art: ImageBitmap? = null,
        val artColor: Int = 0,
    ) : IslandContent {
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
    val compactLyrics: Boolean = false,
)

sealed interface IslandEvent {
    data class Notification(val pkg: String, val title: String, val text: String, val intent: android.app.PendingIntent? = null) : IslandEvent
    data class MediaChanged(val media: IslandContent.Media?) : IslandEvent
    data class AssistantChanged(val active: Boolean) : IslandEvent
    data class CallChanged(val call: IslandContent.Call?) : IslandEvent
}

sealed interface MediaCommand {
    data object PlayPause : MediaCommand
    data object Next : MediaCommand
    data object Previous : MediaCommand
    data class SeekTo(val ms: Long) : MediaCommand
}

sealed interface CallCommand {
    data object Answer : CallCommand
    data object Reject : CallCommand
}

object IslandBus {
    private val _events = MutableSharedFlow<IslandEvent>(extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val events: SharedFlow<IslandEvent> = _events.asSharedFlow()
    fun post(e: IslandEvent) { _events.tryEmit(e) }

    private val _commands = MutableSharedFlow<MediaCommand>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val commands: SharedFlow<MediaCommand> = _commands.asSharedFlow()
    fun send(c: MediaCommand) { _commands.tryEmit(c) }

    private val _callCommands = MutableSharedFlow<CallCommand>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val callCommands: SharedFlow<CallCommand> = _callCommands.asSharedFlow()
    fun sendCall(c: CallCommand) { _callCommands.tryEmit(c) }
}
