package com.example.chord.playback

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy

/**
 * The one place that builds the audio data path. Playback (both players) and "add to cache"
 * use it, so they cannot drift apart or disagree on cache keys.
 *
 *   Routing ── phone files ──────────────────────────────► DefaultDataSource
 *           └─ chord:// ─► StreamResolver ─► Cache ─► OkHttp (shared ChordHttp.stream client)
 *
 * The resolver is OUTSIDE the cache: it picks the provider first and names the cache key after it.
 * OkHttp (not HttpURLConnection): Range resume, exact EOF check against Content-Length, one shared
 * connection pool and DNS fallback.
 */
@UnstableApi
object PlaybackDataSources {

    /** Disk cache over the shared OkHttp client. Keys come from the already resolved DataSpec. */
    private fun cacheFactory(context: Context): CacheDataSource.Factory {
        val http = OkHttpDataSource.Factory(ChordHttp.stream).setUserAgent(USER_AGENT)
        return CacheDataSource.Factory()
            .setCache(AudioCacheManager.getInstance(context))
            .setUpstreamDataSourceFactory(http)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    }

    /** chord:// streams: resolve -> disk cache -> network. */
    fun remoteFactory(context: Context, extractor: AudioStreamExtractor): DataSource.Factory =
        ResolvingDataSource.Factory(cacheFactory(context), StreamResolver(extractor))

    /**
     * Download a whole song into the stream cache (blocking, call off the main thread).
     * Resolves first, then writes under the same provider key playback will read.
     */
    fun cacheWholeSong(context: Context, extractor: AudioStreamExtractor, chordUri: android.net.Uri) {
        val resolved = StreamResolver(extractor).resolveDataSpec(DataSpec(chordUri))
        CacheWriter(cacheFactory(context).createDataSource(), resolved, null, null).cache()
    }

    fun mediaSourceFactory(context: Context, extractor: AudioStreamExtractor): DefaultMediaSourceFactory {
        // Phone files and saved songs (file:// or content://) skip the cache: route by URI scheme.
        val local = DefaultDataSource.Factory(context)
        return DefaultMediaSourceFactory(RoutingDataSourceFactory(local, remoteFactory(context, extractor)))
            .setLoadErrorHandlingPolicy(ChordLoadErrorPolicy())
    }

    /**
     * ExoPlayer already retries a failed load by itself and resumes at the byte it had reached
     * (Range request, nothing restarts). That is right for network blips, so it stays.
     *
     * It must NOT retry these, because the same request can never work. They go to
     * [PlaybackRecovery] at once, which refreshes the link or switches provider:
     *   429 rate limit, 401/403 link expired, 404/410 track gone.
     */
    private class ChordLoadErrorPolicy : DefaultLoadErrorHandlingPolicy() {
        override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
            val e = loadErrorInfo.exception
            if (e is HttpDataSource.InvalidResponseCodeException && e.responseCode in NO_LOADER_RETRY) return C.TIME_UNSET
            return super.getRetryDelayMsFor(loadErrorInfo)
        }
    }

    private val NO_LOADER_RETRY = setOf(401, 403, 404, 410, 429)

    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
}
