package com.example.chord.data.importer

import com.example.chord.data.history.HistoryStore
import com.example.chord.data.home.HomeItem
import com.example.chord.data.home.HomeItemType
import com.example.chord.data.home.HomeRepository
import com.example.chord.data.model.Song
import com.example.chord.data.playlist.PlaylistParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger

/** A track from another service. Matched to a YT Music song before saving. */
data class ExternalTrack(val title: String, val artist: String)
data class ExternalList(val name: String, val tracks: List<ExternalTrack>)
/** [missed] = "Title - Artist" lines that could not be matched or were unavailable. */
data class MatchResult(val songs: List<Song>, val missed: List<String>)
data class YtResult(val name: String, val songs: List<Song>, val missed: List<String>, val complete: Boolean)
/** Current state of a source playlist. [complete] false = list was cut short, so don't treat absent songs as removed. */
data class RemoteList(val songs: List<Song>, val missed: List<String>, val complete: Boolean)

/**
 * Everything import needs, no UI. Three sources:
 *  - Spotify public link (reads Spotify's embed page, no login)
 *  - Spotify CSV (Exportify etc.)
 *  - YouTube Music: your library, or any playlist link
 */
class PlaylistImporter(private val repo: HomeRepository = HomeRepository()) {

    // ---- Spotify -------------------------------------------------------------

    /** "https://open.spotify.com/playlist/ID?si=.." or "spotify:playlist:ID" -> ("playlist", "ID"). */
    fun parseSpotifyLink(text: String): Pair<String, String>? {
        val m = SPOTIFY_LINK.find(text.trim()) ?: SPOTIFY_URI.find(text.trim()) ?: return null
        return m.groupValues[1] to m.groupValues[2]
    }

