package com.example.chord.playback

import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import com.example.chord.data.local.LocalMusicRepository
import com.example.chord.data.model.Song
import com.example.chord.playback.addon.AddonResolver
import com.example.chord.playback.addon.MonochromeResolver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Keeps music playing when a stream dies. Same item, same position, same queue, always.
 *
 * Layer 1 is ExoPlayer itself: a failed load is retried and resumed at the byte it reached
 * (Range request, see [PlaybackDataSources]). This class only sees what that could not fix.
 *
 * What it does, by kind of error:
 *   TRANSIENT  timeout, reset, EOF, 5xx: try the SAME stream again, with growing waits.
 *              The link, the provider and the codec are not touched. One blip never costs FLAC.
 *   STALE      403/401/404/410, broken file: ask the same providers for a fresh link. Once.
 *   RATE LIMIT 429: let that provider rest, go to the next one now.
 *   UNPLAYABLE decoder refuses this provider's file: next provider.
 *
 * Next provider ([fallBack]) comes only after the steps above failed. The failing provider rests
 * for a while ([ProviderHealth]) and is tried again later. It is never banned for good.
 * Offline: nothing is switched and nothing is skipped. The song waits, the play button retries.
 *
 * All calls must come from the player's thread (service scope is Main).
 */
@UnstableApi
class PlaybackRecovery(
    private val player: Player,
    private val scope: CoroutineScope,
    /** True when the phone has a network. Offline errors must not burn providers or the queue. */
    private val isOnline: () -> Boolean = { true },
) : Player.Listener {

    private var netRetries = 0
    private var refreshed = false
    private var fallbacks = 0
    private var lastErrorAt = 0L
    private var retryJob: Job? = null

    private enum class Kind { TRANSIENT, STALE, RATE_LIMITED, UNPLAYABLE }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        // New song: clean slate.
        retryJob?.cancel()
        netRetries = 0
        refreshed = false
        fallbacks = 0
        lastErrorAt = 0L
    }

    override fun onPlayerError(error: PlaybackException) {
        val item = player.currentMediaItem ?: return
        Log.w(TAG, "player error ${error.errorCodeName} on ${item.mediaId} (net $netRetries, fallbacks $fallbacks)", error)

        // Paused by the person: do not wake playback behind their back. Play button retries.
        if (!player.playWhenReady) return

        // Saved song whose file vanished: stream it instead.
        if (error.isMissingFile() && fallBackToStream(item)) return

        if (item.localConfiguration?.uri?.scheme != "chord" || error.isMissingFile()) {
            skip()
            return
        }

        // The song played fine for a while since the last error: earlier trouble is forgiven.
        val now = SystemClock.elapsedRealtime()
        if (lastErrorAt != 0L && now - lastErrorAt > QUIET_RESET_MS) {
            netRetries = 0
            refreshed = false
        }
        lastErrorAt = now

        when (error.kind()) {
            Kind.TRANSIENT -> when {
                netRetries < NET_RETRIES -> retrySame(item)
                !isOnline() -> netRetries = 0               // offline: wait here, play button retries
                !fallBack(item) -> giveUp(error)
            }
            Kind.STALE -> when {
                !refreshed -> refresh(item, error)
                !fallBack(item) -> giveUp(error)
            }
            Kind.RATE_LIMITED -> {
                if (item.currentProvider().startsWith("monochrome:")) MonochromeResolver.backOff(error.retryAfter())
                if (!fallBack(item, error.retryAfterMs())) giveUp(error)
            }
            Kind.UNPLAYABLE -> if (!fallBack(item)) skip()
        }
    }

    /** Same stream, same link, same spot. The disk cache keeps what already arrived. */
    private fun retrySame(item: MediaItem) {
        val attempt = ++netRetries
        retryJob?.cancel()
        retryJob = scope.launch {
            delay(BACKOFF_MS shl (attempt - 1))   // 1 s, 2 s, 4 s
            if (player.currentMediaItem?.mediaId != item.mediaId) return@launch   // person skipped
            player.prepare()
        }
    }

    /** Link looks dead or wrong: ask again, same providers. Same spot. */
    private fun refresh(item: MediaItem, error: PlaybackException) {
        refreshed = true
        // A file that will not parse may be a half or broken copy on disk (Mochy drops it too): start clean.
        if (error.isParseError()) AudioCacheManager.evict(item.mediaId)
        forgetLinks(item, deadId = error.statusCode() == 404 || error.statusCode() == 410)
        player.prepare()
    }

    /**
     * Move this song to the next provider, from the same spot. False when there is none left
     * (YouTube is already playing, or too many switches). The failed provider rests, not banned.
     */
    private fun fallBack(item: MediaItem, restMs: Long = 0L): Boolean {
        val provider = item.currentProvider()
        if (provider.isBlank() || provider == ProviderHealth.YOUTUBE || fallbacks >= MAX_FALLBACKS) return false
        fallbacks++
        netRetries = 0
        refreshed = false
        ProviderHealth.markFailed(provider, restMs)
        forgetLinks(item, deadId = false)
        Log.w(TAG, "${item.mediaId}: $provider failed after recovery, trying the next provider")
        player.prepare()
        return true
    }

    /** Nothing left to try. Network trouble: stay on the song. Anything else: next song. */
    private fun giveUp(error: PlaybackException) {
        if (error.isNetwork()) netRetries = 0 else skip()
    }

    private fun skip() {
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
        val streamItem = item.buildUpon()
            .setUri(Uri.parse(StreamResolver.createDummyUri(item.toSong())))
            .build()
        player.replaceMediaItem(index, streamItem)
        player.seekTo(index, position)
        player.prepare()
        return true
    }

    /** Forget every in-memory link, so the next open resolves fresh. */
    private fun forgetLinks(item: MediaItem, deadId: Boolean) {
        val song = item.toSong()
        StreamResolver.forget(song.videoId)
        AddonResolver.forgetMemo(song)
        MonochromeResolver.forget(song)
        // Server says "no such track": the saved match is wrong, search again.
        if (deadId) MonochromeResolver.forgetSavedId(song)
    }

    private fun MediaItem.currentProvider(): String = StreamResolver.currentStream(mediaId)?.provider.orEmpty()

    private fun MediaItem.toSong() = Song(
        title = mediaMetadata.title?.toString().orEmpty(),
        details = mediaMetadata.artist?.toString().orEmpty(),
        videoId = mediaId,
    )

    private fun PlaybackException.isMissingFile() = errorCode == PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND ||
            errorCode == PlaybackException.ERROR_CODE_IO_NO_PERMISSION

    private fun PlaybackException.kind(): Kind {
        val status = statusCode()
        return when {
            status == 429 -> Kind.RATE_LIMITED
            status == 401 || status == 403 || status == 404 || status == 410 || status == 416 -> Kind.STALE
            // 200 OK with a web page or half a file instead of audio.
            isParseError() -> Kind.STALE
            errorCode == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED ||
                    errorCode == PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED ||
                    errorCode == PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED ||
                    errorCode == PlaybackException.ERROR_CODE_DECODING_FAILED ||
                    errorCode == PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED -> Kind.UNPLAYABLE
            else -> Kind.TRANSIENT   // timeouts, resets, EOF, 5xx, lookup failures
        }
    }

    private fun PlaybackException.isParseError() =
        errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED ||
                errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED

    private fun PlaybackException.statusCode(): Int? =
        (cause as? HttpDataSource.InvalidResponseCodeException)?.responseCode

    private fun PlaybackException.retryAfter(): String? =
        (cause as? HttpDataSource.InvalidResponseCodeException)?.headerFields?.entries
            ?.firstOrNull { it.key.equals("Retry-After", ignoreCase = true) }?.value?.firstOrNull()

    private fun PlaybackException.retryAfterMs(): Long =
        retryAfter()?.trim()?.toLongOrNull()?.times(1000)?.coerceIn(5_000L, 10 * 60_000L) ?: 0L

    /**
     * Network trouble. Link lookup runs inside the data source, so offline it dies as a wrapped
     * UnknownHostException that ExoPlayer files as plain IO_UNSPECIFIED. Look at the causes too.
     */
    private fun PlaybackException.isNetwork(): Boolean =
        errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
                errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ||
                generateSequence(cause) { it.cause }.take(8).any {
                    it is java.net.UnknownHostException || it is java.net.SocketException ||
                            it is java.net.SocketTimeoutException || it is java.io.EOFException
                }

    fun release() {
        retryJob?.cancel()
    }

    private companion object {
        const val TAG = "ChordRecovery"
        // ===================== TUNABLES (safe to change) =====================
        /** Same-stream retries before a link refresh or a provider switch. Waits 1 s, 2 s, 4 s. */
        const val NET_RETRIES = 3
        const val BACKOFF_MS = 1_000L
        /** Provider switches allowed per song (several Monochrome servers + addons, then YouTube). */
        const val MAX_FALLBACKS = 3
        /** Playing this long without an error clears the retry counters. */
        const val QUIET_RESET_MS = 60_000L
        // ====================================================================
    }
}
