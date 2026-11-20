package com.example.chord.playback

import android.net.Uri
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.example.chord.data.local.LocalMusicRepository
import com.example.chord.data.model.Song
import com.example.chord.playback.addon.AddonResolver
import com.example.chord.playback.addon.MonochromeResolver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Keeps music playing when a stream dies mid-song (expired link, network blip, dead proxy).
 *
 * Before: ExoPlayer went IDLE on the first error and stayed there. Tapping play then rebuilt
 * the whole queue for that one song.
 *
 * Now, on error:
 *   1. Throw away every remembered link for the song, so the next open resolves fresh.
 *   2. After a short back-off, prepare() again. Same item, same position, same queue.
 *   3. From the 2nd try, also drop the half-cached file in case it is the broken part.
 *   4. Out of tries (or error can never be fixed by retry): skip to the next song.
 *
 * All calls must come from the player's thread (service scope is Main).
 */
@UnstableApi
class PlaybackRecovery(
    private val player: Player,
    private val scope: CoroutineScope,
) : Player.Listener {

    private var attempts = 0
    private var skipsInARow = 0
    private var retryJob: Job? = null
    private var healthyJob: Job? = null

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        // New song: clean slate. An error-triggered skip must not inherit old failures.
        retryJob?.cancel()
        attempts = 0
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        healthyJob?.cancel()
        if (!isPlaying) return
        // Only forgive old failures after a steady stretch of real playback.
        healthyJob = scope.launch {
            delay(HEALTHY_AFTER_MS)
            attempts = 0
            skipsInARow = 0
        }
    }

    override fun onPlayerError(error: PlaybackException) {
        val item = player.currentMediaItem ?: return
        Log.w(TAG, "player error ${error.errorCodeName} on ${item.mediaId} (attempt ${attempts + 1})", error)

        // Remember the drop. Resolver is patient with the lossless source for a while after.
        if (error.isNetwork()) StreamResolver.noteNetworkLoss()

        // Was FLAC when it stopped: retries must find FLAC again, not slide to AAC.
        if (item.localConfiguration?.uri?.scheme == "chord" && wasLossless(item)) {
            StreamResolver.requireLossless(item.mediaId)
        }

        // Paused by the person: do not wake playback behind their back. Play button retries.
        if (!player.playWhenReady) return

        // Saved song whose file vanished (deleted, folder gone, permission lost): stream it instead.
        if (error.isMissingFile() && fallBackToStream(item)) return

        val streamed = item.localConfiguration?.uri?.scheme == "chord"
        if (!streamed || !error.isRetryable() || attempts >= MAX_ATTEMPTS) {
            // No internet: skipping would only burn through the whole queue. Stop here, keep the spot.
            if (error.isNetwork()) {
                attempts = 0
                player.pause()
                return
            }
            skipOrGiveUp()
            return
        }

        attempts++
        val attempt = attempts
        retryJob?.cancel()
        retryJob = scope.launch {
            delay(BACKOFF_MS * attempt)
            // Item may have changed while we waited.
            if (player.currentMediaItem?.mediaId != item.mediaId) return@launch
            // Glued FLAC+AAC file (see StreamResolver.checkSameSource): always start the cache clean.
            val glued = error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED
            if (attempt >= MAX_ATTEMPTS) StreamResolver.allowFallback(item.mediaId)   // last try: AAC is better than silence
            forgetStream(item, dropDisk = attempt >= 2 || glued)
            player.prepare()
        }
    }

    private fun wasLossless(item: MediaItem): Boolean {
        val id = item.mediaId
        val src = StreamInfoStore.cacheSource(id)
        return src == "flac" || src == "alac" || StreamInfoStore.isLosslessLabel(StreamInfoStore.labels.value[id])
    }

    private fun skipOrGiveUp() {
        attempts = 0
        // Many dead songs in a row = something bigger (no network, dead source). Stop, do not skip forever.
        if (++skipsInARow > MAX_SKIPS_IN_A_ROW) {
            skipsInARow = 0
            player.pause()
            return
        }
        if (player.hasNextMediaItem()) {
            player.seekToNextMediaItem()
            player.prepare()
        }
        // No next song: stay in error state. UI shows it, play button retries.
    }

    /** Swap a dead file:// or content:// item for the stream of the same song. Same spot, same queue. */
    private fun fallBackToStream(item: MediaItem): Boolean {
        val scheme = item.localConfiguration?.uri?.scheme
        if (scheme != "file" && scheme != "content") return false
        if (LocalMusicRepository.isLocal(item.mediaId)) return false   // phone music has no stream
        val index = player.currentMediaItemIndex
        if (index < 0) return false
        val position = player.currentPosition.coerceAtLeast(0L)
        val song = Song(
            title = item.mediaMetadata.title?.toString().orEmpty(),
            details = item.mediaMetadata.artist?.toString().orEmpty(),
            videoId = item.mediaId,
        )
        val streamItem = item.buildUpon()
            .setUri(Uri.parse(StreamResolver.createDummyUri(song)))
            .build()
        player.replaceMediaItem(index, streamItem)
        player.seekTo(index, position)
        player.prepare()
        return true
    }

    private fun PlaybackException.isMissingFile(): Boolean = when (errorCode) {
        PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
        PlaybackException.ERROR_CODE_IO_NO_PERMISSION -> true
        else -> false
    }

    private fun PlaybackException.isNetwork(): Boolean {
        when (errorCode) {
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> return true
        }
        // Link lookup runs inside the data source. Offline, it dies with UnknownHostException,
        // and ExoPlayer files that as plain IO_UNSPECIFIED. Still a lost network: pause, do not skip.
        var t: Throwable? = cause
        var depth = 0
        while (t != null && depth++ < 8) {
            if (t is java.net.UnknownHostException ||
                t is java.net.ConnectException ||
                t is java.net.NoRouteToHostException ||
                t is java.net.SocketTimeoutException
            ) return true
            t = t.cause
        }
        return false
    }

    private fun PlaybackException.isRetryable(): Boolean = when (errorCode) {
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
        PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
        PlaybackException.ERROR_CODE_IO_NO_PERMISSION -> false
        else -> true
    }

    fun release() {
        retryJob?.cancel()
        healthyJob?.cancel()
    }

    companion object {
        private const val TAG = "ChordRecovery"
        private const val MAX_ATTEMPTS = 3
        private const val BACKOFF_MS = 1_000L
        private const val HEALTHY_AFTER_MS = 15_000L
        private const val MAX_SKIPS_IN_A_ROW = 5

        /** Forget every in-memory link for this song. Optionally drop its disk cache entry too. */
        fun forgetStream(item: MediaItem, dropDisk: Boolean) {
            val id = item.mediaId
            StreamResolver.forget(id)
            val song = Song(
                title = item.mediaMetadata.title?.toString().orEmpty(),
                details = item.mediaMetadata.artist?.toString().orEmpty(),
                videoId = id,
            )
            AddonResolver.forgetMemo(song)
            MonochromeResolver.forget(song)
            if (dropDisk) {
                val key = item.localConfiguration?.uri?.toString() ?: return
                runCatching { AudioCacheManager.peek()?.removeResource(key) }
                // Cache is empty now, so the note about its format is stale too.
                StreamInfoStore.setCacheSource(id, null)
            }
        }
    }
}