package com.example.chord.data.lastfm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Last.fm web API. Free key from https://www.last.fm/api/account/create . Reads need only the key. Scrobbling signs through your signer server. */
object LastFmApi {
    const val KEY_URL = "https://www.last.fm/api/account/create"
    private const val BASE = "https://ws.audioscrobbler.com/2.0/"

    data class Track(val title: String, val artist: String, val match: Double)
    data class Album(val title: String, val artist: String)

    class ApiError(val code: Int, message: String) : IOException(message)

    private const val UA = "Chord/1.0 (personal music player)"

    private suspend fun call(key: String, method: String, params: Map<String, String>): JSONObject {
        val qs = params.entries.joinToString("") { "&${it.key}=${enc(it.value)}" }
        return send("$BASE?method=$method&api_key=${enc(key)}&format=json$qs", null)
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    /** GET, or POST when [body] is set. Last.fm and the signer both answer {"error":code,"message":..} on failure. */
    private suspend fun send(url: String, body: String?, json: Boolean = false): JSONObject = withContext(Dispatchers.IO) {
        val conn = URL(url).openConnection() as HttpURLConnection
        val onCancel = kotlin.coroutines.coroutineContext[kotlinx.coroutines.Job]
            ?.invokeOnCompletion { if (it != null) runCatching { conn.disconnect() } }   // cancelled = abort the socket
        try {
            conn.setRequestProperty("User-Agent", UA)
            conn.connectTimeout = 8_000
            conn.readTimeout = 15_000
            if (body != null) {
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", if (json) "application/json" else "application/x-www-form-urlencoded")
                conn.outputStream.use { it.write(body.toByteArray()) }
            }
            val code = conn.responseCode
            val text = (if (code < 400) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            val j = runCatching { JSONObject(text) }.getOrNull()
            if (j != null && j.has("error")) throw ApiError(j.optInt("error"), j.optString("message", "Last.fm error"))
            if (code !in 200..299 || j == null) throw IOException("HTTP $code")
            j
        } finally { onCancel?.dispose(); conn.disconnect() }
    }

    /** Cheap call that only works with a good key. */
    suspend fun validate(key: String): Result<Unit> = runCatching {
        call(key, "chart.gettopartists", mapOf("limit" to "1")); Unit
    }.recoverCatching {
        throw when {
            it is ApiError && it.code == 10 -> IOException("That key is not valid")
            it is ApiError -> IOException(it.message)
            else -> IOException("Can't reach Last.fm. Check internet")
        }
    }

    suspend fun similarTracks(key: String, artist: String, track: String, limit: Int = 30): List<Track> {
        val r = call(key, "track.getsimilar", mapOf("artist" to artist, "track" to track, "limit" to "$limit", "autocorrect" to "1"))
        return tracks(r.optJSONObject("similartracks")?.opt("track"))
    }

    suspend fun topTracks(key: String, artist: String, limit: Int = 5): List<Track> {
        val r = call(key, "artist.gettoptracks", mapOf("artist" to artist, "limit" to "$limit", "autocorrect" to "1"))
        return tracks(r.optJSONObject("toptracks")?.opt("track"))
    }

    suspend fun similarArtists(key: String, artist: String, limit: Int = 10): List<String> {
        val r = call(key, "artist.getsimilar", mapOf("artist" to artist, "limit" to "$limit", "autocorrect" to "1"))
        val a = r.optJSONObject("similarartists")?.opt("artist")
        return list(a).mapNotNull { it.optString("name").takeIf { n -> n.isNotBlank() } }
    }

    // ---------- profile import + tags (read only, same API key) ----------

    /** [period]: 7day, 1month, 12month or overall. */
    suspend fun userTopTracks(key: String, user: String, period: String, limit: Int = 50): List<Track> {
        val r = call(key, "user.gettoptracks", mapOf("user" to user, "period" to period, "limit" to "$limit"))
        return tracks(r.optJSONObject("toptracks")?.opt("track"))
    }

    suspend fun userTopArtists(key: String, user: String, period: String, limit: Int = 20): List<String> {
        val r = call(key, "user.gettopartists", mapOf("user" to user, "period" to period, "limit" to "$limit"))
        return list(r.optJSONObject("topartists")?.opt("artist")).mapNotNull { it.optString("name").takeIf { n -> n.isNotBlank() } }
    }

    /** Most recently scrobbled tracks from a public Last.fm profile. */
    suspend fun userRecentTracks(key: String, user: String, limit: Int = 20): List<Track> {
        val r = call(key, "user.getrecenttracks", mapOf("user" to user, "limit" to "$limit", "extended" to "0"))
        val root = r.optJSONObject("recenttracks")?.opt("track")
        return list(root).mapNotNull { t ->
            val title = t.optString("name").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val artistObject = t.optJSONObject("artist")
            val artist = artistObject?.optString("#text")
                ?.takeIf { it.isNotBlank() }
                ?: artistObject?.optString("name")?.takeIf { it.isNotBlank() }
                ?: t.optString("artist").takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            Track(title, artist, 0.0)
        }.distinctBy { it.title.lowercase() + "|" + it.artist.lowercase() }
    }

    /** Albums the listener has played most during the selected period. */
    suspend fun userTopAlbums(key: String, user: String, period: String, limit: Int = 12): List<Album> {
        val r = call(key, "user.gettopalbums", mapOf("user" to user, "period" to period, "limit" to "$limit"))
        return list(r.optJSONObject("topalbums")?.opt("album")).mapNotNull { a ->
            val title = a.optString("name").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val artist = a.optJSONObject("artist")?.optString("name").orEmpty()
            Album(title, artist)
        }
    }

    /** Tracks from one Last.fm album, preserving the album's own track order. */
    suspend fun albumTracks(key: String, album: Album, limit: Int = 30): List<Track> {
        val r = call(key, "album.getinfo", mapOf("artist" to album.artist, "album" to album.title, "autocorrect" to "1"))
        val root = r.optJSONObject("album")?.optJSONObject("tracks")?.opt("track")
        return list(root).take(limit).mapNotNull { t ->
            val title = t.optString("name").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            Track(title, album.artist, 0.0)
        }
    }

    /** Top tags of an artist as lowercase name -> weight 0..1. */
    suspend fun artistTags(key: String, artist: String, limit: Int = 10): Map<String, Double> {
        val r = call(key, "artist.gettoptags", mapOf("artist" to artist, "autocorrect" to "1"))
        val out = LinkedHashMap<String, Double>()
        list(r.optJSONObject("toptags")?.opt("tag")).take(limit).forEach {
            val n = it.optString("name").lowercase().trim()
            if (n.isNotBlank()) out[n] = (it.optDouble("count", 0.0) / 100.0).coerceIn(0.0, 1.0)
        }
        return out
    }

    // ---------- scrobbling ----------
    // Writes need a signature made with the shared secret. The secret lives only on the signer server
    // (see lastfm-signer/worker.js), never in the app. The app sends it the params, gets back api_key + api_sig,
    // then talks to Last.fm itself. The user's password never touches the app: sign-in is Last.fm's web page.

    data class Scrobble(val artist: String, val title: String, val ts: Long, val duration: Int = 0)

    private fun base(signer: String) = signer.trim().trimEnd('/')

    /** Step 1 of sign-in: (token, url to open in a browser). */
    suspend fun authStart(signer: String): Pair<String, String> {
        val r = send("${base(signer)}/auth/token", null)
        return r.getString("token") to r.getString("url")
    }

    class Session(val sk: String, val name: String, val apiKey: String)

    /** Step 2, after the user allowed Chord on the web page. Also hands back the public API key, so mixes work without pasting one. */
    suspend fun authFinish(signer: String, token: String): Session {
        val r = send("${base(signer)}/auth/session", JSONObject().put("token", token).toString(), json = true)
        return Session(r.getString("sk"), r.getString("name"), r.optString("key"))
    }

    private suspend fun signedPost(signer: String, params: Map<String, String>): JSONObject {
        val s = send("${base(signer)}/sign", JSONObject().put("params", JSONObject(params)).toString(), json = true)
        val all = params + mapOf("api_key" to s.getString("api_key"), "api_sig" to s.getString("api_sig"), "format" to "json")
        return send(BASE, all.entries.joinToString("&") { "${it.key}=${enc(it.value)}" })
    }

    /** Up to 50 per call (Last.fm limit). Throws [ApiError] / [IOException]. */
    suspend fun scrobble(signer: String, sk: String, items: List<Scrobble>) {
        require(items.size in 1..50)
        val p = LinkedHashMap<String, String>()
        p["method"] = "track.scrobble"; p["sk"] = sk
        items.forEachIndexed { i, s ->
            p["artist[$i]"] = s.artist; p["track[$i]"] = s.title; p["timestamp[$i]"] = s.ts.toString()
            if (s.duration > 0) p["duration[$i]"] = s.duration.toString()
        }
        signedPost(signer, p)
    }

    suspend fun nowPlaying(signer: String, sk: String, artist: String, title: String, duration: Int) {
        val p = mutableMapOf("method" to "track.updateNowPlaying", "sk" to sk, "artist" to artist, "track" to title)
        if (duration > 0) p["duration"] = duration.toString()
        signedPost(signer, p)
    }

    private fun list(n: Any?): List<JSONObject> = when (n) {
        is JSONArray -> (0 until n.length()).mapNotNull { n.optJSONObject(it) }
        is JSONObject -> listOf(n)
        else -> emptyList()
    }

    private fun tracks(n: Any?): List<Track> = list(n).mapNotNull { o ->
        val name = o.optString("name").takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val artistObject = o.optJSONObject("artist")
        val artist = artistObject?.optString("name")?.takeIf { it.isNotBlank() }
            ?: artistObject?.optString("#text")?.takeIf { it.isNotBlank() }
            ?: o.optString("artist").takeIf { it.isNotBlank() }
            ?: return@mapNotNull null
        Track(name, artist, o.optString("match", "0").toDoubleOrNull() ?: 0.0)
    }
}
