package com.example.chord.ui.stats

import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import com.example.chord.data.stats.ListenStatsStore
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Adds real playing time to [ListenStatsStore]. Put once in ChordApp, next to PlayTracker.
 * Paused time is not counted. Saves every 10 s and when the song changes.
 */
@Composable
fun ListenTimeTracker(videoId: String?, title: String?, artist: String?, thumbnailUrl: String?, isPlaying: Boolean) {
    val store = ListenStatsStore.get(LocalContext.current)
    val playing by rememberUpdatedState(isPlaying)
    LaunchedEffect(videoId) {
        if (videoId.isNullOrBlank()) return@LaunchedEffect
        var pending = 0L
        var last = SystemClock.elapsedRealtime()
        try {
            while (true) {
                delay(1_000)
                val now = SystemClock.elapsedRealtime()
                val dt = (now - last).coerceAtMost(5_000L)
                last = now
                if (playing) pending += dt
                if (pending >= 10_000L) {
                    store.add(videoId, title.orEmpty(), artist.orEmpty(), thumbnailUrl, pending)
                    pending = 0L
                }
            }
        } finally {
            if (pending > 0L) withContext(NonCancellable) {
                store.add(videoId, title.orEmpty(), artist.orEmpty(), thumbnailUrl, pending)
            }
        }
    }
}
