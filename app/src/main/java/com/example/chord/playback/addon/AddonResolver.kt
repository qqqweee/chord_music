package com.example.chord.playback.addon

import android.content.Context
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import com.example.chord.data.model.Song
import com.example.chord.playback.AudioCacheManager
import com.example.chord.playback.ProviderHealth
import com.example.chord.playback.AudioStream
import com.example.chord.playback.StreamInfoStore
import com.example.chord.playback.StreamResolver
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Locale

/** Addon resolver using BitChord's quality-aware addon request convention. */
object AddonResolver {
    private const val TAG = "ChordAddon"
    private const val LOSSLESS = "LOSSLESS"

    private class Memo(val stream: AudioStream, val at: Long)

    /**
     * Same song asked twice in a short time (seek, re-open, play then download) reuses the answer.
     * Before, every re-open searched the addon again, was slow, and could pick another file.
     */
    private val memo = java.util.concurrent.ConcurrentHashMap<String, Memo>()
    private const val MEMO_MS = 15 * 60_000L

    /** Score that means "title and artist both match". Stop looking when we reach it. */
    private const val GOOD_MATCH = 80
    private const val MIN_MATCH = 40

    fun resolve(videoId: String, title: String, artist: String, forDownload: Boolean): AudioStream? {
        if (title.isBlank()) return null
        val addons = AddonStore.enabled().filter { !forDownload || it.downloads }
        if (addons.isEmpty()) return null

        val key = "$videoId|${AddonStore.sig()}|$forDownload"
        memo[key]?.let { if (System.currentTimeMillis() - it.at < MEMO_MS) return it.stream }

        StreamInfoStore.setBusy(videoId, true)
        try {
            for (addon in addons) {
                // Failed lately: rest it, ask the next one. It is tried again after its rest.
                if (ProviderHealth.isDown("addon:${addon.url}")) continue
                try {
                    val stream = resolveFromAddon(addon, title, artist) ?: continue
                    memo[key] = Memo(stream, System.currentTimeMillis())
                    if (memo.size > 200) memo.entries.removeIf { System.currentTimeMillis() - it.value.at > MEMO_MS }
                    return stream
                } catch (e: Exception) {
                    if (e.message?.startsWith("HTTP 401") == true && addon.headers.isEmpty()) {
                        // Needs a key we do not have (bitchordAuth = official BitChord app only).
                        AddonStore.markOfficialOnly(addon.url)
                    }
                    ProviderHealth.markFailed("addon:${addon.url}")
                    Log.w(TAG, "${addon.name} failed for \"$title\": ${e.message}")
                }
            }
            Log.w(TAG, "No addon had \"$artist - $title\". Falling back to YouTube.")
            return null
        } finally {
            StreamInfoStore.setBusy(videoId, false)
        }
    }

    /** True when the label / mime / format says the file is lossless. */
    fun isLossless(text: String?): Boolean {
        val t = text.orEmpty().lowercase(Locale.ROOT)
        return listOf("flac", "alac", "wav", "pcm", "lossless", "hi-res", "hires").any { it in t }
    }

    @OptIn(UnstableApi::class)
    fun evictCache(context: Context, song: Song) {
        forgetMemo(song)
        evictDisk(context, song)
    }

    /** Forget saved links only. Stream on disk stays, so a song that is playing keeps playing. */
    fun forgetMemo(song: Song) {
        memo.keys.removeIf { it.startsWith(song.videoId + "|") }
        MonochromeResolver.forget(song)
        StreamResolver.forget(song.videoId)
    }

    fun evictDisk(context: Context, song: Song) {
        runCatching {
            AudioCacheManager.getInstance(context)   // opens the cache if needed
            AudioCacheManager.evict(song.videoId)
        }
    }

    /** Scored search hit. */
    private class Hit(val obj: JSONObject, val match: Int, val quality: Int)

    /** Winners within this many points of the best title/artist score count as the same song. */
    private const val MATCH_BAND = 10

