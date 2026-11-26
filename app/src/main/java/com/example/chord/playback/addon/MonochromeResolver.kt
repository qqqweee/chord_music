package com.example.chord.playback.addon

import android.util.Log
import com.example.chord.data.model.Song
import com.example.chord.playback.AudioStream
import com.example.chord.playback.ChordHttp
import com.example.chord.playback.ProviderHealth
import com.example.chord.playback.StreamInfoStore
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

/**
 * Native adapter for the public Monochrome track-streamer and compatible relay APIs.
 *
 * Uses the same public endpoints as Mochy's Monochrome source module:
 *   streamer: GET /search/tracks?q=...&limit=8, then GET /track/{trackId}  (the URL is the stream)
 *   relay:    GET /search?q=...&n=8, then GET /s/{id}?q=flac  (answers with a signed stream URL)
 *
 * Never handles Tidal credentials, shared sessions, or private account endpoints.
 */
object MonochromeResolver {
    private const val TAG = "ChordMonochrome"
    private const val DEFAULT_SERVER = "https://tracks.monochrome.st"
    // ===================== TUNABLES (safe to change) =====================
    /**
     * TUNABLE: quality asked from a STREAMER ("?quality=..."). LOSSLESS = 16-bit / 44.1 kHz FLAC,
     * which streams fine on a phone link. Hi-res 24-bit is 2-4x bigger and is what stalls.
     * Empty = send no parameter (server picks). NOT verified for tracks.monochrome.st: if every
     * song falls back to Opus after this change, set it to "".
     */
    private const val QUALITY = ""
    /** TUNABLE: after "song not found on Monochrome", do not search again for this long. */
    private const val MISS_TTL_MS = 30 * 60_000L
    /** TUNABLE: after HTTP 429/503, leave the server alone this long (or what its Retry-After header says). */
    private const val RATE_LIMIT_TTL_MS = 2 * 60_000L
    /** TUNABLE: least time between two API calls (search, relay link) to the server. Player stream reads are not counted. */
    private const val MIN_GAP_MS = 750L
    /** TUNABLE: how long a RELAY stream link (signed, expires) is remembered. */
    private const val MEMO_TTL_MS = 15 * 60_000L
    /** TUNABLE: how long a STREAMER link is remembered. Link is /track/{id}: stable, not signed, so long is safe. */
    private const val STREAMER_MEMO_TTL_MS = 2 * 60 * 60_000L
    // ====================================================================
    /** Relay answer only needs the headers. Never pull more. */
    private const val RELAY_PEEK_BYTES = 64
    private const val MAX_RESULTS = 8
    private const val MAX_SERVERS = 8
    private const val USER_AGENT =
        "Mozilla/5.0 (Android) Chord/1.0 (compatible; audio source)"

    enum class Kind { STREAMER, RELAY }

    data class Server(
        val baseUrl: String,
        val kind: Kind,
        val displayName: String = URI(baseUrl).host.orEmpty(),
        val builtIn: Boolean = false,
    )

    private data class Track(
        val id: String,
        val title: String,
        val artist: String,
        val album: String? = null,
        val durationMs: Long? = null,
        val isrc: String? = null,
    )

    private data class Memo(val stream: AudioStream, val at: Long)

    private val memo = ConcurrentHashMap<String, Memo>()
    private val misses = ConcurrentHashMap<String, Long>()
    @Volatile private var customServers: List<Server> = emptyList()
    private val lock = Any()

    fun servers(): List<Server> = listOf(defaultServer()) + customServers

    fun addServer(input: String): Server {
        val normalized = normalizeInput(input)
        val found = identify(normalized)
            ?: identify(originOf(normalized))
            ?: discoverServerFromSite(originOf(normalized))
            ?: throw IllegalArgumentException("This link is not a supported Monochrome streamer, relay, or Monochrome site.")
        synchronized(lock) {
            require(servers().none { it.baseUrl == found.baseUrl }) { "Server is already added." }
            require(customServers.size < MAX_SERVERS) { "You can add up to $MAX_SERVERS custom servers." }
            customServers = customServers + found
            persist()
        }
        return found
    }

