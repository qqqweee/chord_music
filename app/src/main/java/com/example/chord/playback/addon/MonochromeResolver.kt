package com.example.chord.playback.addon

import android.util.Log
import com.example.chord.data.model.Song
import com.example.chord.playback.AudioStream
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
 *   streamer: GET /search/tracks?q=...&limit=8, then GET /track/{trackId}
 *   relay:    GET /search?q=...&n=8, then GET /s/{id}?q=flac
 *
 * Never handles Tidal credentials, shared sessions, or private account endpoints.
 */
object MonochromeResolver {
    private const val TAG = "ChordMonochrome"
    private const val DEFAULT_SERVER = "https://tracks.monochrome.st"
    // Short on purpose: a dead server must not hold the song for 20+ s. Next server is tried after this.
    private const val CONNECT_TIMEOUT_MS = 6_000
    private const val READ_TIMEOUT_MS = 12_000
    /** Header probe only needs the first FLAC bytes. Never pull more on a slow link. */
    private const val PROBE_BYTES = 64
    private const val MISS_TTL_MS = 30 * 60_000L
    private const val FAILURE_TTL_MS = 10 * 60_000L
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

    private data class Opened(val url: String, val header: ByteArray)

    private data class Memo(val stream: AudioStream, val at: Long)

    private val memo = ConcurrentHashMap<String, Memo>()
    private val misses = ConcurrentHashMap<String, Long>()
    private val failedAt = ConcurrentHashMap<String, Long>()
    /** Songs whose last lookup died by error (net, timeout). Not the same as "no match". */
    private val errored = ConcurrentHashMap.newKeySet<String>()
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
        errored.remove(song.videoId)

