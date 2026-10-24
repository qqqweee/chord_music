package com.example.chord.playback

import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.ResolvingDataSource
import kotlinx.coroutines.runBlocking

@UnstableApi
class StreamResolver(
    private val extractor: AudioStreamExtractor
) : ResolvingDataSource.Resolver {

    override fun resolveDataSpec(dataSpec: DataSpec): DataSpec {
        val uriStr = dataSpec.uri.toString()

        if (uriStr.startsWith(SCHEME)) {
            val videoId = uriStr.removePrefix(SCHEME)

            // Safe to runBlocking here as ResolvingDataSource calls this
            // on an internal ExoPlayer background loading thread.
            val stream = runBlocking { extractor.extract(videoId) }

            return dataSpec.buildUpon()
                .setUri(stream.url)
                .setHttpRequestHeaders(stream.headers)
                .build()
        }
        return dataSpec
    }

    companion object {
        const val SCHEME = "chord://"

        fun createDummyUri(videoId: String): String {
            return "$SCHEME$videoId"
        }
    }
}