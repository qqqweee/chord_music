
package com.example.chord.playback

import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import androidx.media3.session.SessionToken
import com.example.chord.data.model.Song
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.Executor

data class PlaybackState(
    val title: String = "",
    val isPlaying: Boolean = false,
    val isLoading: Boolean = false,
    val playbackState: Int = Player.STATE_IDLE,
    val error: String? = null,
    val position: Long = 0L,
    val duration: Long = 0L,
    val canSeek: Boolean = false,
    val queue: List<Song> = emptyList(),
    val currentQueueIndex: Int = -1
)

class PlaybackController(context: Context) {

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val mainExecutor = Executor { command ->
        mainHandler.post(command)
    }

    private val _state = MutableStateFlow(PlaybackState())
    val state: StateFlow<PlaybackState> = _state.asStateFlow()

    private var mediaController: MediaController? = null
    private var playerListener: Player.Listener? = null
    private var released = false

    private val progressUpdater = object : Runnable {
        override fun run() {
            if (released) return
            val controller = mediaController ?: return

            updateProgress(controller)

            if (controller.isPlaying) {
                mainHandler.postDelayed(this, 500L)
            }
        }
    }

    val controllerFuture: ListenableFuture<MediaController> =
        MediaController.Builder(
            appContext,
            SessionToken(
                appContext,
                ComponentName(appContext, PlaybackService::class.java)
            )
        ).buildAsync().also { future ->
            future.addListener({
                if (released) return@addListener

                try {
                    val controller = future.get()

                    if (released) {
                        controller.release()
                        return@addListener
                    }

                    mediaController = controller
                    attachPlayerListener(controller)
                } catch (e: Exception) {
                    if (!released) {
                        _state.value = _state.value.copy(
                            isLoading = false,
                            error = "Playback connection failed: ${e.message}"
                        )
                    }
                }
            }, mainExecutor)
        }

    private fun attachPlayerListener(controller: MediaController) {
        val listener = object : Player.Listener {

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (released) return
                _state.value = _state.value.copy(isPlaying = isPlaying)
                updateProgress(controller)
                updateQueue(controller)

                if (isPlaying) startProgressUpdates()
                else mainHandler.removeCallbacks(progressUpdater)
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (released) return

                _state.value = _state.value.copy(
                    playbackState = playbackState,
                    isLoading = playbackState == Player.STATE_BUFFERING
                )

                updateProgress(controller)
                updateQueue(controller)

                if (playbackState == Player.STATE_ENDED) {
                    mainHandler.removeCallbacks(progressUpdater)
                } else if (controller.isPlaying) {
                    startProgressUpdates()
                }
            }

            override fun onMediaItemTransition(
                mediaItem: MediaItem?,
                reason: Int
            ) {
                if (released) return

                _state.value = _state.value.copy(
                    title = mediaItem?.mediaMetadata?.title
                        ?.toString().orEmpty(),
                    position = 0L,
                    duration = 0L,
                    canSeek = false,
                    error = null
                )

                updateProgress(controller)
                updateQueue(controller)
            }

            override fun onTimelineChanged(
                timeline: androidx.media3.common.Timeline,
                reason: Int
            ) {
                if (released) return
                updateQueue(controller)
                updateProgress(controller)
            }

            override fun onPlayerError(error: PlaybackException) {
                if (released) return

                mainHandler.removeCallbacks(progressUpdater)
                _state.value = _state.value.copy(
                    isPlaying = false,
                    isLoading = false,
                    error = "Playback error: ${error.errorCodeName}"
                )
            }
        }

        playerListener = listener
        controller.addListener(listener)

        _state.value = _state.value.copy(
            title = controller.currentMediaItem
                ?.mediaMetadata?.title?.toString().orEmpty(),
            isPlaying = controller.isPlaying,
            isLoading = controller.playbackState == Player.STATE_BUFFERING,
            playbackState = controller.playbackState,
            error = null
        )

        updateQueue(controller)
        updateProgress(controller)

