package com.example.chord.playback

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.ContentMetadata
import com.example.chord.data.model.Song
import com.example.chord.data.settings.AppSettings
import com.example.chord.playback.addon.AddonResolver
import com.example.chord.playback.addon.AddonStore
import com.example.chord.playback.addon.MonochromeResolver
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.ConcurrentHashMap

/**
 * Turns a chord:// link into a real request: URL, that provider's own headers, and a cache key
 * that names the provider. Sits OUTSIDE the disk cache (see [PlaybackDataSources]), so the cache
 * key is known before the cache is asked.
 *
 * Cache key = provider + track + codec + size. FLAC from Monochrome and Opus from YouTube can
 * never share bytes, so a mid-song switch of source can never glue two files into one.
 */
@UnstableApi
class StreamResolver(
    private val extractor: AudioStreamExtractor,
) : ResolvingDataSource.Resolver {

    override fun resolveDataSpec(dataSpec: DataSpec): DataSpec {
        val uriStr = dataSpec.uri.toString()
        if (!uriStr.startsWith(SCHEME)) return dataSpec

        val videoId = uriStr.removePrefix(SCHEME).substringBefore('?')
        val title = dataSpec.uri.getQueryParameter("t").orEmpty()
        val artist = dataSpec.uri.getQueryParameter("a").orEmpty()

        // Whole song already on disk, from the kind of source wanted now: no lookup, no network.
        fullyCachedSpec(dataSpec, videoId)?.let { return it }

        // Runs on ExoPlayer's loader thread. Any failure becomes an IOException, so ExoPlayer
        // treats it as a normal load error (retry, then Recovery), never as a crash.
        val startedAt = android.os.SystemClock.elapsedRealtime()
        val stream = try {
            resolveStream(extractor, videoId, title, artist)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw InterruptedIOException("Stream lookup interrupted")
        } catch (e: IOException) {
            // Lookup failed (offline?) but the bytes at this spot are on disk: keep playing.
            cachedAtPositionSpec(dataSpec, videoId)?.let { return it }
            throw e
        } catch (e: Exception) {
            cachedAtPositionSpec(dataSpec, videoId)?.let { return it }
            throw IOException("Could not find a stream for $videoId: ${e.message}", e)
        }

        // Timing log: adb logcat -s ChordLoad.
        android.util.Log.i("ChordLoad", "lookup $videoId took ${android.os.SystemClock.elapsedRealtime() - startedAt} ms, pos ${dataSpec.position}, source: ${stream.provider} ${stream.quality}")

        val key = cacheKey(videoId, stream)
        StreamInfoStore.setLastCacheKey(videoId, key)
        return dataSpec.buildUpon()
            .setUri(Uri.parse(stream.url))
            .setKey(key)
            .setHttpRequestHeaders(stream.headers)   // this provider's headers only
            .build()
    }

    /** Playable straight from disk: the whole file is cached, under a key of the wanted kind. */
    private fun fullyCachedSpec(dataSpec: DataSpec, videoId: String): DataSpec? {
        val key = StreamInfoStore.lastCacheKey(videoId) ?: return null
        if (isLosslessKey(key) != wantsLossless(videoId)) return null   // setting changed: look again
        val cache = AudioCacheManager.peek() ?: return null
        val length = cache.getContentMetadata(key).get(ContentMetadata.KEY_CONTENT_LENGTH, -1L)
        if (length <= 0L || cache.getCachedBytes(key, 0, length) < length) return null
        return dataSpec.buildUpon().setKey(key).build()
    }

    /** Lookup failed, but the byte we need is on disk under the last known key. */
    private fun cachedAtPositionSpec(dataSpec: DataSpec, videoId: String): DataSpec? {
        val key = StreamInfoStore.lastCacheKey(videoId) ?: return null
        val cache = AudioCacheManager.peek() ?: return null
        if (cache.getCachedLength(key, dataSpec.position, 1) < 1) return null
        return dataSpec.buildUpon().setKey(key).build()
    }

    companion object {
        const val SCHEME = "chord://"

        // ===================== TUNABLES (safe to change) =====================
        /** YouTube lookup is heavy (config + cipher). Links live for hours, so keep one for a while. */
        private const val YT_TTL_MS = 20 * 60_000L
        /** A song keeps the source it started with until it is idle this long (or Recovery forgets it). */
        private const val PICK_TTL_MS = 30 * 60_000L
        // ====================================================================

        private class YtMemo(val stream: AudioStream, val at: Long)
        private class Pick(val stream: AudioStream, val skipLossless: Boolean, val noLossless: Boolean, @Volatile var seen: Long)

        private val ytMemo = ConcurrentHashMap<String, YtMemo>()

        /**
         * The source chosen for a song. Every open of the same song (seek, reconnect, next chunk)
         * gets the same stream, so a song never flips FLAC <-> Opus in the middle.
         * Only [forget] (Recovery, reload) lets the next open choose again.
         */
        private val picks = ConcurrentHashMap<String, Pick>()

        /** One lookup per song at a time. Prefetch and player never repeat each other's work. */
        private val locks = ConcurrentHashMap<String, Any>()

        private fun wantsLossless(videoId: String) =
            AppSettings.losslessStream && !StreamInfoStore.isForcedYoutube(videoId)

        /** Key = provider + track + codec + size. Different bytes, different key. */
        internal fun cacheKey(videoId: String, s: AudioStream): String {
            val family = StreamInfoStore.family(s.mimeType) ?: StreamInfoStore.family(s.quality) ?: "unknown"
            val length = s.contentLength?.takeIf { it > 0 } ?: 0L
            return "chord/$videoId/${s.provider.ifBlank { "unknown" }}/${s.trackKey}/$family/$length"
        }

        internal fun isLosslessKey(key: String): Boolean {
            // Key ends with ".../<family>/<length>". Provider and track may hold slashes, so read from the end.
            val parts = key.split('/')
            val family = parts.getOrNull(parts.size - 2) ?: return false
            return family == "flac" || family == "alac"
        }

        /** What the song is playing from now, if known. Null before the first lookup. */
        fun currentStream(videoId: String): AudioStream? = picks[videoId]?.stream

        /** True when lossless was wanted and available sources had nothing. */
        fun hadNoLosslessMatch(videoId: String): Boolean = picks[videoId]?.noLossless == true

        /**
         * Monochrome first, then configured addons, then YouTube as the guaranteed fallback.
         * Providers that failed lately are skipped (see [ProviderHealth]); they are tried again
         * after their rest. Blocking. Call from a loader or IO thread.
         */
        fun resolveStream(
            extractor: AudioStreamExtractor,
            videoId: String,
            title: String,
            artist: String,
        ): AudioStream {
            val skipLossless = !wantsLossless(videoId)
            val lock = locks.computeIfAbsent(videoId) { Any() }
            return synchronized(lock) {
                val now = System.currentTimeMillis()
                picks[videoId]?.let {
                    if (it.skipLossless == skipLossless && now - it.seen < PICK_TTL_MS) {
                        it.seen = now
                        return it.stream
                    }
                }
                var noLossless = false
                val stream = if (skipLossless) {
                    youtube(extractor, videoId)
                } else {
                    val song = Song(title = title, details = artist, videoId = videoId)
                    (if (title.isNotBlank()) MonochromeResolver.resolve(song) else null)
                        ?: AddonResolver.resolve(videoId, title, artist, forDownload = false)
                        ?: youtube(extractor, videoId).also {
                            noLossless = title.isNotBlank() &&
                                (AddonStore.enabled().isNotEmpty() || MonochromeResolver.isEnabled())
                        }
                }
                picks[videoId] = Pick(stream, skipLossless, noLossless, now)
                if (picks.size > 100) picks.entries.removeIf { now - it.value.seen > PICK_TTL_MS }
                stream
            }
        }

        private fun youtube(extractor: AudioStreamExtractor, videoId: String): AudioStream {
            val now = System.currentTimeMillis()
            ytMemo[videoId]?.let { if (now - it.at < YT_TTL_MS) return it.stream }
            val yt = runBlocking { extractor.extract(videoId) }
            ytMemo[videoId] = YtMemo(yt, now)
            if (ytMemo.size > 100) ytMemo.entries.removeIf { now - it.value.at > YT_TTL_MS }
            return yt
        }

        /** Warm the lookup for a song that is not playing yet. True if a stream is ready. */
        fun prefetch(extractor: AudioStreamExtractor, videoId: String, title: String, artist: String): Boolean =
            try {
                resolveStream(extractor, videoId, title, artist)
                true
            } catch (e: Exception) {
                android.util.Log.w("ChordPrefetch", "prefetch failed for $videoId: ${e.message}")
                false
            }

        /** Drop the chosen source and the remembered YouTube link, so the next open decides fresh. */
        fun forget(videoId: String) {
            picks.remove(videoId)
            ytMemo.remove(videoId)
        }

        fun createDummyUri(videoId: String): String = "$SCHEME$videoId"

        /**
         * Same uri plus title/artist so addons can search. Nothing about settings or sources:
         * the cache key is built from the resolved stream, not from this link.
         */
        fun createDummyUri(song: Song): String {
            // "Song • Artist • Album" -> "Artist". Old code took "Song", so addon searched the wrong artist.
            val artist = com.example.chord.data.history.HistoryStore.cleanArtist(song.details)
            return "$SCHEME${song.videoId}?t=${Uri.encode(song.title)}&a=${Uri.encode(artist)}"
        }
    }
}
