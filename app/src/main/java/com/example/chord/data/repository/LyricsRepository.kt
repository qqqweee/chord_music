package com.example.chord.data.repository

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class LyricsResult(
    val plainLyrics: String?,
    val syncedLyrics: String?,
    val providerName: String
)

class LyricsRepository {

    suspend fun getLyrics(title: String, artist: String, durationMs: Long = 0L): LyricsResult? = withContext(Dispatchers.IO) {
        val cleanTitle = sanitizeTitle(title)
        val cleanArtist = sanitizeArtist(artist)

        if (cleanTitle.isBlank()) return@withContext null

        Log.d(TAG, "Fetching lyrics for '$cleanTitle' by '$cleanArtist'")

        // 1. BetterLyrics / LRCLIB Direct
        if (cleanArtist.isNotBlank()) {
            val durationSec = if (durationMs > 0) durationMs / 1000 else 0
            var urlStr = "https://lrclib.net/api/get?track_name=${encode(cleanTitle)}&artist_name=${encode(cleanArtist)}"
            if (durationSec > 0) urlStr += "&duration=$durationSec"

            val directResult = fetchFromLrclibGet(urlStr, "BetterLyrics Direct")
            if (directResult != null) return@withContext directResult
        }

        // 2. lrc.red
        val lrcRedResult = fetchFromLrcRed(cleanTitle, cleanArtist)
        if (lrcRedResult != null) return@withContext lrcRedResult

        // 3. PaxSenix Proxy
        val paxResult = fetchFromPaxSenix(cleanTitle, cleanArtist)
        if (paxResult != null) return@withContext paxResult

        // 4. LRCLIB Search Fallback (Title + Artist)
        if (cleanArtist.isNotBlank()) {
            val searchUrl = "https://lrclib.net/api/search?q=${encode("$cleanTitle $cleanArtist")}"
            val searchResult = fetchFromLrclibSearch(searchUrl, "BetterLyrics Search")
            if (searchResult != null) return@withContext searchResult
        }

        // 5. LRCLIB Search Fallback (Title Only)
        val titleSearchUrl = "https://lrclib.net/api/search?q=${encode(cleanTitle)}"
        val titleResult = fetchFromLrclibSearch(titleSearchUrl, "BetterLyrics Title Search")
        if (titleResult != null) return@withContext titleResult

        Log.w(TAG, "No lyrics found across all providers for '$cleanTitle'")
        return@withContext null
    }

    private fun fetchFromLrclibGet(urlString: String, provider: String): LyricsResult? {
        return makeHttpCall(urlString) { response ->
            val json = JSONObject(response)
            val synced = json.optString("syncedLyrics").trim().takeIf { isValidLyricsText(it) }
            val plain = json.optString("plainLyrics").trim().takeIf { isValidLyricsText(it) }

            if (synced != null || plain != null) {
                LyricsResult(plain, synced, provider)
            } else null
        }
    }

    private fun fetchFromLrcRed(title: String, artist: String): LyricsResult? {
        val urlStr = "https://lrc.red/api/lyrics?title=${encode(title)}&artist=${encode(artist)}"
        return makeHttpCall(urlStr) { response ->
            val json = JSONObject(response)
            val synced = (json.optString("syncedLyrics").trim().takeIf { isValidLyricsText(it) }
                ?: json.optString("lrc").trim().takeIf { isValidLyricsText(it) })
            val plain = json.optString("plainLyrics").trim().takeIf { isValidLyricsText(it) }

            if (synced != null || plain != null) {
                LyricsResult(plain, synced, "lrc.red")
            } else null
        }
    }

    private fun fetchFromPaxSenix(title: String, artist: String): LyricsResult? {
        val urlStr = "https://paxsenix.org/lyrics?title=${encode(title)}&artist=${encode(artist)}"
        return makeHttpCall(urlStr) { response ->
            val json = JSONObject(response)
            val synced = json.optString("syncedLyrics").trim().takeIf { isValidLyricsText(it) }
            val plain = json.optString("plainLyrics").trim().takeIf { isValidLyricsText(it) }

            if (synced != null || plain != null) {
                LyricsResult(plain, synced, "PaxSenix")
            } else null
        }
    }

    private fun fetchFromLrclibSearch(urlString: String, provider: String): LyricsResult? {
        return makeHttpCall(urlString) { response ->
            val array = JSONArray(response)
            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                val synced = item.optString("syncedLyrics").trim().takeIf { isValidLyricsText(it) }
                val plain = item.optString("plainLyrics").trim().takeIf { isValidLyricsText(it) }

                if (synced != null || plain != null) {
                    return@makeHttpCall LyricsResult(plain, synced, provider)
                }
            }
            null
        }
    }

    private fun isValidLyricsText(text: String): Boolean {
        // Ensures string is non-empty and contains actual readable text lines
        return text.isNotBlank() && text.length > 10 && !text.equals("null", ignoreCase = true)
    }

    private fun <T> makeHttpCall(urlString: String, parser: (String) -> T?): T? {
        var connection: HttpURLConnection? = null
        return try {
            val url = URL(urlString)
            connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("User-Agent", "ChordPlayer/1.0 (Android)")
                connectTimeout = 3000
                readTimeout = 3000
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

    private fun sanitizeTitle(title: String): String {
        return title
            .replace(Regex("(?i)\\b(official video|official audio|lyric video|full song|4k|hd)\\b"), "")
            .replace(Regex("\\(.*?\\)"), "")
            .replace(Regex("\\[.*?\\]"), "")
            .replace(Regex("•.*"), "")
            .replace(Regex("-.*"), "")
            .trim()
    }

    private fun sanitizeArtist(artist: String): String {
        return artist
            .replace(Regex("(?i)Song\\s*•\\s*"), "")
            .replace(Regex("(?i)•\\s*\\d+M?\\s*plays?"), "")
            .replace(Regex("(?i)•\\s*\\d{4}"), "")
            .replace(Regex("(?i)- Topic"), "")
            .replace(Regex("•.*"), "")
            .trim()
    }

    private fun encode(value: String): String {
        return URLEncoder.encode(value, "UTF-8")
    }

    companion object {
        private const val TAG = "LyricsRepository"
    }
}