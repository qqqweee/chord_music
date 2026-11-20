package com.example.chord.data.repository

import com.example.chord.ui.lyrics.KugouProvider
import com.example.chord.ui.lyrics.LrcLibProvider
import com.example.chord.ui.lyrics.LyricsQuery
import com.example.chord.ui.lyrics.NetEaseProvider
import com.example.chord.ui.lyrics.QQMusicProvider
import com.example.chord.ui.lyrics.SimpMusicProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlinx.coroutines.CancellationException
import android.util.Log


data class LyricsResult(
    val plainLyrics: String?,
    val syncedLyrics: String?,
    val providerName: String
) {
    /** Synced if we have it, else plain. */
    val best: String? get() = syncedLyrics ?: plainLyrics
}

/** One provider finished. result == null means "not found". */
data class ProviderOutcome(val providerName: String, val result: LyricsResult?)

class LyricsRepository {

    private class Source(val name: String, val run: suspend (LyricsQuery) -> LyricsResult?)

    // Order = priority. Word-synced NetEase first, then line-synced, then plain fallbacks.
    private val sources: List<Source> = listOf(
        ext("NetEase") { NetEaseProvider().fetch(it) },
        ext("SimpMusic") { SimpMusicProvider.fetch(it) },
        ext("QQMusic") { QQMusicProvider.fetch(it) },
        ext("Kugou") { KugouProvider.fetch(it) },
        ext("LRCLIB") { LrcLibProvider.fetch(it) },
        Source("LRCLIB Direct") { q -> withContext(Dispatchers.IO) { fetchFromLrclibGet(q.title, q.artist, q.durationMs) } },
        Source("lrc.red") { q -> withContext(Dispatchers.IO) { fetchFromLrcRed(q.title, q.artist) } },
        Source("PaxSenix") { q -> withContext(Dispatchers.IO) { fetchFromPaxSenix(q.title, q.artist) } },
        Source("LRCLIB Search") { q -> withContext(Dispatchers.IO) { fetchFromLrclibSearch(q.title, q.artist) } },
    )

    /** All provider names, in priority order. Use this to fill the provider sheet. */
    val providerNames: List<String> get() = sources.map { it.name }

    private fun buildQuery(title: String, artist: String, durationMs: Long, videoId: String?): LyricsQuery? {
        val cleanTitle = sanitizeTitle(title)
        if (cleanTitle.isBlank()) return null
        return LyricsQuery(cleanTitle, sanitizeArtist(artist), durationMs, videoId)
    }

    /**
     * Run every provider at the same time. Each one emits as soon as it is done,
     * so UI can show first hit fast and mark the others Found / NotFound later.
     */
    fun lyricsFlow(
        title: String,
        artist: String,
        durationMs: Long = 0L,
        videoId: String? = null,
    ): Flow<ProviderOutcome> = channelFlow {
        val q = buildQuery(title, artist, durationMs, videoId) ?: return@channelFlow
        sources.forEach { s ->
            launch(Dispatchers.IO) {
                val r = try {
                    s.run(q)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e("Lyrics", "${s.name} FAILED: ${e::class.simpleName} ${e.message}")
                    null
                }
                Log.d("Lyrics", "${s.name} -> ${if (r == null) "none" else "ok"} (title='${q.title}' artist='${q.artist}' dur=${q.durationMs})")
                send(ProviderOutcome(s.name, r))
            }
        }
    }

    /** Fetch from ONE provider by name. Use when user taps a provider in the sheet. */
    suspend fun fetchFrom(
        providerName: String,
        title: String,
        artist: String,
        durationMs: Long = 0L,
        videoId: String? = null,
    ): LyricsResult? = withContext(Dispatchers.IO) {
        val q = buildQuery(title, artist, durationMs, videoId) ?: return@withContext null
        val s = sources.firstOrNull { it.name == providerName } ?: return@withContext null
        runCatching { s.run(q) }.getOrNull()
    }

    /** Old API. Still works. Returns hits in priority order. */
    suspend fun getAllLyrics(
        title: String,
        artist: String,
        durationMs: Long = 0L,
        videoId: String? = null,
    ): List<LyricsResult> {
        val order = providerNames
        return lyricsFlow(title, artist, durationMs, videoId).toList()
            .mapNotNull { it.result }
            .distinctBy { it.providerName }
            .sortedBy { order.indexOf(it.providerName) }
    }

