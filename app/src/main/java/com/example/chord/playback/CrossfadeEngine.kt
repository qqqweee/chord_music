package com.example.chord.playback

import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Real crossfade. Two players.
 *
 *   Idle      main plays. Last (fade + 6 s) of the song: wake [aux] with the next song, silent.
 *   Warm      aux loads. When the song is [seconds] from the end and aux is ready: start aux.
 *   Fading    main fades out, aux fades in (equal power, so no dip in loudness).
 *   Swapping  main jumps into the next song at aux's spot, then aux goes quiet.
 *
 * Main stays the one the notification, queue and UI talk to. Aux is a helper.
 * Aux reads the same cache, so the jump in main costs nothing. If aux is not ready in time,
 * or the user skips, pauses or seeks: we drop the fade and play normal. Never worse than off.
 *
 * Call [tick] every ~50 ms on the main thread.
 */
@UnstableApi
class CrossfadeEngine(
    private val main: ExoPlayer,
    private val aux: ExoPlayer,
    private val seconds: () -> Int,
) {
    private enum class S { Idle, Warm, Fading, Swapping }

    private var s = S.Idle
    private var curId: String? = null
    private var nextId: String? = null
    private var blocked: String? = null   // song where aux failed. Do not retry on it.
    private var deadline = 0L

    fun tick() {
        if (s == S.Swapping) { finishSwap(); return }

        val secs = seconds()
        val fadeMs = secs * 1000L
        val dur = main.duration
        val cur = main.currentMediaItem?.mediaId
        val eligible = secs > 0 && cur != null && dur != C.TIME_UNSET && dur > fadeMs * 3 &&
            main.hasNextMediaItem() && main.repeatMode != Player.REPEAT_MODE_ONE
        if (!eligible) {
            if (s != S.Idle) abort() else restoreVolume()
            return
        }

        val remaining = dur - main.currentPosition
        val next = main.getMediaItemAt(main.nextMediaItemIndex)

        when (s) {
            S.Idle -> {
                if (!main.isPlaying || cur == blocked) { restoreVolume(); return }
                if (remaining in 1..(fadeMs + WARM_MS)) warm(cur!!, next.mediaId, next) else restoreVolume()
            }
            S.Warm -> {
                if (cur != curId || next.mediaId != nextId || !main.playWhenReady) { abort(); return }
                when {
                    aux.playerError != null -> { blocked = cur; abort() }
                    remaining <= fadeMs && aux.playbackState == Player.STATE_READY -> {
                        aux.play()
                        s = S.Fading
                    }
                    remaining <= GIVE_UP_MS -> { blocked = cur; abort() }
                }
            }
            S.Fading -> {
                if (cur != curId || next.mediaId != nextId || !main.playWhenReady || remaining > fadeMs + 600) {
                    abort(); return
                }
                val p = ((fadeMs - remaining).toFloat() / fadeMs).coerceIn(0f, 1f)
                main.volume = cos(p * PI / 2).toFloat()
                aux.volume = sin(p * PI / 2).toFloat()
                if (remaining <= SWAP_AT_MS) swap()
            }
            S.Swapping -> Unit
        }
    }

    fun release() {
        runCatching { aux.release() }
    }

    private fun warm(cur: String, next: String, item: androidx.media3.common.MediaItem) {
        curId = cur
        nextId = next
        aux.volume = 0f
        aux.playWhenReady = false
        aux.setMediaItem(item)
        aux.prepare()
        s = S.Warm
    }

    /** Main jumps to the next song, at the spot aux has reached. Main is silent now (volume ~0). */
    private fun swap() {
        main.seekTo(main.nextMediaItemIndex, aux.currentPosition + SWAP_COMP_MS)
        deadline = SystemClock.uptimeMillis() + SWAP_TIMEOUT_MS
        s = S.Swapping
    }

    private fun finishSwap() {
        val ready = main.currentMediaItem?.mediaId == nextId &&
            main.playbackState == Player.STATE_READY && main.isPlaying
        if (ready || SystemClock.uptimeMillis() > deadline) {
            main.volume = 1f
            quietAux()
            blocked = null
            s = S.Idle
        }
    }

    private fun abort() {
        quietAux()
        main.volume = 1f
        s = S.Idle
    }

    private fun quietAux() {
        aux.volume = 0f
        aux.stop()
        aux.clearMediaItems()
    }

    private fun restoreVolume() {
        if (main.volume != 1f) main.volume = 1f
    }

    private companion object {
        const val WARM_MS = 6_000L        // wake aux this long before the fade starts
        const val GIVE_UP_MS = 400L       // aux still not ready this close to the end: no fade
        const val SWAP_AT_MS = 250L
        const val SWAP_COMP_MS = 120L     // main takes a moment to start. Aim a little ahead.
        const val SWAP_TIMEOUT_MS = 2_500L
    }
}