        val servers = servers().filterNot { isDown(it) }.ifEmpty { servers() }
        for (server in servers) {
            val cacheKey = "${server.baseUrl}|${song.title}|$artist"
            memo[cacheKey]?.let {
                if (System.currentTimeMillis() - it.at < MEMO_TTL_MS) return it.stream
                memo.remove(cacheKey, it)
            }
            val missAt = misses[cacheKey]
            if (missAt != null && System.currentTimeMillis() - missAt < MISS_TTL_MS) continue

            try {
                val results = search(server, "$artist ${coreTitle(song.title)}")
                val best = rank(song, artist, results).firstOrNull()
                if (best == null) {
                    misses[cacheKey] = System.currentTimeMillis()
                    continue
                }
                val opened = open(server, best.id) ?: throw IllegalStateException("No stream URL returned")
                val info = parseFlacInfo(opened.header)
                    ?: throw IllegalStateException("Server returned audio that is not FLAC")

                val stream = AudioStream(
                    url = opened.url,
                    headers = mapOf("User-Agent" to USER_AGENT),
                    quality = info.label + " · Monochrome",
                    mimeType = "audio/flac",
                )
                memo[cacheKey] = Memo(stream, System.currentTimeMillis())
                StreamInfoStore.put(song.videoId, stream.quality)
                clearDown(server)
                Log.i(TAG, "Resolved ${song.title} from ${server.displayName}: ${info.label}")
                return stream
            } catch (e: Exception) {
                Log.w(TAG, "${server.displayName} failed for ${song.title}: ${e.message}")
                errored.add(song.videoId)
                markDown(server)
            }
        }
        return null
    }

    fun isEnabled(): Boolean = true

    /** True if the last lookup for this song failed by error, so a retry may work. */
    fun failedByError(song: Song): Boolean = song.videoId in errored

    /**
     * Drop saved result for this song, so next resolve asks fresh.
     */
    fun forget(song: Song) {
        val artist = com.example.chord.data.history.HistoryStore.cleanArtist(song.details)
        val tail = "|${song.title}|$artist"

        memo.keys.removeIf { it.endsWith(tail) }
        misses.keys.removeIf { it.endsWith(tail) }
    }

    /** Changes the cache key when the configured server list changes. */
    fun cacheSignature(): String = servers().joinToString("|") { "${it.baseUrl}:${it.kind}" }.hashCode().toUInt().toString(16)

    private val MEMO_TTL_MS = 15 * 60_000L

    private fun search(server: Server, query: String): List<Track> {
        val q = enc(query)
        val url = when (server.kind) {
            Kind.STREAMER -> "${server.baseUrl}/search/tracks?q=$q&limit=$MAX_RESULTS"
            Kind.RELAY -> "${server.baseUrl}/search?q=$q&n=$MAX_RESULTS"
        }
        val response = request(url, searchHeaders(server.kind), range = null)
        if (response.code !in 200..299) error("Search HTTP ${response.code}")
        clearDown(server)
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

    private fun open(server: Server, id: String): Opened? {
        return when (server.kind) {
            Kind.STREAMER -> {
                val url = "${server.baseUrl}/track/${encPath(id)}"
                val head = request(url, streamHeaders, range = "bytes=0-41", maxBytes = PROBE_BYTES)
                if (head.code !in 200..299) null else Opened(url, head.body)
            }
            Kind.RELAY -> {
                val response = request("${server.baseUrl}/s/${encPath(id)}?q=flac", relayHeaders, range = "bytes=0-41", maxBytes = PROBE_BYTES)
                if (response.code !in 200..299) return null
                val link = response.headers.entries.firstOrNull { it.key.equals("x-relay-stream", true) }?.value
                    ?: return null
                val safe = validateRelayUrl(server.baseUrl, link) ?: return null
                // The relay returns the first bytes and a signed/temporary stream URL. Playback uses that URL.
                Opened(safe, response.body)
            }
        }
    }

    private data class HttpResult(val code: Int, val body: ByteArray, val headers: Map<String, String>)

    private fun request(url: String, headers: Map<String, String>, range: String?, maxBytes: Int = 64 * 1024): HttpResult {
        require(url.startsWith("https://")) { "Only HTTPS source URLs are allowed." }
        val connection = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        try {
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.instanceFollowRedirects = true
            connection.requestMethod = "GET"
            headers.forEach { (k, v) -> connection.setRequestProperty(k, v) }
            range?.let { connection.setRequestProperty("Range", it) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.use { input ->
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
            val responseHeaders = connection.headerFields.entries
                .filter { it.key != null && it.value?.isNotEmpty() == true }
                .associate { it.key.lowercase(Locale.ROOT) to it.value!!.first() }
            return HttpResult(code, body, responseHeaders)
        } finally {
            connection.disconnect()
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

    internal fun parseFlacInfo(bytes: ByteArray): FlacInfo? {
        if (bytes.size < 4 || bytes.copyOfRange(0, 4).decodeToString() != "fLaC") return null
        if (bytes.size < 26) return FlacInfo(16, 44_100)
        val b = bytes.copyOfRange(8, 26).map { it.toInt() and 0xff }
        val rate = (b[10] shl 12) or (b[11] shl 4) or (b[12] ushr 4)
        val bits = (((b[12] and 0x01) shl 4) or (b[13] ushr 4)) + 1
        if (rate !in 8_000..768_000 || bits !in 8..32) return null
        return FlacInfo(bits, rate)
    }

    internal data class FlacInfo(val bits: Int, val rate: Int) {
        val hiRes get() = bits > 16 || rate > 48_000
        val label: String
            get() {
                val khz = if (rate % 1000 == 0) "${rate / 1000}" else String.format(Locale.US, "%.1f", rate / 1000.0)
                return "${if (hiRes) "Hi-Res FLAC" else "FLAC"} · $bits-bit / $khz kHz"
            }
    }


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
    private fun isDown(server: Server): Boolean =
        failedAt[server.baseUrl]?.let { System.currentTimeMillis() - it < FAILURE_TTL_MS } == true
    private fun markDown(server: Server) { failedAt[server.baseUrl] = System.currentTimeMillis() }
    private fun clearDown(server: Server) { failedAt.remove(server.baseUrl) }
    private fun clearServerCache(base: String) {
        memo.keys.removeIf { it.startsWith("$base|") }
        misses.keys.removeIf { it.startsWith("$base|") }
        failedAt.remove(base)
    }
    private fun defaultServer() = Server(DEFAULT_SERVER, Kind.STREAMER, "Monochrome", builtIn = true)
    private fun searchHeaders(kind: Kind) = if (kind == Kind.RELAY) relayHeaders else headers
    private val headers = mapOf("Accept" to "application/json", "User-Agent" to USER_AGENT)
    private val pageHeaders = mapOf("User-Agent" to USER_AGENT)
    private val streamHeaders = mapOf("User-Agent" to USER_AGENT)
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