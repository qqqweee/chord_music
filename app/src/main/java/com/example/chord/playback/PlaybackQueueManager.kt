package com.example.chord.playback

import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.example.chord.data.model.Song
import com.example.chord.data.repository.MusicRepository
import kotlinx.coroutines.CoroutineScope
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

    fun playSingleAndStartRadio(song: Song) {
        scope.launch {
            queueMutex.withLock {
                resetQueueState(song.videoId)
                QueueStore.replace(listOf(song))

                val generation = currentGeneration

                // Play immediately
                player.setMediaSource(createMediaSource(song))
                player.prepare()
                player.play()

                // Fetch next tracks in background
                loadNextPageInternal(generation)
            }
        }
    }

    fun startRadioFromSeed(seed: Song) {
        scope.launch {
            queueMutex.withLock {
                resetQueueState(seed.videoId)
                QueueStore.replace(listOf(seed))

                val generation = currentGeneration

                // Clear everything after the current playing item
                val index = player.currentMediaItemIndex
                if (index >= 0 && index + 1 < player.mediaItemCount) {
                    player.removeMediaItems(index + 1, player.mediaItemCount)
                }

                loadNextPageInternal(generation)
            }
        }
    }

    fun maybeLoadMore() {
        scope.launch {
            queueMutex.withLock {
                if (isExhausted || seedVideoId == null) return@withLock

                val index = player.currentMediaItemIndex
                if (index < 0) return@withLock

                val remaining = player.mediaItemCount - index - 1
                if (remaining <= PREFETCH_THRESHOLD) {
                    loadNextPageInternal(currentGeneration)
                }
            }
        }
    }

    private suspend fun loadNextPageInternal(generation: Int) {
        if (isExhausted || generation != currentGeneration) return
        val seed = seedVideoId ?: return

        try {
            val page = repository.getRadioPage(seed, continuationToken)

            if (generation != currentGeneration) return

            continuationToken = page.continuation
            if (page.continuation.isNullOrBlank()) {
                isExhausted = true
            }

            val candidates = page.songs
                .filter { it.videoId.isNotBlank() && seenIds.add(it.videoId) }
                .take(MAX_PAGE_SIZE)

            QueueStore.append(candidates)

            val sources = candidates.map { createMediaSource(it) }
            if (sources.isNotEmpty()) {
                player.addMediaSources(sources)
            }
        } catch (e: Exception) {
            Log.e("QueueManager", "Failed to load radio page", e)
        }
    }

    private fun resetQueueState(newSeedId: String) {
        currentGeneration++
        seedVideoId = newSeedId
        continuationToken = null
        isExhausted = false
        seenIds.clear()
        seenIds.add(newSeedId)
    }

    private fun createMediaSource(song: Song) = mediaSourceFactory.createMediaSource(
        MediaItem.Builder()
            .setMediaId(song.videoId)
            .setUri(StreamResolver.createDummyUri(song.videoId))
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(song.title)
                    .setArtist(song.details)
                    .build()
            )
            .build()
    )

    fun release() {
        currentGeneration++
        seenIds.clear()
        QueueStore.clear()
    }

    companion object {
        private const val PREFETCH_THRESHOLD = 3
        private const val MAX_PAGE_SIZE = 10
    }
}