    private fun resolveFromAddon(addon: Addon, title: String, artist: String): AudioStream? {
        val cleaned = cleanTitle(title)
        // Most exact first. Later ones are wider, used only when nothing good was found yet.
        val queries = listOf(
            "$artist $title".trim(),
            "$artist $cleaned".trim(),
            cleaned,
            title,
        ).filter { it.isNotBlank() }.distinct()

        val hits = ArrayList<Hit>()
        var lastError: Exception? = null

        for (query in queries) {
            val results = try {
                search(addon, query, title, artist)
            } catch (e: Exception) {
                lastError = e
                null
            } ?: continue

            for (i in 0 until results.length()) {
                val candidate = results.optJSONObject(i) ?: continue
                val match = matchScore(title, artist, candidate)
                if (match >= MIN_MATCH) hits += Hit(candidate, match, qualityScore(candidate))
            }
            if (hits.any { it.match >= GOOD_MATCH }) break
        }

        if (hits.isEmpty()) {
            // All searches failed (network) -> let caller log it. No match -> just null.
            if (lastError != null) throw lastError
            return null
        }

        // Same song = within a few points of the best match. Among those the BEST QUALITY wins.
        // Before, a 85-point MP3 beat an 80-point FLAC of the same song.
        val top = hits.maxOf { it.match }
        val ranked = hits
            .filter { it.match >= top - MATCH_BAND }
            .sortedWith(compareByDescending<Hit> { it.quality }.thenByDescending { it.match })

        // Try the best few. A dead link or a DASH/HLS manifest must not end the search.
        var failure: Exception? = null
        for (hit in ranked.take(MAX_TRIES)) {
            try {
                buildStream(addon, hit.obj)?.let { return it }
            } catch (e: Exception) {
                failure = e
                Log.w(TAG, "${addon.name} candidate failed: ${e.message}")
            }
        }
        if (failure != null && failure.message?.startsWith("HTTP 401") == true) throw failure
        return null
    }

    private const val MAX_TRIES = 3

    /** Manifest urls need DASH/HLS sources. This player only does plain progressive files. */
    private fun isManifest(url: String, mime: String?): Boolean {
        val u = url.substringBefore('?').lowercase(Locale.ROOT)
        val m = mime.orEmpty().lowercase(Locale.ROOT)
        return u.endsWith(".mpd") || u.endsWith(".m3u8") ||
            "dash+xml" in m || "mpegurl" in m
    }

    private fun buildStream(addon: Addon, best: JSONObject): AudioStream? {
        val id = first(best, "id", "trackId", "track_id")
        val response = if (
            first(best, "url", "stream_url", "streamUrl").isNotBlank()
        ) {
            best
        } else {
            if (id.isBlank()) return null
            requestStream(addon, id) ?: return null
        }

        val url = first(response, "url", "stream_url", "streamUrl")
            .ifBlank { first(best, "url", "stream_url", "streamUrl") }

        if (url.isBlank()) return null

        val format = first(
            response, "format", "codec", "container", "mimeType", "mime_type"
        ).ifBlank {
            first(best, "format", "codec", "container", "mimeType", "mime_type")
        }

        val quality = first(
            response, "quality", "audioQuality", "audio_quality", "tier"
        ).ifBlank {
            first(best, "quality", "audioQuality", "audio_quality", "tier")
        }

        val mime = first(
            response, "mimeType", "mime_type", "contentType", "content_type"
        ).ifBlank {
            first(best, "mimeType", "mime_type", "contentType", "content_type")
        }.ifBlank {
            inferMime(format, url)
        }

        if (isManifest(url, mime) || isManifest(url, format)) {
            Log.w(TAG, "${addon.name}: manifest stream (DASH/HLS) skipped, not playable here")
            return null
        }

        val headersJson = response.optJSONObject("headers")
            ?: best.optJSONObject("headers")

        val headers = headersJson?.keys()?.asSequence()
            ?.associateWith { headersJson.optString(it) }
            ?: emptyMap()

        fun num(vararg keys: String): Int = keys.firstNotNullOfOrNull { k ->
            val v = response.opt(k) ?: best.opt(k)
            when (v) {
                is Number -> v.toDouble().takeIf { it > 0 }
                is String -> v.toDoubleOrNull()?.takeIf { it > 0 }
                else -> null
            }
        }?.toInt() ?: 0

        val bitrate = num("bitrate", "kbps")
        val bitDepth = num("bitDepth", "bit_depth", "bitsPerSample", "bits_per_sample")
        var rate = num("sampleRate", "sample_rate", "samplingRate", "sampling_rate")
        if (rate in 1..400) rate *= 1000  // 96 or 44.1 kHz given as kHz, not Hz

        val codec = prettyCodec(format, mime, url)
        val depthPart = bitDepth.takeIf { it > 0 }?.let { "$it-bit" }
        val ratePart = rate.takeIf { it > 0 }?.let {
            java.lang.String.format(Locale.US, "%.1f", it / 1000.0).removeSuffix(".0") + " kHz"
        }
        // Lossless has no real kbps from the addon. Lossy keeps it.
        val kbpsPart = if (isLossless(codec) || isLossless(quality)) null
        else bitrate.takeIf { it > 0 }?.let { "$it kbps" }

        val label = listOf(
            codec.ifBlank { format },
            depthPart,
            ratePart,
            kbpsPart,
            quality.takeIf { it.isNotBlank() && !it.equals(codec, true) && depthPart == null },
            addon.name
        ).filter { !it.isNullOrBlank() }.joinToString(" · ")

        Log.d(TAG, "${addon.name}: resolved $label")

        return AudioStream(
            url = url,
            headers = headers,   // this addon's own key/token headers
            quality = label,
            mimeType = mime,
            provider = "addon:${addon.url}",
            trackKey = id.ifBlank { url.substringBefore('?').hashCode().toUInt().toString(16) },
        )
    }

