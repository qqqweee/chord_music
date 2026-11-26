package com.example.chord.ui.player

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.Player
import com.example.chord.data.home.HomeItem
import com.example.chord.data.library.LibraryStore
import com.example.chord.data.model.Song
import com.example.chord.data.repository.LyricsRepository
import com.example.chord.data.repository.LyricsResult
import com.example.chord.playback.DownloadStore
import com.example.chord.playback.PlaybackController
import com.example.chord.playback.PlaybackState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class PlayerViewModel(
    private val playbackController: PlaybackController,
    private val library: LibraryStore,
) : ViewModel() {

    val state: StateFlow<PlaybackState> = playbackController.state

    private val lyricsRepository = LyricsRepository()
    private val providerOrder: List<String> = lyricsRepository.providerNames

    // ── lyrics ──────────────────────────────────────────────────────────────
    // name -> result. Key missing = not asked yet. Value null = asked, not found.
    private val _outcomes = MutableStateFlow<Map<String, LyricsResult?>>(emptyMap())
    private val _fetching = MutableStateFlow<Set<String>>(emptySet())
    private val _selectedProvider = MutableStateFlow<String?>(null)

    private val _isLoadingLyrics = MutableStateFlow(false)
    val isLoadingLyrics = _isLoadingLyrics.asStateFlow()

    /** Every provider that found lyrics, in priority order. */
    val availableLyrics: StateFlow<List<LyricsResult>> = _outcomes
        .map { m -> m.values.filterNotNull().sortedBy { providerOrder.indexOf(it.providerName) } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Lyrics to show: picked provider, else first with synced lyrics, else first with anything. */
    val selectedLyrics: StateFlow<LyricsResult?> =
        combine(availableLyrics, _selectedProvider) { list, picked ->
            list.firstOrNull { it.providerName == picked }
                ?: list.firstOrNull { !it.syncedLyrics.isNullOrBlank() }
                ?: list.firstOrNull()
        }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Rows for the provider sheet. ALL providers, with live status. */
    val providers: StateFlow<List<LyricsProviderOption>> =
        combine(_outcomes, _fetching, selectedLyrics) { outcomes, fetching, selected ->
            providerOrder.map { name ->
                val status = when {
                    name == selected?.providerName -> ProviderStatus.Current
                    name in fetching -> ProviderStatus.Loading
                    outcomes.containsKey(name) ->
                        if (outcomes[name] != null) ProviderStatus.Found else ProviderStatus.NotFound
                    else -> ProviderStatus.Idle
                }
                LyricsProviderOption(id = name, name = name, status = status)
            }
        }.stateIn(
            viewModelScope, SharingStarted.Eagerly,
            providerOrder.map { LyricsProviderOption(it, it, ProviderStatus.Idle) },
        )

    private var currentSong: Song? = null
    private var currentDurationMs = 0L
    private var fetchGen = 0

    private fun record(name: String, result: LyricsResult?) {
        _outcomes.update { it + (name to result) }
        _fetching.update { it - name }
    }

    /** User tapped a provider in the sheet. Use it if found, else go fetch it. */
    fun selectLyricsProvider(name: String) {
        if (_outcomes.value[name] != null) {
            _selectedProvider.value = name
            return
        }
        val song = currentSong ?: return
        val gen = fetchGen
        viewModelScope.launch {
            _fetching.update { it + name }
            val r = try {
                lyricsRepository.fetchFrom(
                    providerName = name,
                    title = song.title,
                    artist = song.details,
                    durationMs = currentDurationMs,
                    videoId = song.videoId.takeIf { it.isNotBlank() },
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            if (gen != fetchGen) return@launch // song changed meanwhile
            record(name, r)
            if (r != null) _selectedProvider.value = name
        }
    }

    // ── favourites: saved in LibraryStore (Favourites playlist) ──
    private val playingSong: Song?
        get() = state.value.queue.getOrNull(state.value.currentQueueIndex)?.takeIf { it.videoId.isNotBlank() }

    val isFavorite: StateFlow<Boolean> = combine(state, library.favouriteIds()) { s, favs ->
        s.queue.getOrNull(s.currentQueueIndex)?.videoId?.let { it in favs } ?: false
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun toggleFavorite() {
        val song = playingSong ?: return
        viewModelScope.launch { library.toggleFavourite(song) }
    }

    // ── queue modes: shuffle / repeat live in the player; AutoPlay is UI state until a radio source exists ──
    val shuffle: StateFlow<Boolean> = state.map { it.shuffle }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val repeat: StateFlow<RepeatMode> = state.map {
        when (it.repeatMode) {
            Player.REPEAT_MODE_ALL -> RepeatMode.All
            Player.REPEAT_MODE_ONE -> RepeatMode.One
            else -> RepeatMode.Off
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, RepeatMode.Off)
    private val _autoplay = MutableStateFlow(true)
    val autoplay = _autoplay.asStateFlow()

    fun toggleShuffle() = playbackController.setShuffle(!state.value.shuffle)
    fun toggleAutoplay() { _autoplay.update { !it } }
    fun cycleRepeat() = playbackController.setRepeatMode(
        when (state.value.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    )
    fun removeQueueItem(index: Int) = playbackController.removeQueueItem(index)
    fun moveQueueItem(from: Int, to: Int) = playbackController.moveQueueItem(from, to)

    private var lyricsJob: Job? = null
    private var lastFetchedKey: String? = null

    init {
        viewModelScope.launch {
            state.collectLatest { playbackState ->
                val currentSong = playbackState.queue.getOrNull(playbackState.currentQueueIndex)
                    ?: if (playbackState.title.isNotBlank()) {
                        Song(title = playbackState.title, details = "", videoId = "")
                    } else null

                if (currentSong != null && currentSong.title.isNotBlank()) {
                    val currentKey = "${currentSong.title}_${currentSong.details}"
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

    /** Fresh stream for a song (upgrade quality / back to Opus) without restarting the queue. */
    fun reload(song: Song) {
        playbackController.reload(song)
    }

    /** Playlist / album tap: queue the whole list, start at [startIndex]. */
    fun playQueue(songs: List<Song>, startIndex: Int) {
        playbackController.playQueue(songs, startIndex)
    }

    /** Home tap: catalogue tracks only (HomeRepository already drops music videos). */
    fun playHomeItem(item: HomeItem) {
        val id = item.videoId ?: return
        play(
            Song(
                title = item.title,
                details = artistFromSubtitle(item.subtitle),
                videoId = id,
                thumbnailUrl = item.thumbnailUrl?.takeIf { it.isNotBlank() }
                    ?: "https://i.ytimg.com/vi/$id/hqdefault.jpg",
            )
        )
    }

    fun togglePlayPause() = playbackController.togglePlayPause()
    fun setSleepTimer(minutes: Int) { playbackController.setSleepTimer(minutes) }
    fun seekTo(positionMs: Long) = playbackController.seekTo(positionMs)
    fun setVolume(volume: Float) = playbackController.setVolume(volume)
    fun next() = playbackController.next()
    fun previous() = playbackController.previous()
    fun playQueueItem(index: Int) = playbackController.playQueueItem(index)
    fun enqueue(song: Song, next: Boolean) { playbackController.enqueue(song, next) }

    fun startRadio() {
        val currentSong = state.value.queue.getOrNull(state.value.currentQueueIndex)
        if (currentSong != null) playbackController.play(currentSong)
    }

    fun fetchLyricsForSong(song: Song?) {
        lyricsJob?.cancel()
        val gen = ++fetchGen

        _outcomes.value = emptyMap()
        _selectedProvider.value = null
        _fetching.value = emptySet()
        currentSong = song
        currentDurationMs = 0L

        if (song == null || song.title.isBlank()) {
            _isLoadingLyrics.value = false
            return
        }

        lyricsJob = viewModelScope.launch {
            // Saved with the download? Use it. Works offline, no network.
            DownloadStore.readLyrics(song.videoId)?.let { text ->
                if (gen != fetchGen) return@launch
                val synced = text.takeIf { Regex("""\[\d+:\d+""").containsMatchIn(it) }
                record("Saved", LyricsResult(plainLyrics = if (synced == null) text else null, syncedLyrics = synced, providerName = "Saved"))
                _isLoadingLyrics.value = false
                return@launch
            }
            _isLoadingLyrics.value = true
            _fetching.value = providerOrder.toSet()
            try {
                // Song length is 0 right after a track change. Providers need it to pick the
                // right version, so wait for it (max 6s), then go anyway.
                val duration = withTimeoutOrNull(6_000L) { state.first { it.duration > 0L }.duration } ?: 0L
                if (gen != fetchGen) return@launch
                currentDurationMs = duration

                lyricsRepository.lyricsFlow(
                    title = song.title,
                    artist = song.details,
                    durationMs = duration,
                    videoId = song.videoId.takeIf { it.isNotBlank() },
                ).collect { outcome ->
                    if (gen != fetchGen) return@collect
                    record(outcome.providerName, outcome.result)
                    // First synced hit: stop spinner now, others keep filling the sheet.
                    if (!outcome.result?.syncedLyrics.isNullOrBlank()) _isLoadingLyrics.value = false
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("PlayerViewModel", "Failed to fetch lyrics for ${song.title}", e)
            } finally {
                if (gen == fetchGen) {
                    _isLoadingLyrics.value = false
                    _fetching.value = emptySet()
                }
            }
        }
    }

    override fun onCleared() {
        lyricsJob?.cancel()
        playbackController.release()
        super.onCleared()
    }

    private companion object {
        val KIND = setOf("song", "video", "single", "album", "ep")

        /** "Song • Arijit Singh • Fitoor" / "Arijit Singh • 4:36" → "Arijit Singh". */
        fun artistFromSubtitle(subtitle: String): String {
            val parts = subtitle.split(" • ", "•").map { it.trim() }.filter { it.isNotEmpty() }
            return (if (parts.size > 1 && parts.first().lowercase() in KIND) parts[1] else parts.firstOrNull()).orEmpty()
        }
    }
}