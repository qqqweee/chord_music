package com.example.chord.ui.library

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.chord.data.history.HistoryStore
import com.example.chord.data.importer.PlaylistImporter
import com.example.chord.data.library.LibraryStore
import com.example.chord.data.model.Song
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

sealed interface SyncUi {
    data object Idle : SyncUi
    data object Working : SyncUi
    /** Songs gone from the source. Ask before removing them here. */
    data class AskRemove(val playlistId: Long, val gone: List<Song>, val added: Int, val missed: List<String>) : SyncUi
    data class Done(val added: Int, val removed: Int, val missed: List<String>) : SyncUi
    data class Failed(val message: String) : SyncUi
}

/** One ViewModel for everything playlist related: Library tabs, detail page, add sheet. */
class LibraryViewModel(app: Application) : AndroidViewModel(app) {
    private val store = LibraryStore.get(app)
    private val history = HistoryStore.get(app)

    val playlists = store.playlists()
    val recent = history.recent()
    val mostPlayed = history.mostPlayed()
    val artists = history.artists()

    fun playlist(id: Long) = store.playlist(id)
    fun songs(id: Long) = store.songs(id)
    fun membership(videoId: String) = store.membership(videoId)

    fun create(name: String, then: (Long) -> Unit = {}) = viewModelScope.launch { then(store.create(name)) }
    fun rename(id: Long, name: String) = viewModelScope.launch { store.rename(id, name) }
    fun delete(id: Long) = viewModelScope.launch { store.delete(id) }
    fun remove(id: Long, videoId: String) = viewModelScope.launch { store.remove(id, videoId) }
    fun toggle(id: Long, song: Song, done: (Boolean) -> Unit = {}) =
        viewModelScope.launch { done(store.toggle(id, song)) }

    // ---- sync with source (Spotify / YouTube Music) ------------------------------

    private val importer = PlaylistImporter()
    private val _sync = MutableStateFlow<SyncUi>(SyncUi.Idle)
    val sync: StateFlow<SyncUi> = _sync.asStateFlow()

    fun dismissSync() { _sync.value = SyncUi.Idle }

    /** New songs are added at once. Songs gone from the source are only removed if the user says yes. */
    fun sync(id: Long, source: String) {
        if (_sync.value is SyncUi.Working) return
        _sync.value = SyncUi.Working
        viewModelScope.launch {
            try {
                val remote = importer.fetchSource(source)
                val local = store.songs(id).first()
                val localIds = local.mapTo(HashSet()) { it.videoId }
                val remoteIds = remote.songs.mapTo(HashSet()) { it.videoId }
                val fresh = remote.songs.filter { it.videoId !in localIds }
                if (fresh.isNotEmpty()) store.addAll(id, fresh)
                val gone = if (remote.complete) local.filter { it.videoId !in remoteIds } else emptyList()
                _sync.value = if (gone.isEmpty()) SyncUi.Done(fresh.size, 0, remote.missed)
                else SyncUi.AskRemove(id, gone, fresh.size, remote.missed)
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                _sync.value = SyncUi.Failed(e.message ?: "Sync failed")
            }
        }
    }

    fun removeGone(ask: SyncUi.AskRemove) {
        viewModelScope.launch {
            store.removeAll(ask.playlistId, ask.gone.map { it.videoId })
            _sync.value = SyncUi.Done(ask.added, ask.gone.size, ask.missed)
        }
    }

    fun keepGone(ask: SyncUi.AskRemove) { _sync.value = SyncUi.Done(ask.added, 0, ask.missed) }
}
