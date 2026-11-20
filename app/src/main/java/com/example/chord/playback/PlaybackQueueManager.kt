package com.example.chord.playback

import com.example.chord.data.local.LocalMusicRepository
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.example.chord.data.model.Song
import com.example.chord.data.repository.MusicRepository
import com.example.chord.data.repository.RadioPage
import com.example.chord.data.settings.AppSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class PlaybackQueueManager(
    private val player: ExoPlayer,
    private val repository: MusicRepository,
    private val mediaSourceFactory: DefaultMediaSourceFactory,
    private val scope: CoroutineScope
) {
    private val queueMutex = Mutex()
    private val seenIds = mutableSetOf<String>()

    private var currentGeneration = 0
    private var seedVideoId: String? = null
    private var continuationToken: String? = null
    private var isExhausted = false
    /** The one running "load more" job. A new play cancels it, so it never makes the new song wait. */
    private var fillJob: Job? = null

    private fun minAhead() = AppSettings.queueSize

    private fun cancelFill() { fillJob?.cancel(); fillJob = null }

    private fun launchFill(generation: Int) {
        if (fillJob?.isActive == true) return
        fillJob = scope.launch { fill(generation, minAhead()) }
    }

    @OptIn(UnstableApi::class)
    fun playSingleAndStartRadio(song: Song) {
        cancelFill()
        scope.launch {
            val generation = queueMutex.withLock {
                resetQueueState(song.videoId)
                // Phone songs have no radio.
                if (LocalMusicRepository.isLocal(song.videoId)) isExhausted = true
                QueueStore.replace(listOf(song))
                player.setMediaSource(createMediaSource(song))
                player.prepare()
                player.play()
                currentGeneration
            }
            launchFill(generation)
        }
    }

    fun startRadioFromSeed(seed: Song) {
        cancelFill()
        scope.launch {
            val generation = queueMutex.withLock {
                resetQueueState(seed.videoId)
                QueueStore.replace(listOf(seed))

                // Clear everything after the current playing item
                val index = player.currentMediaItemIndex
                if (index >= 0 && index + 1 < player.mediaItemCount) {
                    player.removeMediaItems(index + 1, player.mediaItemCount)
                }
                currentGeneration
            }
            launchFill(generation)
        }
    }

    /**
     * Plays a fixed list (playlist or album) from [startIndex]. Radio from the
     * last song fills in when the list runs low, so playback never just stops.
     */
    @OptIn(UnstableApi::class)
    fun playQueue(songs: List<Song>, startIndex: Int, startPositionMs: Long = 0L) {
        if (songs.isEmpty()) return
        cancelFill()
        scope.launch {
            queueMutex.withLock {
                currentGeneration++
                // Radio from the last song, so playback goes on when the list ends.
                val last = songs.last().videoId
                seedVideoId = last
                continuationToken = null
                isExhausted = LocalMusicRepository.isLocal(last)
                seenIds.clear()
                seenIds.addAll(songs.map { it.videoId })

                QueueStore.replace(songs)
                player.setMediaSources(
                    songs.map { createMediaSource(it) },
                    startIndex.coerceIn(0, songs.lastIndex),
                    startPositionMs.coerceAtLeast(0L),
                )
                player.prepare()
                player.play()
            }
            // Short list: top up with radio at once.
            maybeLoadMore()
        }
    }

    /** Play next ([next] = true) or add to the end of the queue. Empty queue: just play it. */
    @OptIn(UnstableApi::class)
    fun enqueue(song: Song, next: Boolean) {
        if (player.mediaItemCount == 0) { playSingleAndStartRadio(song); return }
        scope.launch {
            queueMutex.withLock {
                seenIds.add(song.videoId)
                val at = if (next) player.currentMediaItemIndex + 1 else player.mediaItemCount
                player.addMediaSource(at.coerceIn(0, player.mediaItemCount), createMediaSource(song))
            }
        }
    }

    fun maybeLoadMore() {
        if (fillJob?.isActive == true) return
        if (isExhausted || seedVideoId == null) return
        val index = player.currentMediaItemIndex
        if (index < 0) return
        val remaining = player.mediaItemCount - index - 1
        if (remaining > maxOf(4, minAhead() / 2)) return

        val generation = currentGeneration
        fillJob = scope.launch { fill(generation, minAhead()) }
    }

    /**
     * Last song ended and nothing came next (radio was slow or failed). Load more now and
     * move on, so music never just stops. Safe to call often.
     */
    fun onPlaybackEnded() {
        if (player.playbackState != Player.STATE_ENDED || !player.playWhenReady) return
        val generation = currentGeneration
        cancelFill()
        fillJob = scope.launch {
            fill(generation, minAhead())
            if (generation == currentGeneration &&
                player.playbackState == Player.STATE_ENDED &&
                player.hasNextMediaItem()
            ) {
                player.seekToNextMediaItem()
                player.play()
            }
        }
    }

    /**
     * Keep at least [minAhead] songs after the current one. Up to [MAX_PAGES_PER_FILL] pages per call.
     * Network runs OUTSIDE the mutex, so skip, enqueue and swap never wait for the radio.
     */
    private suspend fun fill(generation: Int, minAhead: Int) {
        repeat(MAX_PAGES_PER_FILL) {
            val (seed, token) = queueMutex.withLock {
                if (isExhausted || generation != currentGeneration) return
                val ahead = player.mediaItemCount - player.currentMediaItemIndex.coerceAtLeast(0) - 1
                if (ahead >= minAhead) return
                (seedVideoId ?: return) to continuationToken
            }

            val page = try {
                repository.getRadioPage(seed, token)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load radio page", e)
                return
            }

            val added = queueMutex.withLock { appendPage(generation, page) }
            if (!added) return
        }
    }

    /** Runs under the mutex. False when the page is stale or the radio has nothing new. */
    @OptIn(UnstableApi::class)
    private fun appendPage(generation: Int, page: RadioPage): Boolean {
        if (generation != currentGeneration) return false

        // Lazy: only songs we really queue are marked as seen.
        val candidates = page.songs.asSequence()
            .filter { it.videoId.isNotBlank() && seenIds.add(it.videoId) }
            .take(maxOf(minAhead(), 10))
            .toList()

        if (page.continuation.isNullOrBlank()) {
            // End of this radio chain. Do not stop: restart radio from the newest song.
            // No new songs at all = nothing left to find, then (and only then) we are done.
            val newSeed = candidates.lastOrNull()?.videoId
            if (newSeed == null) {
                isExhausted = true
            } else {
                seedVideoId = newSeed
                continuationToken = null
            }
        } else {
            continuationToken = page.continuation
        }

        if (candidates.isEmpty()) return !isExhausted

        QueueStore.append(candidates)
        player.addMediaSources(candidates.map { createMediaSource(it) })
        return true
    }

    private fun resetQueueState(newSeedId: String) {
        currentGeneration++
        seedVideoId = newSeedId
        continuationToken = null
        isExhausted = false
        seenIds.clear()
        seenIds.add(newSeedId)
    }

    @OptIn(UnstableApi::class)
    private fun createMediaSource(song: Song): androidx.media3.exoplayer.source.MediaSource {
        // Last play fell back to YouTube while an addon was on: drop that cached copy so the addon is asked again.
        if (StreamInfoStore.takeDegraded(song.videoId)) {
            runCatching { AudioCacheManager.peek()?.removeResource(StreamResolver.createDummyUri(song)) }
        }
        return buildMediaSource(song)
    }

    @OptIn(UnstableApi::class)
    private fun buildMediaSource(song: Song) = mediaSourceFactory.createMediaSource(buildMediaItem(song))

    private fun buildMediaItem(song: Song): MediaItem =
        MediaItem.Builder()
            .setMediaId(song.videoId)
            .setUri(
                when {
                    LocalMusicRepository.isLocal(song.videoId) -> LocalMusicRepository.uriOf(song.videoId)
                    // Saved song: play the file. Else stream.
                    else -> DownloadStore.audioUriOf(song.videoId)
                        ?: android.net.Uri.parse(StreamResolver.createDummyUri(song))
                }
            )
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(song.title)
                    .setArtist(song.details)
                    .setArtworkUri(
                        song.thumbnailUrl
                            ?.takeIf { it.isNotBlank() }
                            ?.let { android.net.Uri.parse(it) }
                    )
                    .build()
            )
            .build()

    /**
     * Give the playing song a fresh stream WITHOUT touching the queue.
     * Old way restarted the whole queue and radio. Now: same spot, same queue, only this item swaps.
     * Call after the new link is already resolved, so the swap is quick.
     */
    @OptIn(UnstableApi::class)
    fun swapCurrent(song: Song) {
        scope.launch {
            queueMutex.withLock {
                val index = player.currentMediaItemIndex
                val item = player.currentMediaItem
                if (index < 0 || item == null || item.mediaId != song.videoId) return@withLock
                val position = player.currentPosition
                val wasPlaying = player.playWhenReady
                player.replaceMediaItem(index, buildMediaItem(song))
                player.seekTo(index, position)
                player.playWhenReady = wasPlaying
            }
        }
    }

    fun release() {
        cancelFill()
        currentGeneration++
        seenIds.clear()
        QueueStore.clear()
    }

    private companion object {
        const val TAG = "QueueManager"
        const val MAX_PAGES_PER_FILL = 4
    }
}