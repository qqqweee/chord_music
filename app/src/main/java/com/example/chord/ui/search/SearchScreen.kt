package com.example.chord.ui.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import com.example.chord.data.model.Song
import com.example.chord.playback.PlaybackState
import com.example.chord.ui.components.MiniPlayer
import com.example.chord.ui.player.PlayerScreen

@Composable
fun SearchScreen(
    searchViewModel: SearchViewModel,
    playbackState: PlaybackState,
    syncedLyrics: String?,       // Added lyrics state
    isLoadingLyrics: Boolean,    // Added loading state
    onPlaySong: (Song) -> Unit,
    onTogglePlayPause: () -> Unit,
    onSeek: (Long) -> Unit,
    onStartRadio: () -> Unit,
    onPrevious: () -> Unit = {},
    onNext: () -> Unit = {},
    onPlayQueueItem: (Int) -> Unit = {}
) {
    val search by searchViewModel.uiState.collectAsState()
    var showFullPlayer by remember { mutableStateOf(false) }

    val currentSong = playbackState.queue
        .getOrNull(playbackState.currentQueueIndex)
        ?: search.songs.firstOrNull { it.title == playbackState.title }

    val thumbnailUrl = currentSong?.videoId?.let { videoId ->
        "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"
    }

    if (showFullPlayer) {
        PlayerScreen(
            playbackState = playbackState,
            thumbnailUrl = thumbnailUrl,
            syncedLyrics = syncedLyrics,       // Pass to PlayerScreen
            isLoadingLyrics = isLoadingLyrics, // Pass to PlayerScreen
            onTogglePlayPause = onTogglePlayPause,
            onBack = { showFullPlayer = false },
            onSeek = onSeek,
            onPrevious = onPrevious,
            onNext = onNext,
            onPlayQueueItem = onPlayQueueItem
        )
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text(
            text = "Chord",
            style = MaterialTheme.typography.headlineMedium
        )

        Spacer(Modifier.height(12.dp))

        OutlinedTextField(
            value = search.query,
            onValueChange = searchViewModel::updateQuery,
            label = { Text("Search songs") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        Spacer(Modifier.height(8.dp))

        Button(
            onClick = searchViewModel::search,
            enabled = !search.isLoading && search.query.isNotBlank(),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (search.isLoading) "Searching..." else "Search")
        }

        Spacer(Modifier.height(8.dp))

        if (search.message.isNotBlank()) {
            Text(
                text = search.message,
                style = MaterialTheme.typography.bodySmall
            )
        }

        if (search.isLoading || playbackState.isLoading) {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth()
            )
        }

        playbackState.error?.let { error ->
            Text(
                text = error,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall
            )
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
        ) {
            search.songs.forEach { song ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .clickable(enabled = !playbackState.isLoading) {
                            onPlaySong(song)
                        }
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            text = song.title,
                            style = MaterialTheme.typography.titleMedium
                        )

                        if (song.details.isNotBlank()) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = song.details,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }

                        Text(
                            text = when {
                                playbackState.title != song.title -> "Tap to play"
                                playbackState.isLoading -> "Loading audio…"
                                playbackState.isPlaying -> "Playing"
                                playbackState.playbackState == Player.STATE_BUFFERING ->
                                    "Buffering…"
                                playbackState.playbackState == Player.STATE_READY -> "Ready"
                                else -> "Selected"
                            },
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }

        if (playbackState.title.isNotBlank()) {
            HorizontalDivider()
            Spacer(Modifier.height(8.dp))

            MiniPlayer(
                title = playbackState.title,
                details = currentSong?.details.orEmpty(),
                thumbnailUrl = thumbnailUrl,
                isPlaying = playbackState.isPlaying,
                isLoading = playbackState.isLoading ||
                        playbackState.playbackState == Player.STATE_BUFFERING,
                onTogglePlayPause = onTogglePlayPause,
                onOpenPlayer = { showFullPlayer = true }
            )
        }
    }
}