    private fun ext(name: String, fetch: suspend (LyricsQuery) -> String?) = Source(name) { q ->
        runCatching { fetch(q) }.getOrNull()
            ?.takeIf { isValidLyricsText(it) }
            ?.let { LyricsResult(null, it, name) }
    }

    private fun fetchFromLrclibGet(title: String, artist: String, durationMs: Long): LyricsResult? {
        if (artist.isBlank()) return null
        val durationSec = if (durationMs > 0) durationMs / 1000 else 0
        var urlStr = "https://lrclib.net/api/get?track_name=${encode(title)}&artist_name=${encode(artist)}"
        if (durationSec > 0) urlStr += "&duration=$durationSec"

        return makeHttpCall(urlStr) { response ->
            parseLrclibObject(JSONObject(response), "LRCLIB Direct")
        }
    }

    private fun fetchFromLrcRed(title: String, artist: String): LyricsResult? {
        val urlStr = "https://lrc.red/api/lyrics?title=${encode(title)}&artist=${encode(artist)}"
        return makeHttpCall(urlStr) { response ->
            val json = JSONObject(response)
            val synced = (json.optString("syncedLyrics").trim().takeIf { isValidLyricsText(it) }
                ?: json.optString("lrc").trim().takeIf { isValidLyricsText(it) })
            val plain = json.optString("plainLyrics").trim().takeIf { isValidLyricsText(it) }

            if (synced != null || plain != null) LyricsResult(plain, synced, "lrc.red") else null
        }
    }

    private fun fetchFromPaxSenix(title: String, artist: String): LyricsResult? {
        val urlStr = "https://paxsenix.org/lyrics?title=${encode(title)}&artist=${encode(artist)}"
        return makeHttpCall(urlStr) { response ->
            val json = JSONObject(response)
            val synced = json.optString("syncedLyrics").trim().takeIf { isValidLyricsText(it) }
            val plain = json.optString("plainLyrics").trim().takeIf { isValidLyricsText(it) }

            if (synced != null || plain != null) LyricsResult(plain, synced, "PaxSenix") else null
        }
    }

    private fun fetchFromLrclibSearch(title: String, artist: String): LyricsResult? {
        val query = if (artist.isNotBlank()) "$title $artist" else title
        val urlString = "https://lrclib.net/api/search?q=${encode(query)}"

        return makeHttpCall(urlString) { response ->
            val array = JSONArray(response)
            if (array.length() > 0) parseLrclibObject(array.getJSONObject(0), "LRCLIB Search") else null
        }
    }

    private fun parseLrclibObject(json: JSONObject, provider: String): LyricsResult? {
        val synced = json.optString("syncedLyrics").trim().takeIf { isValidLyricsText(it) }
        val plain = json.optString("plainLyrics").trim().takeIf { isValidLyricsText(it) }
        return if (synced != null || plain != null) LyricsResult(plain, synced, provider) else null
    }

    private fun isValidLyricsText(text: String) =
        text.isNotBlank() && text.length > 10 && !text.equals("null", ignoreCase = true)

    private fun <T> makeHttpCall(urlString: String, parser: (String) -> T?): T? {
        var connection: HttpURLConnection? = null
        return try {
            val url = URL(urlString)
            connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("User-Agent", "ChordPlayer/1.0 (Android)")
                connectTimeout = 4000
                readTimeout = 4000
            }
            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                parser(response)
            } else null
        } catch (e: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }

    private fun sanitizeTitle(title: String) = title
        .replace(Regex("(?i)\\b(official video|official audio|lyric video|full song|4k|hd)\\b"), "")
        .replace(Regex("\\(.*?\\)"), "")
        .replace(Regex("\\[.*?\\]"), "").replace(Regex("•.*"), "").replace(Regex("-.*"), "").trim()

    private fun sanitizeArtist(artist: String) = artist
        .replace(Regex("(?i)Song\\s*•\\s*"), "").replace(Regex("(?i)•\\s*\\d+M?\\s*plays?"), "")
        .replace(Regex("(?i)•\\s*\\d{4}"), "").replace(Regex("(?i)- Topic"), "").replace(Regex("•.*"), "").trim()

    private fun encode(value: String) = URLEncoder.encode(value, "UTF-8")
}