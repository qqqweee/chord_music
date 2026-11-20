package com.example.chord.ui.search

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.example.chord.data.home.HomeItem
import com.example.chord.data.model.Song
import com.example.chord.playback.PlaybackState

@Composable
fun SearchScreen(
    searchViewModel: SearchViewModel,
    playbackState: PlaybackState,
    onPlaySong: (Song) -> Unit,
    onOpenItem: (HomeItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    val s by searchViewModel.uiState.collectAsState()
    val discover by searchViewModel.discover.collectAsState()
    LaunchedEffect(Unit) { searchViewModel.ensureDiscover() }
    val currentVideoId = playbackState.queue.getOrNull(playbackState.currentQueueIndex)?.videoId

    Box(modifier = modifier.fillMaxSize()) {
        SearchContent(
            query = s.query,
            onQueryChange = searchViewModel::updateQuery,
            submitted = s.submitted,
            tab = s.tab,
            onTab = searchViewModel::selectTab,
            hits = s.results[s.tab].orEmpty(),
            results = s.results,
            discover = discover,
            isLoading = s.tab in s.loadingTabs,
            message = s.message,
            currentVideoId = currentVideoId,
            isPlaying = playbackState.isPlaying,
            onSubmit = searchViewModel::submit,
            onPlay = onPlaySong,
            onOpen = onOpenItem,
            modifier = Modifier.fillMaxSize(),
        )
    }
}