        if (controller.isPlaying) startProgressUpdates()
    }

    private fun updateQueue(controller: MediaController) {
        val queue = (0 until controller.mediaItemCount).mapNotNull { index ->
            val item = controller.getMediaItemAt(index)
            val videoId = item.mediaId

            if (videoId.isBlank()) return@mapNotNull null

            Song(
                title = item.mediaMetadata.title?.toString().orEmpty(),
                details = item.mediaMetadata.artist?.toString().orEmpty(),
                videoId = videoId
            )
        }

        _state.value = _state.value.copy(
            queue = queue,
            currentQueueIndex = controller.currentMediaItemIndex
        )
    }


    private fun startProgressUpdates() {
        if (released) return
        mainHandler.removeCallbacks(progressUpdater)
        mainHandler.post(progressUpdater)
    }

    private fun updateProgress(controller: MediaController) {
        if (released) return

        val duration = controller.duration.coerceAtLeast(0L)
        val position = controller.currentPosition.coerceAtLeast(0L)

        _state.value = _state.value.copy(
            position = if (duration > 0L) {
                position.coerceAtMost(duration)
            } else position,
            duration = duration,
            canSeek = controller.isCurrentMediaItemSeekable
        )
    }


    fun refreshProgress() {
        if (!released) mediaController?.let(::updateProgress)
    }

    fun seekTo(positionMs: Long) {
        if (released) return

        val controller = mediaController ?: return
        if (!controller.isCurrentMediaItemSeekable) return

        val duration = controller.duration
        val target = positionMs.coerceAtLeast(0L)

        controller.seekTo(
            if (duration > 0L) target.coerceAtMost(duration) else target
        )
        updateProgress(controller)
    }

    fun play(song: Song): ListenableFuture<SessionResult>? {
        if (released) return null

        _state.value = _state.value.copy(
            title = song.title,
            isPlaying = false,
            isLoading = true,
            playbackState = Player.STATE_BUFFERING,
            position = 0L,
            duration = 0L,
            canSeek = false,
            error = null
        )

        val args = Bundle().apply {
            putString(PlaybackService.EXTRA_VIDEO_ID, song.videoId)
            putString(PlaybackService.EXTRA_TITLE, song.title)
            putString(PlaybackService.EXTRA_DETAILS, song.details)
        }

        return sendPlayCommand(args)
    }

    private fun sendPlayCommand(args: Bundle): ListenableFuture<SessionResult>? {
        val controller = mediaController ?: run {
            _state.value = _state.value.copy(
                isLoading = false,
                error = "Playback controller is not connected."
            )
            return null
        }

        return try {
            controller.sendCustomCommand(
                SessionCommand(PlaybackService.ACTION_PLAY_SONG, Bundle.EMPTY),
                args
            ).also { future ->
                future.addListener({
                    if (released) return@addListener

                    try {
                        val result = future.get()
                        if (result.resultCode != SessionResult.RESULT_SUCCESS) {
                            _state.value = _state.value.copy(
                                isLoading = false,
                                error = "Playback command failed (${result.resultCode})"
                            )
                        }
                        updateProgress(controller)
                        updateQueue(controller)
                    } catch (e: Exception) {
                        _state.value = _state.value.copy(
                            isLoading = false,
                            error = "Playback command failed: ${e.message}"
                        )
                    }
                }, mainExecutor)
            }
        } catch (e: Exception) {
            _state.value = _state.value.copy(
                isLoading = false,
                error = "Unable to send playback command: ${e.message}"
            )
            null
        }
    }

    fun togglePlayPause() {
        if (released) return
        val controller = mediaController ?: return

        when {
            controller.isPlaying -> controller.pause()
            controller.playbackState == Player.STATE_READY ||
                    controller.playbackState == Player.STATE_ENDED -> controller.play()
        }
    }

    fun next() {
        if (released) return
        mediaController?.let { controller ->
            if (controller.hasNextMediaItem()) {
                controller.seekToNextMediaItem()
            }
        }
    }

    fun previous() {
        if (released) return
        mediaController?.let { controller ->
            if (controller.currentPosition > PREVIOUS_RESTART_THRESHOLD_MS) {
                controller.seekTo(0L)
            } else if (controller.hasPreviousMediaItem()) {
                controller.seekToPreviousMediaItem()
            }
        }
    }

    fun playQueueItem(index: Int) {
        if (released) return
        val controller = mediaController ?: return

        if (index in 0 until controller.mediaItemCount) {
            controller.seekTo(index, 0L)
            controller.play()
        }
    }

    fun release() {
        if (released) return
        released = true

        mainHandler.removeCallbacks(progressUpdater)
        playerListener?.let { mediaController?.removeListener(it) }

        playerListener = null
        mediaController = null
        MediaController.releaseFuture(controllerFuture)
    }

    private companion object {
        const val PREVIOUS_RESTART_THRESHOLD_MS = 3_000L
    }
}