    fun removeServer(baseUrl: String) {
        synchronized(lock) {
            customServers = customServers.filterNot { it.baseUrl == baseUrl }
            persist()
        }
        clearServerCache(baseUrl)
    }

    fun init(context: android.content.Context) {
        synchronized(lock) {
            val prefs = context.applicationContext.getSharedPreferences("chord_monochrome", 0)
            val raw = prefs.getString("servers", "[]").orEmpty()
            customServers = runCatching {
                val array = JSONArray(raw)
                List(array.length()) { i ->
                    val o = array.getJSONObject(i)
                    Server(
                        baseUrl = o.getString("url"),
                        kind = Kind.valueOf(o.getString("kind")),
                        displayName = o.optString("name").ifBlank { URI(o.getString("url")).host.orEmpty() },
                    )
                }.filter { isSafeBase(it.baseUrl) }.distinctBy { it.baseUrl }.take(MAX_SERVERS)
            }.getOrDefault(emptyList())
            preferenceContext = context.applicationContext
        }
    }

    @Volatile private var preferenceContext: android.content.Context? = null

    private fun persist() {
        val context = preferenceContext ?: return
        val json = JSONArray()
        customServers.forEach { server ->
            json.put(JSONObject().put("url", server.baseUrl).put("kind", server.kind.name).put("name", server.displayName))
        }
        context.getSharedPreferences("chord_monochrome", 0)
            .edit().putString("servers", json.toString()).apply()
    }

    /** One lookup per song at a time. Prefetch and player share the same work, never do it twice. */
    private val songLocks = ConcurrentHashMap<String, Any>()

    /**
     * Find the FLAC stream of a song. Streamer: the URL is returned at once, with no test request,
     * so only the player hits the server. Relay: one small request to get the signed URL.
     * Null = not on Monochrome (or every server failed): caller falls back.
     */
    fun resolve(song: Song): AudioStream? {
        val songLock = songLocks.computeIfAbsent(song.videoId) { Any() }
        return synchronized(songLock) {
            StreamInfoStore.setBusy(song.videoId, true)
            try {
                resolveInternal(song)
            } finally {
                StreamInfoStore.setBusy(song.videoId, false)
            }
        }
    }

