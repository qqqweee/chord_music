package com.example.chord.data.library

import android.content.Context
import android.content.SharedPreferences
import com.example.chord.data.auth.AuthStore
import com.example.chord.data.home.HomeRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

/** One YouTube Music playlist in your library. Opening it always loads the live page, so songs are never old. */
data class YtPlaylist(val title: String, val subtitle: String, val thumbnailUrl: String?, val browseId: String) {
    val isLiked get() = browseId == LIKED_ID
    companion object { const val LIKED_ID = "VLLM" }
}

/**
 * Your YouTube Music playlists, kept fresh.
 *  - Cached on disk: Library opens at once, even offline.
 *  - [refresh] asks YouTube again. Called when Library opens (if older than 3 min), on sign-in,
 *    on pull-down and on the refresh button.
 *  - Liked songs is always first.
 */
object YtLibrary {
    private const val FILE = "chord_yt_library"
    private const val STALE_MS = 3 * 60_000L

    private val repo by lazy { HomeRepository() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private var prefs: SharedPreferences? = null

    private val _playlists = MutableStateFlow<List<YtPlaylist>>(emptyList())
    val playlists: StateFlow<List<YtPlaylist>> = _playlists.asStateFlow()
    private val _syncing = MutableStateFlow(false)
    val syncing: StateFlow<Boolean> = _syncing.asStateFlow()
    private val _updatedAt = MutableStateFlow(0L)
    val updatedAt: StateFlow<Long> = _updatedAt.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    @Synchronized
    fun init(ctx: Context) {
        if (prefs != null) return
        val p = ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        prefs = p
        _updatedAt.value = p.getLong("at", 0L)
        runCatching {
            val a = JSONArray(p.getString("list", "[]"))
            _playlists.value = (0 until a.length()).map {
                val o = a.getJSONObject(it)
                YtPlaylist(o.getString("t"), o.optString("s"), o.optString("i").takeIf { u -> u.isNotBlank() }, o.getString("b"))
            }
        }
    }

    /** [force] = ignore the 3 minute rest. */
    fun refresh(force: Boolean = false) {
        if (!AuthStore.signedIn.value) {
            clear(); _error.value = null; return
        }
        if (!force && _playlists.value.isNotEmpty() && System.currentTimeMillis() - _updatedAt.value < STALE_MS) return
        scope.launch {
            if (!lock.tryLock()) return@launch   // one sync at a time
            try {
                _syncing.value = true
                repo.libraryPlaylists().fold(
                    onSuccess = { items ->
                        val liked = YtPlaylist("Liked songs", "Auto playlist", null, YtPlaylist.LIKED_ID)
                        val list = listOf(liked) + items.filter { it.browseId != YtPlaylist.LIKED_ID }
                            .map { YtPlaylist(it.title, it.subtitle, it.thumbnailUrl, it.browseId!!) }
                        _playlists.value = list
                        _updatedAt.value = System.currentTimeMillis()
                        _error.value = null
                        save(list)
                    },
                    onFailure = { _error.value = it.message ?: "Couldn't reach YouTube Music" },
                )
            } finally {
                _syncing.value = false
                lock.unlock()
            }
        }
    }

    /** Sign out: forget the cached list. */
    fun clear() {
        _playlists.value = emptyList(); _updatedAt.value = 0L
        prefs?.edit()?.clear()?.apply()
    }

    private fun save(list: List<YtPlaylist>) {
        val a = JSONArray()
        list.forEach { a.put(JSONObject().put("t", it.title).put("s", it.subtitle).put("i", it.thumbnailUrl ?: "").put("b", it.browseId)) }
        prefs?.edit()?.putString("list", a.toString())?.putLong("at", _updatedAt.value)?.apply()
    }
}