    private fun prettyCodec(format: String, mime: String?, url: String): String {
        val t = (format + " " + mime.orEmpty()).lowercase(Locale.ROOT)
        return when {
            "flac" in t || url.contains(".flac", true) -> "FLAC"
            "alac" in t -> "ALAC"
            "wav" in t || "pcm" in t -> "WAV"
            "opus" in t -> "Opus"
            "mp3" in t || "mpeg" in t -> "MP3"
            "aac" in t || "mp4" in t || "m4a" in t -> "AAC"
            else -> ""
        }
    }

    private fun search(
        addon: Addon,
        query: String,
        title: String,
        artist: String
    ): JSONArray? {
        val url = AddonHttp.join(
            addon.url, addon.searchPath,
            "q=${enc(query)}&title=${enc(title)}&artist=${enc(artist)}&quality=${qual(addon)}",
        )

        val body = AddonHttp.get(url, addon.headers)

        if (body.trimStart().startsWith("[")) return JSONArray(body)

        val obj = JSONObject(body)
        for (key in listOf("results", "tracks", "items", "data", "streams")) {
            obj.optJSONArray(key)?.let { return it }
        }
        return null
    }

    /** Supports both BitChord-style /stream/{id} and /stream?id=... APIs. */
    private fun requestStream(addon: Addon, id: String): JSONObject? {
        val path = addon.streamPath.trimEnd('/')
        val encodedId = enc(id)

        val attempts = listOf(
            AddonHttp.join(addon.url, "$path/$encodedId", "quality=${qual(addon)}"),
            AddonHttp.join(addon.url, path, "id=$encodedId&quality=${qual(addon)}"),
            AddonHttp.join(addon.url, path, "id=$encodedId")
        ).distinct()

        var last: Exception? = null

        for (url in attempts) {
            try {
                return JSONObject(AddonHttp.get(url, addon.headers))
            } catch (e: Exception) {
                last = e
            }
        }

        throw last ?: IllegalStateException("Addon stream endpoint failed")
    }

    private fun first(o: JSONObject, vararg keys: String): String =
        keys.asSequence()
            .map { o.opt(it) }
            .mapNotNull { value ->
                when (value) {
                    null, JSONObject.NULL -> null
                    is String -> value.takeIf { it.isNotBlank() }
                    else -> value.toString().takeIf { it.isNotBlank() }
                }
            }
            .firstOrNull()
            .orEmpty()

    private fun artistOf(o: JSONObject): String {
        o.optJSONArray("artists")?.let { arr ->
            return List(arr.length()) { i ->
                val item = arr.opt(i)
                if (item is JSONObject) first(item, "name", "title")
                else item?.toString().orEmpty()
            }.joinToString(" ")
        }
        return first(o, "artist", "author", "artists")
    }

    private val VARIANT_WORDS = listOf(
        "live", "remix", "karaoke", "instrumental", "cover", "acoustic",
        "sped up", "slowed", "nightcore", "8d", "reverb", "demo",
    )

