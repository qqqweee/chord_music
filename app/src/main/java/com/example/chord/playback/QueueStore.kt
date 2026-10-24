
package com.example.chord.playback

import com.example.chord.data.model.Song
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

object QueueStore {
    private val _queue = MutableStateFlow<List<Song>>(emptyList())
    val queue = _queue.asStateFlow()

    fun replace(songs: List<Song>) {
        _queue.value = songs.distinctBy { it.videoId }
    }

    fun append(songs: List<Song>) {
        val existing = _queue.value.mapTo(mutableSetOf()) { it.videoId }
        _queue.value = _queue.value + songs.filter {
            it.videoId.isNotBlank() && existing.add(it.videoId)
        }
    }

    fun clear() {
        _queue.value = emptyList()
    }
}