    private fun resolveInternal(song: Song): AudioStream? {
        if (song.videoId.startsWith("local:")) return null
        val artist = com.example.chord.data.history.HistoryStore.cleanArtist(song.details)
        if (song.title.isBlank() || artist.isBlank()) return null
        val startedAt = System.currentTimeMillis()

        // A server in its rest period is NOT asked again. All resting = play from YouTube, no request sent.
        val servers = servers().filterNot { isDown(it) }
        if (servers.isEmpty()) Log.w(TAG, "all servers resting, no request sent for ${song.title}")
        for (server in servers) {
            val cacheKey = "${server.baseUrl}|${song.title}|$artist"
            memo[cacheKey]?.let {
                val ttl = if (server.kind == Kind.STREAMER) STREAMER_MEMO_TTL_MS else MEMO_TTL_MS
                if (System.currentTimeMillis() - it.at < ttl) return it.stream
                memo.remove(cacheKey, it)
            }
            // Disk map: this song was matched before. No search needed (the slow part).
            val saved = savedId(server, song.videoId)
            val missAt = misses[cacheKey]
            if (saved == null && missAt != null && System.currentTimeMillis() - missAt < MISS_TTL_MS) continue

            try {
                val trackId = saved ?: run {
                    val results = search(server, "$artist ${coreTitle(song.title)}")
                    val best = rank(song, artist, results).firstOrNull()
                    if (best == null) {
                        misses[cacheKey] = System.currentTimeMillis()
                        Log.i(TAG, "no match for '${song.title}' / '$artist': server gave ${results.size} results, none passed ranking")
                    }
                    best?.id
                } ?: continue

                val url = when (server.kind) {
                    Kind.STREAMER -> streamerUrl(server, trackId)
                    Kind.RELAY -> relayUrl(server, trackId)
                        ?: run {
                            if (saved != null) forgetSavedId(song)   // saved id went stale
                            throw IllegalStateException("No stream URL returned")
                        }
                }
                // Claimed, not proven: the label shown to the person comes from the decoder
                // (PlaybackQualityReporter), never from this.
                val stream = AudioStream(
                    url = url,
                    headers = mapOf("User-Agent" to USER_AGENT),
                    quality = "FLAC · Monochrome",
                    mimeType = "audio/flac",
                    provider = providerId(server),
                    trackKey = "$trackId|q=$QUALITY",
                )
                memo[cacheKey] = Memo(stream, System.currentTimeMillis())
                if (saved == null) saveId(server, song.videoId, trackId)
                Log.i(TAG, "Resolved ${song.title} from ${server.displayName} in ${System.currentTimeMillis() - startedAt} ms (saved id: ${saved != null})")
                return stream
            } catch (e: Exception) {
                // Lookup was cancelled (player stopped / song skipped). Not a server fault:
                // do not mark the server down. Pass the cancel up.
                if (e is InterruptedException || Thread.currentThread().isInterrupted ||
                    e.message?.contains("interrupted", ignoreCase = true) == true
                ) {
                    Thread.currentThread().interrupt()
                    throw java.io.InterruptedIOException("Monochrome lookup cancelled")
                }
                Log.w(TAG, "${server.displayName} failed for ${song.title}: ${e.message}")
                ProviderHealth.markFailed(providerId(server), (e as? RateLimited)?.waitMs ?: 0L)
            }
        }
        return null
    }

    fun isEnabled(): Boolean = true

    // ---- Disk map: YouTube id -> Monochrome track id. Search once per song, ever. ----
    private fun idPrefs() = preferenceContext?.getSharedPreferences("chord_monochrome_ids", 0)
    private fun idKey(server: Server, videoId: String) = "${server.baseUrl}|$videoId"
    private fun savedId(server: Server, videoId: String): String? =
        idPrefs()?.getString(idKey(server, videoId), null)
    private fun saveId(server: Server, videoId: String, trackId: String) {
        idPrefs()?.edit()?.putString(idKey(server, videoId), trackId)?.apply()
    }
    /** Person said "wrong song / upgrade", or the server said "no such track": next lookup searches again. */
    fun forgetSavedId(song: Song) {
        val prefs = idPrefs() ?: return
        val edit = prefs.edit()
        prefs.all.keys.filter { it.endsWith("|${song.videoId}") }.forEach { edit.remove(it) }
        edit.apply()
    }

    /** Drop saved result for this song, so next resolve asks fresh. */
    fun forget(song: Song) {
        val artist = com.example.chord.data.history.HistoryStore.cleanArtist(song.details)
        val tail = "|${song.title}|$artist"
        memo.keys.removeIf { it.endsWith(tail) }
        misses.keys.removeIf { it.endsWith(tail) }
    }

    private class RateLimited(val waitMs: Long) : Exception("Server rate limit, rest ${waitMs / 1000} s")

    private var lastCallAt = 0L

    /** Keep a gap between API calls, so quick skipping never turns into a burst of searches. */
    private fun throttle() {
        val wait = synchronized(this) {
            val now = System.currentTimeMillis()
            val at = maxOf(now, lastCallAt + MIN_GAP_MS)
            lastCallAt = at
            at - now
        }
        if (wait > 0) Thread.sleep(wait)   // interrupt (song skipped) is handled by the caller
    }

    /** One API call. 429/503 = the server asks us to slow down: stop asking for a while. */
    private fun apiCall(url: String, headers: Map<String, String>, range: String? = null, maxBytes: Int = 64 * 1024): HttpResult {
        throttle()
        val response = request(url, headers, range, maxBytes)
        if (response.code == 429 || response.code == 503) throw RateLimited(retryAfterMs(response.headers["retry-after"]))
        return response
    }

