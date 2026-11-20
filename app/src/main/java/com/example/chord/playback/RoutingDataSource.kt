package com.example.chord.playback

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener

/**
 * Files on the phone (content:// or file://) go straight to [local].
 * Everything else (chord:// streams) goes to [remote], which has the disk cache.
 * Keeps phone songs out of the 200 MB stream cache.
 */
@UnstableApi
class RoutingDataSourceFactory(
    private val local: DataSource.Factory,
    private val remote: DataSource.Factory,
) : DataSource.Factory {
    override fun createDataSource(): DataSource = Routing(local.createDataSource(), remote.createDataSource())

    private class Routing(private val local: DataSource, private val remote: DataSource) : DataSource {
        private var active: DataSource? = null

        override fun addTransferListener(listener: TransferListener) {
            local.addTransferListener(listener)
            remote.addTransferListener(listener)
        }

        override fun open(dataSpec: DataSpec): Long {
            val scheme = dataSpec.uri.scheme
            val source = if (scheme == "content" || scheme == "file") local else remote
            active = source
            return source.open(dataSpec)
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            active?.read(buffer, offset, length) ?: -1

        override fun getUri(): Uri? = active?.uri
        override fun getResponseHeaders(): Map<String, List<String>> = active?.responseHeaders ?: emptyMap()
        override fun close() {
            active?.close()
            active = null
        }
    }
}
