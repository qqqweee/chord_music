package com.example.chord.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.content.Context
import com.example.chord.data.history.HistoryStore
import com.example.chord.data.home.HomeItem
import com.example.chord.data.home.HomeItemType
import com.example.chord.data.home.HomeShelf
import com.example.chord.data.home.ShelfStyle
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import com.example.chord.data.home.HomeRepository
import com.example.chord.data.repository.MusicRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Params strings are the YT Music search filters (same ones Metrolist uses). */
enum class SearchTab(val label: String, val params: String?) {
    All("All", null),
    Songs("Songs", "EgWKAQIIAWoKEAkQBRAKEAMQBA%3D%3D"),
    Artists("Artists", "EgWKAQIgAWoKEAkQChAFEAMQBA%3D%3D"),
    Albums("Albums", "EgWKAQIYAWoKEAkQChAFEAMQBA%3D%3D"),
    Playlists("Playlists", "EgeKAQQoAEABagoQAxAEEAoQCRAF"),
}

data class SearchUiState(
    val query: String = "",
    /** The query whose results are on screen. Null = show suggestions / history. */
    val submitted: String? = null,
    val tab: SearchTab = SearchTab.All,
    val results: Map<SearchTab, List<HomeItem>> = emptyMap(),
    /** Tabs still loading. All tabs load at once so "All" can show artists, albums and playlists. */
    val loadingTabs: Set<SearchTab> = emptySet(),
    val message: String = "",
)

class SearchViewModel(
    @Suppress("UNUSED_PARAMETER") repository: MusicRepository,
    context: Context? = null,
) : ViewModel() {

    private val repo = HomeRepository()
    private val history = context?.let { HistoryStore.get(it) }
    private val _uiState = MutableStateFlow(SearchUiState())
    val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()
    private val jobs = HashMap<SearchTab, Job>()

    // ---- discover (empty search screen) -----------------------------------------
    private val personal = MutableStateFlow<List<HomeShelf>>(emptyList())
    private val explore = MutableStateFlow<List<HomeShelf>>(emptyList())
    /** Your taste first, then what is new and popular. */
    val discover: StateFlow<List<HomeShelf>> =
        combine(personal, explore) { a, b -> a + b }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    init { loadDiscover() }

    /** Screen shown. Try again if the first load failed (no network at start). */
    fun ensureDiscover() { if (explore.value.isEmpty()) loadDiscover() }

    private fun loadDiscover() {
        viewModelScope.launch {
            repo.explore().getOrNull()?.shelves.orEmpty()
                .filter { it.style != ShelfStyle.Hero && it.items.size >= 3 }
                .take(5).let { explore.value = it }
        }
        viewModelScope.launch {
            val names = history?.artists()?.first().orEmpty().take(3).map { it.title }.filter { it.isNotBlank() }
            if (names.isEmpty()) return@launch
            personal.value = personalShelves(names)
        }
    }

    private suspend fun personalShelves(names: List<String>): List<HomeShelf> = coroutineScope {
        fun find(q: String, tab: SearchTab, type: HomeItemType) = async {
            repo.search(q, tab.params).getOrNull().orEmpty().filter { it.type == type }
        }
        val albums = names.map { find(it, SearchTab.Albums, HomeItemType.Album) }
        val artists = names.map { find(it, SearchTab.Artists, HomeItemType.Artist) }
        val lists = find("${names.first()} mix", SearchTab.Playlists, HomeItemType.Playlist)
        val top = names.map { it.lowercase() }.toSet()
        listOfNotNull(
            albums.awaitAll().flatMap { it.take(3) }.distinctBy { it.browseId ?: it.title }.takeIf { it.size >= 3 }
                ?.let { HomeShelf("Albums for you", "", it, false, true) },
            artists.awaitAll().flatMap { it.take(4) }.filter { it.title.lowercase() !in top }
                .distinctBy { it.browseId ?: it.title }.takeIf { it.size >= 3 }
                ?.let { HomeShelf("Artists you may like", "", it, false, true) },
            lists.await().take(8).takeIf { it.size >= 3 }
                ?.let { HomeShelf("Playlists for you", "", it, false, true) },
        )
    }

    // ---- search ----------------------------------------------------------------------

    fun updateQuery(query: String) {
        _uiState.update { it.copy(query = query, submitted = if (query.trim() == it.submitted) it.submitted else null) }
    }

    /** Run a search for [q]. Loads every tab at once. Also used to open "more from artist". */
    fun submit(q: String) {
        val text = q.trim()
        if (text.isBlank()) return
        jobs.values.forEach { it.cancel() }
        jobs.clear()
        val all = SearchTab.values().toSet()
        _uiState.value = SearchUiState(query = text, submitted = text, tab = SearchTab.All, loadingTabs = all)
        all.forEach { load(text, it) }
    }

    fun selectTab(tab: SearchTab) {
        _uiState.update { it.copy(tab = tab) }
        val st = _uiState.value
        val q = st.submitted ?: return
        if (st.results[tab] == null && tab !in st.loadingTabs) load(q, tab)
    }

    private fun load(q: String, tab: SearchTab) {
        jobs[tab]?.cancel()
        _uiState.update { if (it.submitted == q) it.copy(loadingTabs = it.loadingTabs + tab) else it }
        jobs[tab] = viewModelScope.launch {
            repo.search(q, tab.params)
                .onSuccess { hits ->
                    _uiState.update {
                        if (it.submitted != q) it
                        else it.copy(results = it.results + (tab to hits), loadingTabs = it.loadingTabs - tab)
                    }
                }
                .onFailure { e ->
                    if (e is CancellationException) throw e
                    _uiState.update {
                        if (it.submitted != q) it
                        else it.copy(loadingTabs = it.loadingTabs - tab, message = "Search failed: ${e.message ?: "unknown error"}")
                    }
                }
        }
    }
}