    private fun retryAfterMs(header: String?): Long =
        header?.trim()?.toLongOrNull()?.times(1000)?.coerceIn(5_000L, 10 * 60_000L) ?: RATE_LIMIT_TTL_MS

    /** Player got HTTP 429 on a stream. Rest every server, so the next songs do not add to the load. */
    fun backOff(retryAfterHeader: String?) {
        val ms = retryAfterMs(retryAfterHeader)
        servers().forEach { ProviderHealth.markFailed(providerId(it), ms) }
        Log.w(TAG, "rate limited by stream, resting ${ms / 1000} s")
    }

    private fun search(server: Server, query: String): List<Track> {
        val q = enc(query)
        val url = when (server.kind) {
            Kind.STREAMER -> "${server.baseUrl}/search/tracks?q=$q&limit=$MAX_RESULTS"
            Kind.RELAY -> "${server.baseUrl}/search?q=$q&n=$MAX_RESULTS"
        }
        val response = apiCall(url, searchHeaders(server.kind))
        if (response.code !in 200..299) error("Search HTTP ${response.code}")
        val root = JSONObject(response.body.toString(Charsets.UTF_8))
        val rows = root.optJSONArray(if (server.kind == Kind.STREAMER) "tracks" else "data") ?: JSONArray()
        return List(rows.length()) { rows.optJSONObject(it) }
            .filterNotNull()
            .mapNotNull { o ->
                if (server.kind == Kind.STREAMER && o.optBoolean("playable", true).not()) return@mapNotNull null
                val id = first(o, "trackId", "id")
                val title = first(o, "title")
                if (id.isBlank() || title.isBlank()) return@mapNotNull null
                val artists = o.optJSONArray("artistNames")
                val artist = if (artists != null) {
                    (0 until artists.length()).joinToString(", ") { artists.optString(it) }
                } else first(o, "artist").replace(Regex("""(?<! )&(?! )"""), ", ")
                val duration = o.optLong("duration", 0L).takeIf { it > 0 }?.let { if (it > 10_000) it else it * 1000 }
                Track(id, title, artist, first(o, "albumTitle", "releaseTitle", "album").ifBlank { null }, duration, first(o, "isrc").ifBlank { null })
            }
    }

    /** Streamer: /track/{id} is the stream itself. Stable, not signed. */
    private fun streamerUrl(server: Server, id: String): String =
        "${server.baseUrl}/track/${encPath(id)}" + if (QUALITY.isEmpty()) "" else "?quality=$QUALITY"

    /** Relay: first bytes come with a signed/temporary stream URL in a header. Playback uses that URL. */
    private fun relayUrl(server: Server, id: String): String? {
        val response = apiCall("${server.baseUrl}/s/${encPath(id)}?q=flac", relayHeaders, range = "bytes=0-41", maxBytes = RELAY_PEEK_BYTES)
        if (response.code !in 200..299) return null
        val link = response.headers.entries.firstOrNull { it.key.equals("x-relay-stream", true) }?.value ?: return null
        return validateRelayUrl(server.baseUrl, link)
    }

    private data class HttpResult(val code: Int, val body: ByteArray, val headers: Map<String, String>)

