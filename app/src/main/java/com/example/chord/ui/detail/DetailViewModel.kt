package com.example.chord.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.chord.data.home.HomeItem
import com.example.chord.data.home.HomeRepository
import com.example.chord.data.playlist.PlaylistHeader
import com.example.chord.data.playlist.PlaylistParser
import com.example.chord.data.playlist.PlaylistTrack
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class DetailUiState(
    val header: PlaylistHeader,
    val tracks: List<PlaylistTrack> = emptyList(),
    val loading: Boolean = true,
    /** Later pages still arriving in the background. */
    val loadingMore: Boolean = false,
    val error: String? = null,
)

/**
 * One playlist or album page. The header is drawn at once from the card that
 * was tapped; tracks follow. Long playlists are pulled in page by page right
 * away, so Play always queues the whole list, not just what was scrolled to.
 */
class DetailViewModel(
    private val item: HomeItem,
    private val repo: HomeRepository = HomeRepository(),
) : ViewModel() {

    private val browseId = item.browseId.orEmpty()

    private val _state = MutableStateFlow(
        DetailUiState(
            header = PlaylistHeader(
                title = item.title,
                byline = "",
                subtitle = item.subtitle,
                secondSubtitle = "",
                description = "",
                thumbnailUrl = item.thumbnailUrl,
            ),
        ),
    )
    val state: StateFlow<DetailUiState> = _state.asStateFlow()

    private var job: Job? = null

    init { load() }

    fun retry() = load()

    private fun load() {
        if (browseId.isBlank()) {
            _state.update { it.copy(loading = false, error = "Nothing to open") }
            return
        }
        job?.cancel()
        job = viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null, tracks = emptyList()) }
            repo.browse(browseId)
                .mapCatching { PlaylistParser.parsePage(it) }
                .onSuccess { page ->
                    if (page.tracks.isEmpty()) {
                        _state.update { it.copy(loading = false, error = "No playable songs here") }
                        return@onSuccess
                    }
                    _state.update { cur ->
                        val h = page.header
                        cur.copy(
                            loading = false,
                            tracks = page.tracks,
                            header = if (h == null) cur.header else cur.header.copy(
                                title = h.title.ifBlank { cur.header.title },
                                byline = h.byline,
                                subtitle = h.subtitle.ifBlank { cur.header.subtitle },
                                secondSubtitle = h.secondSubtitle,
                                description = h.description,
                                thumbnailUrl = h.thumbnailUrl ?: cur.header.thumbnailUrl,
                            ),
                        )
                    }
                    pump(page.continuation)
                }
                .onFailure { e ->
                    _state.update { it.copy(loading = false, error = e.message ?: "Couldn't load") }
                }
        }
    }

    private suspend fun pump(first: String?) {
        var token = first
        while (token != null && _state.value.tracks.size < MAX_TRACKS) {
            _state.update { it.copy(loadingMore = true) }
            val result = repo.browseContinuation(token).mapCatching { PlaylistParser.parseContinuation(it) }
            val (more, next) = result.getOrNull() ?: break
            val known = _state.value.tracks
            _state.update { it.copy(tracks = known + more) }
            token = next
        }
        _state.update { it.copy(loadingMore = false) }
    }

    class Factory(private val item: HomeItem) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = DetailViewModel(item) as T
    }

    private companion object {
        const val MAX_TRACKS = 500
    }
}
