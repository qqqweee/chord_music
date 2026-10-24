package com.example.chord.ui.player

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.chord.data.model.Song
import com.example.chord.data.repository.LyricsRepository
import com.example.chord.playback.PlaybackController
import com.example.chord.playback.PlaybackState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PlayerViewModel(
    private val playbackController: PlaybackController
) : ViewModel() {

    val state: StateFlow<PlaybackState> = playbackController.state

    private val lyricsRepository = LyricsRepository()

    private val _syncedLyrics = MutableStateFlow<String?>(null)
    val syncedLyrics = _syncedLyrics.asStateFlow()

    private val _isLoadingLyrics = MutableStateFlow(false)
    val isLoadingLyrics = _isLoadingLyrics.asStateFlow()

    private var lyricsJob: Job? = null
    private var lastFetchedKey: String? = null

    init {
        // 1. Refresh playback position periodically
        viewModelScope.launch {
            while (true) {
                playbackController.refreshProgress()
                delay(500L)
            }
        }

        // 2. Observe song changes with collectLatest (automatically cancels previous collectors)
        viewModelScope.launch {
            state.collectLatest { playbackState ->
                val currentSong = playbackState.queue.getOrNull(playbackState.currentQueueIndex)
                    ?: if (playbackState.title.isNotBlank()) {
                        Song(
                            title = playbackState.title,
                            details = "",
                            videoId = ""
                        )
                    } else null

                if (currentSong != null && currentSong.title.isNotBlank()) {
                    val currentKey = "${currentSong.title}_${currentSong.details}"

                    // Only trigger a new fetch if title OR artist metadata changed
                    if (currentKey != lastFetchedKey) {
                        lastFetchedKey = currentKey
                        fetchLyricsForSong(currentSong)
                    }
                }
            }
        }
    }

    fun play(song: Song) {
        playbackController.play(song)
    }

    fun togglePlayPause() {
        playbackController.togglePlayPause()
    }

    fun seekTo(positionMs: Long) {
        playbackController.seekTo(positionMs)
    }

    fun next() {
        playbackController.next()
    }

    fun previous() {
        playbackController.previous()
    }

    fun playQueueItem(index: Int) {
        playbackController.playQueueItem(index)
    }

    fun startRadio() {
        val currentSong = state.value.queue.getOrNull(state.value.currentQueueIndex)
        if (currentSong != null) {
            playbackController.play(currentSong)
        }
    }

    fun fetchLyricsForSong(song: Song?) {
        lyricsJob?.cancel() // Cancel ongoing fetch immediately

        if (song == null || song.title.isBlank()) {
            _syncedLyrics.value = null
            _isLoadingLyrics.value = false
            return
        }

        lyricsJob = viewModelScope.launch {
            _syncedLyrics.value = null
            _isLoadingLyrics.value = true

            // If artist metadata hasn't loaded yet, wait up to 300ms for ExoPlayer metadata sync
            if (song.details.isBlank()) {
                delay(300L)
            }

            try {
                val result = withContext(Dispatchers.IO) {
                    lyricsRepository.getLyrics(
                        title = song.title,
                        artist = song.details
                    )
                }

                // Verify the song hasn't changed while we were fetching over network
                val activeSongTitle = state.value.title
                if (activeSongTitle.equals(song.title, ignoreCase = true)) {
                    _syncedLyrics.value = result?.syncedLyrics ?: result?.plainLyrics
                } else {
                    Log.d("PlayerViewModel", "Discarded stale lyrics for: ${song.title}")
                }
            } catch (e: Exception) {
                Log.e("PlayerViewModel", "Failed to fetch lyrics for ${song.title}", e)
                _syncedLyrics.value = null
            } finally {
                _isLoadingLyrics.value = false
            }
        }
    }

    override fun onCleared() {
        lyricsJob?.cancel()
        playbackController.release()
        super.onCleared()
    }
}