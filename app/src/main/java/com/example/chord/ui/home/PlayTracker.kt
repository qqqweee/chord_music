package com.example.chord.ui.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import com.example.chord.data.history.HistoryStore
import kotlinx.coroutines.delay

/**
 * Teaches Home what you listen to. Put it once in ChordApp:
 *
 *   PlayTracker(song?.videoId, song?.title, song?.details, thumbnailUrl, playbackState.isPlaying)
 *
 * Song starts  -> goes to the top of "Listen again" at once.
 * 30 s playing -> counts as a play (speed dial, hour mix, hero use this).
 * Paused time does not count.
 */
@Composable
fun PlayTracker(videoId: String?, title: String?, artist: String?, thumbnailUrl: String?, isPlaying: Boolean) {
    val store = HistoryStore.get(LocalContext.current)
    val playing by rememberUpdatedState(isPlaying)
    LaunchedEffect(videoId) {
        if (videoId.isNullOrBlank()) return@LaunchedEffect
        store.touch(videoId, title.orEmpty(), artist.orEmpty(), thumbnailUrl)
        var heard = 0
        while (heard < 30) {
            delay(1_000)
            if (playing) heard++
        }
        store.record(videoId, title.orEmpty(), artist.orEmpty(), thumbnailUrl)
    }
}