    suspend fun spotifyFromLink(type: String, id: String): ExternalList = withContext(Dispatchers.IO) {
        val conn = URL("https://open.spotify.com/embed/$type/$id").openConnection() as HttpURLConnection
        val html = try {
            conn.connectTimeout = 15_000; conn.readTimeout = 20_000
            conn.setRequestProperty("User-Agent", UA)
            conn.setRequestProperty("Accept-Language", "en")
            if (conn.responseCode !in 200..299) error("Spotify said HTTP ${conn.responseCode}")
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally { conn.disconnect() }

        val json = NEXT_DATA.find(html)?.groupValues?.get(1) ?: error("Spotify page changed. Use the CSV option.")
        val holder = findHolder(JSONObject(json)) ?: error("No tracks found. Is the playlist public?")
        val list = holder.getJSONArray("trackList")
        val tracks = (0 until list.length()).mapNotNull { i ->
            val t = list.optJSONObject(i) ?: return@mapNotNull null
            val title = t.optString("title").trim()
            if (title.isEmpty()) null else ExternalTrack(title, t.optString("subtitle").replace(' ', ' ').trim())
        }
        if (tracks.isEmpty()) error("Playlist is empty")
        val name = holder.optString("name").ifBlank { holder.optString("title") }.ifBlank { "Spotify import" }
        ExternalList(name, tracks)
    }

    private fun findHolder(n: Any?): JSONObject? = when (n) {
        is JSONObject ->
            if (n.optJSONArray("trackList") != null) n
            else n.keys().asSequence().firstNotNullOfOrNull { findHolder(n.opt(it)) }
        is JSONArray -> (0 until n.length()).firstNotNullOfOrNull { findHolder(n.opt(it)) }
        else -> null
    }

    /** Exportify-style CSV. Columns found by name, any order. */
    fun spotifyFromCsv(name: String, text: String): ExternalList {
        val rows = parseCsv(text)
        if (rows.size < 2) error("CSV is empty")
        val head = rows[0].map { it.trim().lowercase() }
        val ti = head.indexOfFirst { it in TITLE_COLS }
        val ai = head.indexOfFirst { it in ARTIST_COLS }
        if (ti < 0) error("No song name column in CSV")
        val tracks = rows.drop(1).mapNotNull { r ->
            val title = r.getOrNull(ti)?.trim().orEmpty()
            if (title.isEmpty()) null
            else ExternalTrack(title, if (ai >= 0) r.getOrNull(ai).orEmpty().replace(";", ", ").trim() else "")
        }
        if (tracks.isEmpty()) error("No songs in CSV")
        return ExternalList(name.ifBlank { "Spotify import" }, tracks)
    }

    // ---- matching ------------------------------------------------------------

    /** Finds each track on YT Music (4 at a time). Result keeps the original order; misses are dropped. */
    suspend fun match(tracks: List<ExternalTrack>, onProgress: (done: Int, total: Int) -> Unit): MatchResult =
        coroutineScope {
            val capped = tracks.take(MAX_TRACKS)
            val gate = Semaphore(4)
            val done = AtomicInteger(0)
            val results = capped.map { t ->
                async {
                    val song = gate.withPermit { matchOne(t) }
                    onProgress(done.incrementAndGet(), capped.size)
                    t to song
                }
            }.awaitAll()
            val missed = results.filter { it.second == null }.map { label(it.first.title, it.first.artist) } +
                tracks.drop(MAX_TRACKS).map { label(it.title, it.artist) + " (over $MAX_TRACKS limit)" }
            MatchResult(results.mapNotNull { it.second }.distinctBy { it.videoId }, missed)
        }

    private fun label(title: String, artist: String) = if (artist.isBlank()) title else "$title - $artist"

    private suspend fun matchOne(t: ExternalTrack): Song? {
        val hit = repo.search("${t.title} ${t.artist}".trim(), SONGS_FILTER).getOrNull()
            ?.firstOrNull { it.type == HomeItemType.Song && it.videoId != null } ?: return null
        return Song(hit.title, HistoryStore.cleanArtist(hit.subtitle), hit.videoId!!, hit.thumbnailUrl)
    }

    // ---- YouTube Music ---------------------------------------------------------

    suspend fun ytLibrary(): Result<List<HomeItem>> = repo.libraryPlaylists()

    /** "...?list=PLxxxx" -> "VLPLxxxx". Also accepts a bare "VL..." / "PL..." id. */
    fun parseYtLink(text: String): String? {
        val t = text.trim()
        YT_LIST.find(t)?.let { return "VL" + it.groupValues[1] }
        return if (Regex("^(VL)?(PL|RDCLAK|OLAK|LM)[A-Za-z0-9_-]+$").matches(t)) (if (t.startsWith("VL")) t else "VL$t") else null
    }

    /** Pulls every song of a YT Music playlist (up to [MAX_TRACKS]). Name = page title. */
    suspend fun ytPlaylist(browseId: String, fallbackName: String): YtResult {
        val page = repo.browse(browseId).mapCatching { PlaylistParser.parsePage(it) }.getOrThrow()
        val all = ArrayList(page.tracks)
        var token = page.continuation
        while (token != null && all.size < MAX_TRACKS) {
            val (more, next) = repo.browseContinuation(token).mapCatching { PlaylistParser.parseContinuation(it) }
                .getOrNull() ?: break
            all += more
            token = next
        }
        if (all.isEmpty()) error("No songs found")
        val songs = all.filter { it.videoId.isNotBlank() }
            .map { Song(it.title, it.artist, it.videoId, it.thumbnailUrl) }.distinctBy { it.videoId }
        val missed = all.filter { it.videoId.isBlank() }.map { label(it.title, it.artist) + " (unavailable)" }
        val name = page.header?.title?.takeIf { it.isNotBlank() } ?: fallbackName
        return YtResult(name, songs, missed, complete = token == null)
    }

    /** Reads a saved source ("yt:VL.." or "sp:playlist:ID") again. Used by Sync. */
    suspend fun fetchSource(source: String): RemoteList = when {
        source.startsWith("yt:") -> ytPlaylist(source.removePrefix("yt:"), "").let { RemoteList(it.songs, it.missed, it.complete) }
        source.startsWith("sp:") -> {
            val (_, type, id) = source.split(":")
            val list = spotifyFromLink(type, id)
            val m = match(list.tracks) { _, _ -> }
            // Spotify embed page shows about 100 tracks max. At that size the list may be cut.
            RemoteList(m.songs, m.missed, complete = list.tracks.size < SPOTIFY_EMBED_CAP)
        }
        else -> error("Unknown source")
    }

    // ---- csv -----------------------------------------------------------------

    private fun parseCsv(text: String): List<List<String>> {
        val rows = ArrayList<List<String>>()
        var row = ArrayList<String>()
        val cell = StringBuilder()
        var quoted = false
        var i = 0
        val s = text.removePrefix("﻿")
        while (i < s.length) {
            val c = s[i]
            when {
                quoted && c == '"' && s.getOrNull(i + 1) == '"' -> { cell.append('"'); i++ }
                c == '"' -> quoted = !quoted
                !quoted && c == ',' -> { row.add(cell.toString()); cell.clear() }
                !quoted && (c == '\n' || c == '\r') -> {
                    if (c == '\r' && s.getOrNull(i + 1) == '\n') i++
                    row.add(cell.toString()); cell.clear()
                    if (row.any { it.isNotBlank() }) rows.add(row)
                    row = ArrayList()
                }
                else -> cell.append(c)
            }
            i++
        }
        if (cell.isNotEmpty() || row.isNotEmpty()) { row.add(cell.toString()); if (row.any { it.isNotBlank() }) rows.add(row) }
        return rows
    }

    private companion object {
        const val MAX_TRACKS = 300
        const val SPOTIFY_EMBED_CAP = 100
        const val SONGS_FILTER = "EgWKAQIIAWoKEAkQBRAKEAMQBA%3D%3D"
        const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36"
        val SPOTIFY_LINK = Regex("open\\.spotify\\.com/(?:intl-[a-z]+/)?(?:embed/)?(playlist|album)/([A-Za-z0-9]+)")
        val SPOTIFY_URI = Regex("spotify:(playlist|album):([A-Za-z0-9]+)")
        val NEXT_DATA = Regex("<script id=\"__NEXT_DATA__\"[^>]*>(.*?)</script>", RegexOption.DOT_MATCHES_ALL)
        val YT_LIST = Regex("[?&]list=([A-Za-z0-9_-]+)")
        val TITLE_COLS = setOf("track name", "title", "name", "song", "track")
        val ARTIST_COLS = setOf("artist name(s)", "artist name", "artist", "artists")
    }
}