    private fun request(url: String, headers: Map<String, String>, range: String?, maxBytes: Int = 64 * 1024): HttpResult {
        require(url.startsWith("https://")) { "Only HTTPS source URLs are allowed." }
        val builder = okhttp3.Request.Builder().url(url)
        headers.forEach { (k, v) -> builder.header(k, v) }
        range?.let { builder.header("Range", it) }
        ChordHttp.api.newCall(builder.build()).execute().use { resp ->
            val body = resp.body?.source()?.inputStream()?.let { input ->
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8 * 1024)
                var total = 0
                while (total < maxBytes) {
                    val count = input.read(buffer, 0, minOf(buffer.size, maxBytes - total))
                    if (count < 0) break
                    out.write(buffer, 0, count)
                    total += count
                }
                out.toByteArray()
            } ?: ByteArray(0)
            val responseHeaders = resp.headers.names().associate { it.lowercase(Locale.ROOT) to (resp.header(it) ?: "") }
            return HttpResult(resp.code, body, responseHeaders)
        }
    }

    private fun rank(song: Song, artist: String, tracks: List<Track>): List<Track> {
        val wantedTitle = norm(song.title)
        val wantedArtist = words(artist).toSet()
        return tracks.mapNotNull { candidate ->
            val candidateTitle = norm(candidate.title)
            val candidateArtist = words(candidate.artist).toSet()
            val title = similarity(wantedTitle, candidateTitle)
            val artistOverlap = if (wantedArtist.isEmpty()) 0f else
                wantedArtist.intersect(candidateArtist).size.toFloat() / wantedArtist.size
            val duration = if (candidate.durationMs == null) 0.6f else {
                val expected = songDurationMs(song)
                if (expected == null) 0.6f else (1f - abs(expected - candidate.durationMs).toFloat() / 20_000f).coerceIn(0f, 1f)
            }
            val variantPenalty = if (variants(song.title) == variants(candidate.title)) 0f else 0.25f
            val score = 0.55f * title + 0.35f * artistOverlap + 0.10f * duration - variantPenalty
            if (title >= 0.65f && artistOverlap >= 0.4f && score >= 0.68f) candidate to score else null
        }.sortedByDescending { it.second }.map { it.first }
    }

    // Current Song model has no duration; placeholder deliberately keeps matching title/artist-driven.
    private fun songDurationMs(song: Song): Long? = null

    /**
     * Mochy accepts a Monochrome website link as well as a direct server URL. Its website
     * bundle names the track server; inspect only same-origin script assets and validate each
     * discovered candidate by its actual search response before storing it.
     */
    private fun discoverServerFromSite(origin: String): Server? {
        val page = runCatching { request(origin, pageHeaders, null) }.getOrNull() ?: return null
        if (page.code !in 200..299) return null
        val html = page.body.toString(Charsets.UTF_8)
        val scriptPattern = Regex("""<script[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
        val serverPattern = Regex("""https://[a-z0-9.-]+""", RegexOption.IGNORE_CASE)
        val scripts = scriptPattern.findAll(html).mapNotNull { match ->
            runCatching { URI(origin).resolve(match.groupValues[1]).toString() }.getOrNull()
        }.filter { it.startsWith("$origin/") }.take(4)
        val candidates = scripts.flatMap { script ->
            val content = runCatching { request(script, pageHeaders, null) }.getOrNull()
                ?.takeIf { it.code in 200..299 }?.body?.toString(Charsets.UTF_8).orEmpty()
            serverPattern.findAll(content).map { it.value }.asSequence()
        }.filter { candidate ->
            val host = runCatching { URI(candidate).host.orEmpty() }.getOrDefault("")
            host.startsWith("tracks.") || "relay" in host
        }.distinct().take(8)
        return candidates.firstNotNullOfOrNull { identify(it) }
    }

    private fun identify(base: String): Server? {
        val normalized = normalizeBase(base) ?: return null
        for (kind in Kind.entries) {
            val testUrl = when (kind) {
                Kind.STREAMER -> "$normalized/search/tracks?q=test&limit=1"
                Kind.RELAY -> "$normalized/search?q=test&n=1"
            }
            val result = runCatching { request(testUrl, searchHeaders(kind), null) }.getOrNull() ?: continue
            if (result.code !in 200..299) continue
            val root = runCatching { JSONObject(result.body.toString(Charsets.UTF_8)) }.getOrNull() ?: continue
            val key = if (kind == Kind.STREAMER) "tracks" else "data"
            if (root.optJSONArray(key) != null) return Server(normalized, kind)
        }
        return null
    }

    private fun normalizeInput(input: String): String {
        val value = input.trim().let { if ("://" in it) it else "https://$it" }
        val uri = URI(value)
        require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null) {
            "Enter a valid HTTPS Monochrome server or website URL without embedded credentials."
        }
        val path = uri.rawPath.orEmpty()
            .removeSuffix("/search/tracks")
            .removeSuffix("/search")
            .removeSuffix("/manifest.json")
            .trimEnd('/')
        return "${uri.scheme}://${uri.rawAuthority}$path".takeIf { it.isNotBlank() }
            ?: error("Invalid server URL")
    }

    private fun normalizeBase(value: String): String? {
        val uri = runCatching { URI(value) }.getOrNull() ?: return null
        if (uri.scheme != "https" || uri.host.isNullOrBlank() || uri.userInfo != null) return null
        return "${uri.scheme}://${uri.rawAuthority}${uri.rawPath.orEmpty()}".trimEnd('/')
    }

    private fun validateRelayUrl(base: String, candidate: String): String? {
        val origin = originOf(base)
        val parsed = runCatching { URI(candidate) }.getOrNull() ?: return null
        if (parsed.scheme != "https" || parsed.host.isNullOrBlank()) return null
        val sameOrigin = "${parsed.scheme}://${parsed.rawAuthority}" == origin
        if (!sameOrigin) return null
        return parsed.toString()
    }

    private fun isSafeBase(value: String): Boolean = normalizeBase(value) == value
    private fun originOf(value: String): String = runCatching {
        val u = URI(value); "${u.scheme}://${u.rawAuthority}"
    }.getOrDefault(value)
    private fun providerId(server: Server) = "monochrome:${server.baseUrl}"
    private fun isDown(server: Server): Boolean = ProviderHealth.isDown(providerId(server))
    private fun clearServerCache(base: String) {
        memo.keys.removeIf { it.startsWith("$base|") }
        misses.keys.removeIf { it.startsWith("$base|") }
        ProviderHealth.clear("monochrome:$base")
    }
    private fun defaultServer() = Server(DEFAULT_SERVER, Kind.STREAMER, "Monochrome", builtIn = true)
    private fun searchHeaders(kind: Kind) = if (kind == Kind.RELAY) relayHeaders else headers
    private val headers = mapOf("Accept" to "application/json", "User-Agent" to USER_AGENT)
    private val pageHeaders = mapOf("User-Agent" to USER_AGENT)
    private val relayHeaders = headers + ("X-Relay-Client" to "relay/2")
    private fun coreTitle(title: String) = title.replace(Regex("""\([^)]*\)|\[[^]]*]"""), " ").replace(Regex("""\s+"""), " ").trim()
    private fun norm(s: String) = s.lowercase(Locale.ROOT).replace(Regex("""\([^)]*\)|\[[^]]*]"""), "").replace(Regex("""[^\p{L}\p{N}]+"""), " ").trim()
    private fun words(s: String) = norm(s).split(' ').filter { it.length > 1 && it !in setOf("the", "and") }
    private fun similarity(a: String, b: String): Float {
        if (a.isBlank() || b.isBlank()) return 0f
        if (a == b) return 1f
        val x = a.split(' ').toSet(); val y = b.split(' ').toSet()
        return (x intersect y).size.toFloat() / (x union y).size
    }
    private fun variants(s: String): Set<String> = listOf("live", "remix", "acoustic", "instrumental", "karaoke", "sped up", "slowed", "nightcore", "cover", "demo")
        .filter { Regex("""\b${Regex.escape(it)}\b""").containsMatchIn(s.lowercase(Locale.ROOT)) }.toSet()
    private fun first(o: JSONObject, vararg keys: String) = keys.firstNotNullOfOrNull { k ->
        o.opt(k)?.takeIf { it != JSONObject.NULL }?.toString()?.takeIf { it.isNotBlank() }
    }.orEmpty()
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
    private fun encPath(s: String) = enc(s).replace("%2F", "%252F")
}