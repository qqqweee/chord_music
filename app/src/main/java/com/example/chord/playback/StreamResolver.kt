package com.example.chord.playback

import androidx.media3.common.ParserException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.ResolvingDataSource
import com.example.chord.data.model.Song
import com.example.chord.playback.addon.AddonResolver
import com.example.chord.playback.addon.AddonStore
import com.example.chord.playback.addon.MonochromeResolver
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.ConcurrentHashMap

@UnstableApi
class StreamResolver(
    private val extractor: AudioStreamExtractor,
    private val forDownload: Boolean = false,
) : ResolvingDataSource.Resolver {

    override fun resolveDataSpec(dataSpec: DataSpec): DataSpec {
        val uriStr = dataSpec.uri.toString()

        if (uriStr.startsWith(SCHEME)) {
            val videoId = uriStr.removePrefix(SCHEME).substringBefore('?')
            val title = dataSpec.uri.getQueryParameter("t").orEmpty()
            val artist = dataSpec.uri.getQueryParameter("a").orEmpty()

            // Resolution runs on ExoPlayer's loader thread. If prefetch already did the work,
            // this returns at once from memory.
            // Any failure becomes an IOException, so ExoPlayer treats it as a normal load error
            // (retry, then Recovery) and never as an unexpected crash on the loader thread.
            val stream = try {
                resolveStream(extractor, videoId, title, artist, forDownload)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw InterruptedIOException("Stream lookup interrupted")
            } catch (e: IOException) {
                throw e
            } catch (e: Exception) {
                throw IOException("Could not find a stream for $videoId: ${e.message}", e)
            }

            if (!forDownload) checkSameSource(dataSpec, videoId, stream)

            return dataSpec.buildUpon()
                .setUri(stream.url)
                .setHttpRequestHeaders(stream.headers)
                .build()
        }
        return dataSpec
    }

    companion object {
        const val SCHEME = "chord://"

        private class YtMemo(val stream: AudioStream, val at: Long)

        /** YouTube lookup is heavy (config + cipher). Links live for hours, so keep one for a while. */
        private val ytMemo = ConcurrentHashMap<String, YtMemo>()
        private const val YT_TTL_MS = 20 * 60_000L

        /** Set when the player sees the network die. Used to be patient with Monochrome after. */
        @Volatile private var networkLostAt = 0L
        private const val BACK_ONLINE_WINDOW_MS = 2 * 60_000L
        private const val MONOCHROME_RETRIES = 2
        private const val RETRY_PAUSE_MS = 60_000L
        @Volatile private var retriesExhaustedAt = 0L

        /** Songs that were lossless when they stopped. Value = when. Cleared on success or last retry. */
        private val needLossless = ConcurrentHashMap<String, Long>()
        private const val NEED_LOSSLESS_TTL_MS = 10 * 60_000L

        fun requireLossless(videoId: String) { needLossless[videoId] = System.currentTimeMillis() }
        fun allowFallback(videoId: String) { needLossless.remove(videoId) }
        private fun mustStayLossless(videoId: String): Boolean {
            val at = needLossless[videoId] ?: return false
            if (System.currentTimeMillis() - at > NEED_LOSSLESS_TTL_MS) { needLossless.remove(videoId); return false }
            return true
        }

        fun noteNetworkLoss() { networkLostAt = System.currentTimeMillis() }
        private fun justBackOnline() = System.currentTimeMillis() - networkLostAt < BACK_ONLINE_WINDOW_MS

        /**
         * Disk cache key is the chord:// link, same for FLAC and AAC. Cached head from one source
         * plus tail from another = glued file = plays silent. So:
         *   position 0: a fresh file starts. Note its format. Old cached bytes of other format go.
         *   later chunk, other format than the cached head: fail the load. Recovery then throws
         *   the cache away and restarts the song clean.
         */
        private fun checkSameSource(dataSpec: DataSpec, videoId: String, stream: AudioStream) {
            val codec = StreamInfoStore.family(stream.mimeType) ?: StreamInfoStore.family(stream.quality) ?: return
            // FLAC vs FLAC from two different servers/tracks is also a glued file. Monochrome URLs
            // are stable per source (host + path), so pin that too. CDN hosts of other sources rotate: codec only.
            val family = if (stream.quality.contains("Monochrome", ignoreCase = true)) {
                val u = runCatching { java.net.URI(stream.url) }.getOrNull()
                codec + "|" + u?.host.orEmpty() + u?.rawPath.orEmpty()
            } else codec
            val known = StreamInfoStore.cacheSource(videoId)
            val key = dataSpec.key ?: dataSpec.uri.toString()
            val cache = AudioCacheManager.peek()
            if (dataSpec.position == 0L) {
                if (known != family) {
                    // Other source (or unknown origin): old bytes must not live under this key.
                    runCatching { cache?.removeResource(key) }
                }
                StreamInfoStore.setCacheSource(videoId, family)
                return
            }
            if (known == null) {
                // Cached bytes with no note of where they came from (old app version, cleared note): not safe.
                val orphan = runCatching { cache?.getCachedSpans(key)?.isNotEmpty() == true }.getOrDefault(false)
                if (orphan) {
                    runCatching { cache?.removeResource(key) }
                    throw ParserException.createForMalformedContainer("Cached bytes of unknown source", null)
                }
                StreamInfoStore.setCacheSource(videoId, family)
                return
            }
            if (known == family) return
            runCatching { cache?.removeResource(key) }
            throw ParserException.createForMalformedContainer(
                "Cached part is $known but new link is $family", null,
            )
        }

        /** One lookup per song at a time. Prefetch and player never repeat each other's work. */
        private val locks = ConcurrentHashMap<String, Any>()

        /**
         * Monochrome FLAC first, then configured addons, then YouTube as the guaranteed fallback.
         * Blocking. Call from a loader or IO thread.
         */
        fun resolveStream(
            extractor: AudioStreamExtractor,
            videoId: String,
            title: String,
            artist: String,
            forDownload: Boolean,
        ): AudioStream {
            // Person chose "Back to Opus" for this song: skip every lossless source.
            val forcedOpus = !forDownload && StreamInfoStore.isForcedYoutube(videoId)
            val lock = locks.computeIfAbsent("$videoId|$forDownload|$forcedOpus") { Any() }
            return synchronized(lock) {
                val song = Song(title = title, details = artist, videoId = videoId)
                var monochrome = if (!forcedOpus && title.isNotBlank()) MonochromeResolver.resolve(song) else null

                // Monochrome failed by error (net, timeout), not "no match": ask 2 more times
                // (after 1 s, then 2 s) before any fallback. Many songs hit by one dead server would
                // each wait, so after 2 failed retries skip the retries for 60 s (unless net just came back).
                if (monochrome == null && !forcedOpus && title.isNotBlank() &&
                    MonochromeResolver.failedByError(song) &&
                    (justBackOnline() || System.currentTimeMillis() - retriesExhaustedAt > RETRY_PAUSE_MS)
                ) {
                    for (n in 1..MONOCHROME_RETRIES) {
                        Thread.sleep(1_000L * n)
                        monochrome = MonochromeResolver.resolve(song)
                        if (monochrome != null) break
                    }
                    if (monochrome == null) retriesExhaustedAt = System.currentTimeMillis()
                }
                val lossless = monochrome
                    ?: (if (forcedOpus) null else AddonResolver.resolve(videoId, title, artist, forDownload))
                if (lossless != null) {
                    needLossless.remove(videoId)
                    lossless
                } else {
                    // Song was FLAC before it stopped. Do not quietly drop to AAC on one bad lookup:
                    // fail, so Recovery asks again. Last retry lets AAC through, so music still plays.
                    if (!forDownload && !forcedOpus && mustStayLossless(videoId)) {
                        throw IOException("Lossless source not ready for $videoId. Retrying.")
                    }
                    youtube(extractor, videoId, title, forcedOpus)
                }
            }
        }

        private fun youtube(extractor: AudioStreamExtractor, videoId: String, title: String, forcedOpus: Boolean): AudioStream {
            val now = System.currentTimeMillis()
            ytMemo[videoId]?.let { if (now - it.at < YT_TTL_MS) return it.stream }

            val yt = runBlocking { extractor.extract(videoId) }
            ytMemo[videoId] = YtMemo(yt, now)
            if (ytMemo.size > 100) ytMemo.entries.removeIf { now - it.value.at > YT_TTL_MS }

            if (!forcedOpus) {
                // Addon was on but had nothing (or failed). Say so in the player, and remember
                // it so the cached YouTube copy is thrown away before the next play.
                if (title.isNotBlank() && (AddonStore.enabled().isNotEmpty() || MonochromeResolver.isEnabled())) {
                    StreamInfoStore.markDegraded(videoId)
                    StreamInfoStore.put(videoId, listOf(yt.quality, StreamInfoStore.NO_MATCH).filter { it.isNotBlank() }.joinToString(" · "))
                }
            }
            return yt
        }

        /** Warm the lookup for a song that is not playing yet. True if a stream is ready. */
        fun prefetch(extractor: AudioStreamExtractor, videoId: String, title: String, artist: String): Boolean =
            try {
                resolveStream(extractor, videoId, title, artist, forDownload = false)
                true
            } catch (e: Exception) {
                android.util.Log.w("ChordPrefetch", "prefetch failed for $videoId: ${e.message}")
                false
            }

        /** Drop remembered YouTube link, so next resolve asks fresh. */
        fun forget(videoId: String) {
            ytMemo.remove(videoId)
        }

        fun createDummyUri(videoId: String): String {
            return "$SCHEME$videoId"
        }

        /** Same uri plus title/artist so addons can search. Also the cache key. */
        fun createDummyUri(song: Song): String {
            // "Song • Artist • Album" -> "Artist". Old code took "Song", so addon searched the wrong artist.
            val artist = com.example.chord.data.history.HistoryStore.cleanArtist(song.details)
            return "$SCHEME${song.videoId}?t=${android.net.Uri.encode(song.title)}&a=${android.net.Uri.encode(artist)}&s=${AddonStore.sig()}&m=${MonochromeResolver.cacheSignature()}${if (StreamInfoStore.isForcedYoutube(song.videoId)) "&y=1" else ""}"
        }
    }
}