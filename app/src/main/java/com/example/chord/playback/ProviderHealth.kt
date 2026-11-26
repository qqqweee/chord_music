package com.example.chord.playback

import android.util.Log
import java.util.concurrent.ConcurrentHashMap

/**
 * Which audio providers failed lately. One small table, used by every resolver.
 *
 * A failing provider rests for a short time, then gets tried again. Never a permanent ban.
 * Each failure in a row doubles the rest (10 s, 20 s, 40 s ... max 5 min). One success clears it.
 *
 * A provider id is a plain string: "monochrome:https://host", "addon:Name", "youtube".
 * YouTube is the last resort and is never put to rest.
 */
object ProviderHealth {
    private const val TAG = "ChordHealth"
    private const val BASE_REST_MS = 10_000L
    private const val MAX_REST_MS = 5 * 60_000L

    private class State(@Volatile var downUntil: Long = 0L, @Volatile var strikes: Int = 0)

    private val states = ConcurrentHashMap<String, State>()

    fun isDown(provider: String): Boolean {
        if (provider == YOUTUBE) return false
        val s = states[provider] ?: return false
        return System.currentTimeMillis() < s.downUntil
    }

    /** Failure. [restMs] from a server hint (Retry-After) wins when it is longer than the backoff. */
    fun markFailed(provider: String, restMs: Long = 0L) {
        if (provider.isBlank() || provider == YOUTUBE) return
        val s = states.computeIfAbsent(provider) { State() }
        synchronized(s) {
            s.strikes = (s.strikes + 1).coerceAtMost(6)
            val backoff = (BASE_REST_MS shl (s.strikes - 1)).coerceAtMost(MAX_REST_MS)
            val rest = maxOf(backoff, restMs.coerceAtMost(MAX_REST_MS))
            s.downUntil = maxOf(s.downUntil, System.currentTimeMillis() + rest)
            Log.w(TAG, "$provider resting ${rest / 1000} s (strike ${s.strikes})")
        }
    }

    /** Provider answered fine. Forget the strikes. */
    fun markOk(provider: String) {
        states.remove(provider)
    }

    fun clear(provider: String) {
        states.remove(provider)
    }

    const val YOUTUBE = "youtube"
}
