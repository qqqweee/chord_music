package com.example.chord.ui.lyrics

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Kept for existing call sites. It used to duplicate the word-by-word logic with different
 * (and buggy) styling; it now simply delegates to [WordByWordLyricsView].
 */
@Composable
fun SyncedLyricsView(
    syncedLyrics: String,
    currentPositionMs: Long,
    onSeekRequested: (Long) -> Unit,
    modifier: Modifier = Modifier,
    isPlaying: Boolean = true,
) {
    WordByWordLyricsView(
        rawLyrics = syncedLyrics,
        currentPositionMs = currentPositionMs,
        onSeekRequested = onSeekRequested,
        modifier = modifier,
        isPlaying = isPlaying,
    )
}