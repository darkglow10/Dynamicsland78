package com.example.dynamicisland

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.Rating
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState

/**
 * Adadaki kalp düğmesini çalan uygulamaya iletir. İki yol denenir:
 *  1) Uygulamanın medya oturumunda "beğen" tarzı özel eylem varsa onu tetikler.
 *  2) Oturum kalp/başparmak puanlamasını destekliyorsa puan gönderir.
 * Uygulama ikisini de sunmuyorsa false döner (dışarıdan beğenilemez).
 */
object PlayerLike {
    private val LIKE = Regex("like|love|heart|favo|thumb|beğen|begen|favori|нрав|избран|лайк", RegexOption.IGNORE_CASE)
    private val UNLIKE = Regex("unlike|unlove|unfav|unsave|remove|delete|undo|çıkar|kaldır|vazgeç|удал|убрат|отмен", RegexOption.IGNORE_CASE)
    private val DISLIKE = Regex("dislike|thumbs?[ _-]?down|beğenme(?![a-zçğıöşü])|не нрав|дизлайк", RegexOption.IGNORE_CASE)

    /** 1 = beğen, 2 = beğenmekten vazgeç, 0 = ilgisiz eylem */
    private fun kind(a: PlaybackState.CustomAction): Int {
        val t = "${a.action} ${a.name}"
        return when {
            !LIKE.containsMatchIn(t) || DISLIKE.containsMatchIn(t) -> 0
            UNLIKE.containsMatchIn(t) -> 2
            else -> 1
        }
    }

    private fun controller(ctx: Context, title: String): MediaController? = try {
        val msm = ctx.getSystemService(MediaSessionManager::class.java)
        val list = msm.getActiveSessions(ComponentName(ctx, IslandNotificationListener::class.java))
        list.firstOrNull { it.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE) == title }
            ?: list.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: list.firstOrNull()
    } catch (e: Exception) {
        null
    }

    /** Uygulama durumu açıkça bildiriyorsa true/false, bilinmiyorsa null. */
    fun likedState(ctx: Context, title: String): Boolean? {
        val c = controller(ctx, title) ?: return null
        return try {
            val ps = c.playbackState
            val rating = c.metadata?.getRating(MediaMetadata.METADATA_KEY_USER_RATING)
            if (ps != null && (ps.actions and PlaybackState.ACTION_SET_RATING) != 0L && rating != null) {
                when {
                    !rating.isRated -> false
                    rating.ratingStyle == Rating.RATING_HEART -> rating.hasHeart()
                    rating.ratingStyle == Rating.RATING_THUMB_UP_DOWN -> rating.isThumbUp
                    else -> null
                }
            } else if (ps?.customActions.orEmpty().any { kind(it) == 2 }) true
            else null
        } catch (e: Exception) {
            null
        }
    }

    /** Beğeniyi çalan uygulamaya gönderir; gönderilebildiyse true. */
    fun setLiked(ctx: Context, title: String, liked: Boolean): Boolean {
        val c = controller(ctx, title) ?: return false
        return try {
            val ps = c.playbackState
            val acts = ps?.customActions.orEmpty()
            val want = acts.firstOrNull { kind(it) == (if (liked) 1 else 2) }
                ?: if (!liked) acts.firstOrNull { kind(it) == 1 } else null   // aç/kapa tarzı tek düğme
            if (want != null) {
                c.transportControls.sendCustomAction(want, want.extras)
                true
            } else if (ps != null && (ps.actions and PlaybackState.ACTION_SET_RATING) != 0L) {
                val style = c.metadata?.getRating(MediaMetadata.METADATA_KEY_USER_RATING)?.ratingStyle ?: Rating.RATING_HEART
                val r = if (style == Rating.RATING_THUMB_UP_DOWN) Rating.newThumbRating(liked) else Rating.newHeartRating(liked)
                c.transportControls.setRating(r)
                true
            } else false
        } catch (e: Exception) {
            false
        }
    }
}
