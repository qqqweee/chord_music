package com.example.chord

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.ViewModelProvider
import com.example.chord.di.AppContainer
import com.example.chord.ui.player.PlayerViewModel
import com.example.chord.ui.search.SearchScreen
import com.example.chord.ui.search.SearchViewModel

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        // Enable drawing content edge-to-edge behind the status bar and navigation bar
        enableEdgeToEdge()

        super.onCreate(savedInstanceState)

        val container = AppContainer(applicationContext)

        val searchViewModel = ViewModelProvider(
            this,
            container.searchViewModelFactory()
        )[SearchViewModel::class.java]

        val playerViewModel = ViewModelProvider(
            this,
            container.playerViewModelFactory()
        )[PlayerViewModel::class.java]

        setContent {
            MaterialTheme {
                ChordApp(
                    searchViewModel = searchViewModel,
                    playerViewModel = playerViewModel
                )
            }
        }
    }
}

@Composable
private fun ChordApp(
    searchViewModel: SearchViewModel,
    playerViewModel: PlayerViewModel
) {
    val playbackState by playerViewModel.state.collectAsState()

    // 1. Collect the states from your view model
    val syncedLyrics by playerViewModel.syncedLyrics.collectAsState()
    val isLoadingLyrics by playerViewModel.isLoadingLyrics.collectAsState()

    // 2. Pass them to the SearchScreen
    SearchScreen(
        searchViewModel = searchViewModel,
        playbackState = playbackState,
        syncedLyrics = syncedLyrics,
        isLoadingLyrics = isLoadingLyrics,
        onPlaySong = playerViewModel::play,
        onTogglePlayPause = playerViewModel::togglePlayPause,
        onSeek = { positionMs ->
            playerViewModel.seekTo(positionMs)
        },
        onStartRadio = {
            playerViewModel.startRadio()
        },
        onPrevious = playerViewModel::previous,
        onNext = playerViewModel::next,
        onPlayQueueItem = playerViewModel::playQueueItem
    )
}