    private fun variantsOf(raw: String): Set<String> {
        val t = raw.lowercase(Locale.ROOT)
        return VARIANT_WORDS.filter { Regex("\\b${Regex.escape(it)}\\b").containsMatchIn(t) }.toSet()
    }

    /** "Song (feat. X) - Remastered 2011" -> "Song". */
    private fun cleanTitle(t: String): String =
        t.replace(Regex("\\(.*?\\)|\\[.*?]"), " ")
            .replace(Regex("\\s[-\u2013\u2014]\\s.*$"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
            .ifBlank { t }

    private fun matchScore(title: String, artist: String, candidate: JSONObject): Int {
        val wantedTitle = norm(title)
        val candidateRaw = first(candidate, "title", "name")
        val candidateTitle = norm(candidateRaw)
        val wantedArtist = norm(artist)
        val candidateArtist = norm(artistOf(candidate))

        var score = 0

        if (candidateTitle == wantedTitle && wantedTitle.isNotBlank()) score += 50
        else if (
            candidateTitle.isNotBlank() && wantedTitle.isNotBlank() &&
            (candidateTitle.contains(wantedTitle) || wantedTitle.contains(candidateTitle))
        ) score += 35

        if (wantedArtist.isNotBlank() && candidateArtist.isNotBlank()) {
            val wantedWords = wantedArtist.split(' ').filter { it.length >= 3 }.toSet()
            val candidateWords = candidateArtist.split(' ').filter { it.length >= 3 }.toSet()
            if (
                candidateArtist.contains(wantedArtist) ||
                wantedArtist.contains(candidateArtist) ||
                wantedWords.intersect(candidateWords).isNotEmpty()
            ) score += 30
        }

        // "Song (Live)" must not win over "Song" when you asked for "Song".
        val wantedVariants = variantsOf(title)
        val extra = variantsOf(candidateRaw) - wantedVariants
        if (extra.isNotEmpty() && score > 0) score -= 30

        return score
    }

    private fun qualityScore(o: JSONObject): Int {
        val format = first(
            o, "format", "codec", "container", "mimeType", "mime_type"
        ).lowercase(Locale.ROOT)

        val quality = first(
            o, "quality", "audioQuality", "audio_quality", "tier"
        ).lowercase(Locale.ROOT)

        val bitrate = o.optInt("bitrate", o.optInt("kbps", 0))

        val codecScore = when {
            "flac" in format || "alac" in format ||
                    "wav" in format || "pcm" in format -> 500

            "opus" in format || "vorbis" in format -> 250
            "aac" in format || "mp3" in format -> 150
            else -> 0
        }

        val tierScore = when {
            listOf("lossless", "flac", "hi-res", "hires", "hifi", "max")
                .any { it in quality } -> 400

            listOf("320", "high").any { it in quality } -> 200
            else -> 0
        }

        // Hi-Res (24-bit, or above 48 kHz) beats CD quality FLAC.
        val depth = o.optInt("bitDepth", o.optInt("bit_depth", o.optInt("bitsPerSample", 0)))
        var rate = o.optInt("sampleRate", o.optInt("sample_rate", 0))
        if (rate in 1..400) rate *= 1000
        val hiRes = if (depth > 16 || rate > 48_000) 150 else 0

        return codecScore + tierScore + hiRes + bitrate.coerceAtMost(1000)
    }

    private fun inferMime(format: String, url: String): String? {
        val f = format.lowercase(Locale.ROOT)
            .substringAfterLast('/')
            .substringBefore(';')

        return when {
            "flac" in f || url.contains(".flac", true) -> "audio/flac"
            "alac" in f -> "audio/alac"
            "wav" in f || "pcm" in f -> "audio/wav"
            "opus" in f || "webm" in f || url.contains(".webm", true) ->
                "audio/webm"
            "mp3" in f || url.contains(".mp3", true) -> "audio/mpeg"
            "aac" in f || "mp4" in f || "m4a" in f -> "audio/mp4"
            else -> null
        }
    }

    private fun norm(s: String) = s.lowercase(Locale.ROOT)
        .replace(Regex("\\(.*?\\)|\\[.*?]"), "")
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .trim()

    /** Quality value this addon wants (from its manifest), else LOSSLESS. */
    private fun qual(addon: Addon) = addon.quality.ifBlank { LOSSLESS }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
}