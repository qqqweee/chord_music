package com.example.chord.playback

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.Locale

/**
 * Remembers the audio quality of each song we resolved, like "Opus · 160 kbps · 48 kHz".
 * Player shows it. Saved on disk, so cached and downloaded songs still show it
 * when no new stream is resolved.
 */
object StreamInfoStore {
    private const val FILE = "stream_quality"
    private const val MAX = 400

    private var prefs: android.content.SharedPreferences? = null
    private val _labels = MutableStateFlow<Map<String, String>>(emptyMap())
    val labels: StateFlow<Map<String, String>> = _labels.asStateFlow()

    private val _busy = MutableStateFlow<Set<String>>(emptySet())
    /** Song ids an addon is being asked about now. Player blinks "Upgrading quality". */
    val busy: StateFlow<Set<String>> = _busy.asStateFlow()
    fun setBusy(id: String, on: Boolean) { _busy.update { if (on) it + id else it - id } }

    /**
     * Songs that fell back to YouTube while an addon was on. Their stream sits in the disk cache
     * under the addon key, so next play would reuse that worse file. Queue evicts them, addon is asked again.
     */
    private val degraded = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    fun markDegraded(id: String) { degraded.add(id) }
    fun takeDegraded(id: String): Boolean = degraded.remove(id)

    /** Note appended when every lossless source missed. Never counts as a codec. */
    const val NO_MATCH = "No lossless match"

    /**
     * True only when the CODEC part (first part) is lossless. Before, the whole label was
     * searched, so the fallback note "lossless sources had no match" lit the Lossless badge
     * on every YouTube song.
     */
    fun isLosslessLabel(label: String?): Boolean {
        val codec = label.orEmpty().split(" · ").map { it.trim() }.firstOrNull { it.isNotBlank() } ?: return false
        if ("no match" in codec.lowercase(Locale.ROOT)) return false
        return com.example.chord.playback.addon.AddonResolver.isLossless(codec)
    }

    /** Label parts without the fallback note. */
    fun cleanParts(label: String?): List<String> =
        label.orEmpty().split(" · ").map { it.trim() }
            .filter { it.isNotBlank() && "no match" !in it.lowercase(Locale.ROOT) }

    // Songs the person sent back to YouTube Opus. Own prefs file: put() clears FILE.
    private var forcePrefs: android.content.SharedPreferences? = null
    private val _forcedYoutube = MutableStateFlow<Set<String>>(emptySet())
    val forcedYoutube: StateFlow<Set<String>> = _forcedYoutube.asStateFlow()
    fun isForcedYoutube(id: String) = id in _forcedYoutube.value

    @Synchronized
    fun setForcedYoutube(id: String, on: Boolean) {
        val next = if (on) _forcedYoutube.value + id else _forcedYoutube.value - id
        if (next == _forcedYoutube.value) return
        _forcedYoutube.value = next
        forcePrefs?.edit()?.putStringSet("ids", next)?.apply()
    }

    /**
     * What format the cached bytes of a song really are: "flac", "aac", "opus"...
     * The disk cache key is the chord:// link, the same for every source. If FLAC bytes were
     * cached and a later play resolves to AAC (server hiccup), the cache would glue the FLAC head
     * to the AAC tail. Result: song plays, no sound. This note lets the resolver catch that.
     */
    private var srcPrefs: android.content.SharedPreferences? = null
    private val cacheSrc = LinkedHashMap<String, String>()

    @Synchronized
    fun cacheSource(id: String): String? = cacheSrc[id]

    @Synchronized
    fun setCacheSource(id: String, family: String?) {
        if (family == null) {
            if (cacheSrc.remove(id) == null) return
        } else {
            if (cacheSrc[id] == family) return
            cacheSrc.remove(id)
            cacheSrc[id] = family
        }
        while (cacheSrc.size > MAX) cacheSrc.remove(cacheSrc.keys.first())
        srcPrefs?.let { p ->
            val e = p.edit().clear()
            cacheSrc.forEach { (k, v) -> e.putString(k, v) }
            e.apply()
        }
    }

    /** "flac" / "aac" / "opus" / ... from a mime type or a quality label. Null if unknown. */
    fun family(text: String?): String? {
        val t = text.orEmpty().lowercase(Locale.ROOT)
        return when {
            "flac" in t -> "flac"
            "alac" in t -> "alac"
            "opus" in t -> "opus"
            "vorbis" in t -> "vorbis"
            "mp4a" in t || "aac" in t || "audio/mp4" in t -> "aac"
            else -> null
        }
    }

    @Synchronized
    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        prefs = p
        forcePrefs = context.applicationContext.getSharedPreferences("stream_force_yt", Context.MODE_PRIVATE)
        _forcedYoutube.value = forcePrefs?.getStringSet("ids", emptySet())?.toSet().orEmpty()
        _labels.value = p.all.mapNotNull { (k, v) -> (v as? String)?.let { k to it } }.toMap()
        val sp = context.applicationContext.getSharedPreferences("stream_cache_src", Context.MODE_PRIVATE)
        srcPrefs = sp
        sp.all.forEach { (k, v) -> (v as? String)?.let { cacheSrc[k] = it } }
    }

    @Synchronized
    fun put(videoId: String, label: String) {
        if (label.isBlank() || _labels.value[videoId] == label) return
        val next = LinkedHashMap(_labels.value)
        next.remove(videoId)
        next[videoId] = label
        while (next.size > MAX) next.remove(next.keys.first())
        _labels.value = next
        prefs?.let { p ->
            val e = p.edit().clear()
            next.forEach { (k, v) -> e.putString(k, v) }
            e.apply()
        }
    }

    /** "Opus · 160 kbps · 48 kHz". Parts we do not know are left out. */
    fun label(codecs: String?, mime: String?, bitrate: Int?, sampleRate: Int?): String {
        val c = (codecs ?: mime).orEmpty().lowercase(Locale.ROOT)
        val codec = when {
            "opus" in c -> "Opus"
            "mp4a" in c || "aac" in c || "audio/mp4" in c -> "AAC"
            "vorbis" in c -> "Vorbis"
            "flac" in c -> "FLAC"
            "ec-3" in c -> "E-AC-3"
            else -> null
        }
        val kbps = bitrate?.takeIf { it > 0 }?.let { if (it >= 1000) it / 1000 else it }?.let { "$it kbps" }
        val khz = sampleRate?.takeIf { it > 0 }?.let {
            val v = it / 1000.0
            String.format(Locale.US, "%.1f", v) + " kHz"
        }
        return listOfNotNull(codec, khz, kbps, if (codec != null) "Stereo" else null).joinToString(" · ")
    }
}