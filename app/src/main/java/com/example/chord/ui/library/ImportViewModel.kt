package com.example.chord.ui.library

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.chord.data.auth.AuthStore
import com.example.chord.data.home.HomeItem
import com.example.chord.data.home.HomeItemType
import com.example.chord.data.importer.ExternalList
import com.example.chord.data.importer.PlaylistImporter
import com.example.chord.data.library.LibraryStore
import com.example.chord.data.model.Song
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface ImportUi {
    data object Idle : ImportUi
    data class Working(val label: String, val done: Int, val total: Int) : ImportUi
    data class Done(val playlistId: Long, val name: String, val added: Int, val missed: Int, val missedTitles: List<String> = emptyList()) : ImportUi
    data class Failed(val message: String) : ImportUi
}

sealed interface YtLibraryUi {
    data object Idle : YtLibraryUi
    data object Loading : YtLibraryUi
    data class Ready(val items: List<HomeItem>) : YtLibraryUi
    data class Error(val message: String) : YtLibraryUi
}

/** Import from Spotify and YouTube Music into local playlists. */
class ImportViewModel(private val app: Application) : AndroidViewModel(app) {
    private val importer = PlaylistImporter()
    private val store = LibraryStore.get(app)

    private val _ui = MutableStateFlow<ImportUi>(ImportUi.Idle)
    val ui: StateFlow<ImportUi> = _ui.asStateFlow()

    private val _yt = MutableStateFlow<YtLibraryUi>(YtLibraryUi.Idle)
    val yt: StateFlow<YtLibraryUi> = _yt.asStateFlow()

    private var job: Job? = null

    fun dismiss() { _ui.value = ImportUi.Idle }
    fun cancel() { job?.cancel(); _ui.value = ImportUi.Idle }

    // ---- Spotify -------------------------------------------------------------

    fun spotifyLink(text: String) = run("Reading Spotify") {
        val (type, id) = importer.parseSpotifyLink(text) ?: error("That is not a Spotify playlist or album link")
        finishExternal(importer.spotifyFromLink(type, id), source = "sp:$type:$id")
    }

    fun spotifyCsv(uri: Uri, fileName: String) = run("Reading file") {
        val text = withContext(Dispatchers.IO) {
            app.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: error("Can't open file")
        }
        finishExternal(importer.spotifyFromCsv(fileName.substringBeforeLast('.'), text))
    }

    private suspend fun finishExternal(list: ExternalList, source: String? = null) {
        _ui.value = ImportUi.Working("Matching songs", 0, list.tracks.size)
        val r = importer.match(list.tracks) { d, t -> _ui.value = ImportUi.Working("Matching songs", d, t) }
        save(list.name, r.songs, r.missed, source)
    }

    // ---- YouTube Music ---------------------------------------------------------

    fun loadYtLibrary() {
        if (!AuthStore.signedIn.value) return
        _yt.value = YtLibraryUi.Loading
        viewModelScope.launch {
            val liked = HomeItem("Liked songs", "Your likes", null, null, "VLLM", HomeItemType.Playlist)
            _yt.value = importer.ytLibrary().fold(
                onSuccess = { YtLibraryUi.Ready((listOf(liked) + it).distinctBy { p -> p.browseId }) },
                onFailure = { YtLibraryUi.Error(it.message ?: "Couldn't load your library") },
            )
        }
    }

    fun ytItem(item: HomeItem) = run("Reading ${item.title}") {
        val r = importer.ytPlaylist(item.browseId.orEmpty(), item.title)
        save(r.name, r.songs, r.missed, "yt:" + item.browseId.orEmpty())
    }

    fun ytLink(text: String) = run("Reading playlist") {
        val id = importer.parseYtLink(text) ?: error("That is not a YouTube Music playlist link")
        val r = importer.ytPlaylist(id, "YouTube Music import")
        save(r.name, r.songs, r.missed, "yt:$id")
    }

    // ---- shared ----------------------------------------------------------------

    private suspend fun save(name: String, songs: List<Song>, missed: List<String>, source: String?) {
        if (songs.isEmpty()) error("No songs matched")
        val id = store.create(name, source)
        store.addAll(id, songs)
        _ui.value = ImportUi.Done(id, name, songs.size, missed.size, missed)
    }

    private fun run(label: String, block: suspend () -> Unit) {
        job?.cancel()
        _ui.value = ImportUi.Working(label, 0, 0)
        job = viewModelScope.launch {
            try { block() } catch (e: CancellationException) { throw e } catch (e: Exception) {
                _ui.value = ImportUi.Failed(e.message ?: "Import failed")
            }
        }
    }
}