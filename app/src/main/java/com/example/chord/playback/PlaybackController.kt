package com.example.chord.playback

import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
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
    val currentQueueIndex: Int = -1,
    val shuffle: Boolean = false,
    val repeatMode: Int = Player.REPEAT_MODE_OFF
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
            saveProgress(controller)

            if (controller.isPlaying) {
                mainHandler.postDelayed(this, 500L)
            }
        }
    }

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var reconnects = 0

    /** What was playing when the app last closed. */
    private val lastStore = LastPlayedStore(appContext)

    /**
     * Saved song shown in the mini player, NOT loaded into the service yet. Exists only while the
     * player is empty. First play / next / previous press loads it (see [resumeSaved]).
     */
    private var pending: LastPlayedStore.Snapshot? = null
    private var lastSavedAt = 0L

    init {
        connect()
    }

    /** Connect to the service. Called at start, and again if the link drops. */
    @OptIn(UnstableApi::class)
    private fun connect() {
        if (released) return
        val future = MediaController.Builder(
            appContext,
            SessionToken(appContext, ComponentName(appContext, PlaybackService::class.java))
        ).setListener(object : MediaController.Listener {
            override fun onDisconnected(controller: MediaController) {
                mainHandler.post { handleDisconnected(controller) }
            }
        }).buildAsync()
        controllerFuture = future
        future.addListener({
            if (released) return@addListener
            try {
                val controller = future.get()
                if (released) {
                    controller.release()
                    return@addListener
                }
                reconnects = 0
                mediaController = controller
                attachPlayerListener(controller)
            } catch (e: Exception) {
                if (!released) {
                    _state.value = _state.value.copy(
                        isLoading = false,
                        error = "Playback connection failed: ${e.message}"
                    )
                    scheduleReconnect()
                }
            }
        }, mainExecutor)
    }

    private fun scheduleReconnect() {
        if (released || reconnects >= MAX_RECONNECTS) return
        reconnects++
        mainHandler.postDelayed({ if (mediaController == null) connect() }, 1_000L * reconnects)
    }

    /** Called when a command needs the link and it is down. */
    private fun ensureConnecting() {
        if (released || mediaController != null) return
        if (controllerFuture?.isDone == false) return   // already trying
        reconnects = 0
        connect()
    }

    private fun handleDisconnected(controller: MediaController) {
        if (released || controller !== mediaController) return
        mainHandler.removeCallbacks(progressUpdater)
        playerListener?.let { runCatching { controller.removeListener(it) } }
        playerListener = null
        mediaController = null
        _state.value = _state.value.copy(
            isPlaying = false,
            isLoading = false,
            error = "Playback connection lost. Reconnecting…"
        )
        scheduleReconnect()
    }

    private fun friendlyError(e: PlaybackException): String = when (e.errorCode) {
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> "No internet connection. Tap play to retry."
        PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> "Song file not found."
        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> "Source refused the stream. Tap play to retry."
        else -> "Playback error: ${e.errorCodeName}"
    }

    private fun attachPlayerListener(controller: MediaController) {
        val listener = object : Player.Listener {

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (released) return
                _state.value = _state.value.copy(isPlaying = isPlaying)
                updateProgress(controller)

                if (isPlaying) startProgressUpdates()
                else {
                    mainHandler.removeCallbacks(progressUpdater)
                    saveProgress(controller, force = true)
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (released) return

                _state.value = _state.value.copy(
                    playbackState = playbackState,
                    isLoading = playbackState == Player.STATE_BUFFERING,
                    // Service recovered by itself: drop the old error.
                    error = if (playbackState == Player.STATE_READY || playbackState == Player.STATE_BUFFERING) null else _state.value.error
                )

                updateProgress(controller)

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
                saveProgress(controller, force = true)
            }

            override fun onTimelineChanged(
                timeline: androidx.media3.common.Timeline,
                reason: Int
            ) {
                if (released) return
                updateQueue(controller)
                updateProgress(controller)
            }

            override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
                if (released) return
                _state.value = _state.value.copy(shuffle = shuffleModeEnabled)
            }

            override fun onRepeatModeChanged(repeatMode: Int) {
                if (released) return
                _state.value = _state.value.copy(repeatMode = repeatMode)
            }

            override fun onPlayerError(error: PlaybackException) {
                if (released) return

                mainHandler.removeCallbacks(progressUpdater)
                _state.value = _state.value.copy(
                    isPlaying = false,
                    isLoading = false,
                    error = friendlyError(error)
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
            shuffle = controller.shuffleModeEnabled,
            repeatMode = controller.repeatMode,
            error = null
        )

        updateQueue(controller)
        updateProgress(controller)

        if (controller.isPlaying) startProgressUpdates()

        // Fresh service with nothing loaded (app was closed): show the last song in the mini player.
        if (controller.mediaItemCount == 0) restoreSaved()
    }

    /** Fill the UI from the saved snapshot. The service player stays empty until the person presses play. */
    private fun restoreSaved() {
        val snap = lastStore.load() ?: return
        pending = snap
        val song = snap.current
        QueueStore.replace(snap.queue)
        _state.value = _state.value.copy(
            title = song.title,
            isPlaying = false,
            isLoading = false,
            playbackState = Player.STATE_IDLE,
            error = null,
            position = snap.positionMs,
            duration = snap.durationMs,
            canSeek = false,
            queue = snap.queue,
            currentQueueIndex = snap.index,
        )
    }

    /**
     * Load the saved queue into the service and play. [index] and [positionMs] say where.
     * Plain play = same song, same spot. Next / previous = other song from 0.
     */
    private fun resumeSaved(snap: LastPlayedStore.Snapshot, index: Int, positionMs: Long) {
        val i = index.coerceIn(0, snap.queue.lastIndex)
        // Almost at the end of the song: start it again, do not play 2 seconds and skip.
        val atEnd = snap.durationMs > 0 && positionMs > snap.durationMs - END_RESTART_MARGIN_MS
        playQueue(snap.queue, i, if (atEnd) 0L else positionMs)
    }

    /** Remember where we are. Position is saved at most every few seconds, unless [force]. */
    private fun saveProgress(controller: MediaController, force: Boolean = false) {
        if (controller.mediaItemCount == 0) return
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastSavedAt < SAVE_EVERY_MS) return
        lastSavedAt = now
        lastStore.saveProgress(
            controller.currentMediaItemIndex,
            controller.currentPosition.coerceAtLeast(0L),
            controller.duration.coerceAtLeast(0L),
        )
    }

    private fun updateQueue(controller: MediaController) {
        // Player is empty on purpose: the saved song in the UI must stay.
        if (pending != null && controller.mediaItemCount == 0) return
        if (controller.mediaItemCount > 0) pending = null

        val queue = (0 until controller.mediaItemCount).mapNotNull { index ->
            val item = controller.getMediaItemAt(index)
            val videoId = item.mediaId

            if (videoId.isBlank()) return@mapNotNull null

            Song(
                title = item.mediaMetadata.title?.toString().orEmpty(),
                details = item.mediaMetadata.artist?.toString().orEmpty(),
                videoId = videoId,
                thumbnailUrl = item.mediaMetadata.artworkUri?.toString()
            )
        }

        _state.value = _state.value.copy(
            queue = queue,
            currentQueueIndex = controller.currentMediaItemIndex
        )
        QueueStore.replace(queue)
        if (controller.mediaItemCount > 0) lastStore.saveQueue(queue)
    }


    private fun startProgressUpdates() {
        if (released) return
        mainHandler.removeCallbacks(progressUpdater)
        mainHandler.post(progressUpdater)
    }

    private fun updateProgress(controller: MediaController) {
        if (released) return
        if (pending != null && controller.mediaItemCount == 0) return   // keep saved position on screen

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

    /** 0..1. Used by recap stories to fade songs in and out. Ignored if the player has no volume command. */
    fun setVolume(volume: Float) {
        if (released) return
        mediaController?.volume = volume.coerceIn(0f, 1f)
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

    @OptIn(UnstableApi::class)
    fun play(song: Song): ListenableFuture<SessionResult>? {
        if (released) return null
        pending = null

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
            song.thumbnailUrl?.let { putString(PlaybackService.EXTRA_THUMBNAIL_URL, it) }
        }

        return sendPlayCommand(args)
    }

    /**
     * New stream for this song (better quality, or back to Opus). Playing song keeps its spot
     * and the queue stays. No loading flash: old stream plays until the new one is ready.
     */
    @OptIn(UnstableApi::class)
    fun reload(song: Song): ListenableFuture<SessionResult>? {
        if (released) return null
        pending = null
        val args = Bundle().apply {
            putString(PlaybackService.EXTRA_VIDEO_ID, song.videoId)
            putString(PlaybackService.EXTRA_TITLE, song.title)
            putString(PlaybackService.EXTRA_DETAILS, song.details)
            song.thumbnailUrl?.let { putString(PlaybackService.EXTRA_THUMBNAIL_URL, it) }
        }
        return sendPlayCommand(args, PlaybackService.ACTION_RELOAD)
    }

    /** Sleep timer runs in the service. [minutes] = 0 cancels it. */
    @OptIn(UnstableApi::class)
    fun setSleepTimer(minutes: Int): ListenableFuture<SessionResult>? {
        if (released) return null
        val args = Bundle().apply { putInt(PlaybackService.EXTRA_MINUTES, minutes) }
        return sendPlayCommand(args, PlaybackService.ACTION_SLEEP)
    }

    /** Play next or add to the end of the queue. */
    @OptIn(UnstableApi::class)
    fun enqueue(song: Song, next: Boolean): ListenableFuture<SessionResult>? {
        if (released) return null
        pending = null
        val args = Bundle().apply {
            putString(PlaybackService.EXTRA_VIDEO_ID, song.videoId)
            putString(PlaybackService.EXTRA_TITLE, song.title)
            putString(PlaybackService.EXTRA_DETAILS, song.details)
            song.thumbnailUrl?.let { putString(PlaybackService.EXTRA_THUMBNAIL_URL, it) }
            putBoolean(PlaybackService.EXTRA_NEXT, next)
        }
        return sendPlayCommand(args, PlaybackService.ACTION_ENQUEUE)
    }

    /** Queue a whole playlist or album and start at [startIndex]. */
    @OptIn(UnstableApi::class)
    fun playQueue(songs: List<Song>, startIndex: Int, startPositionMs: Long = 0L): ListenableFuture<SessionResult>? {
        if (released) return null
        pending = null
        val list = songs.filter { it.videoId.isNotBlank() }.take(MAX_QUEUE)
        if (list.isEmpty()) return null
        val start = startIndex.coerceIn(0, list.lastIndex)

        _state.value = _state.value.copy(
            title = list[start].title,
            isPlaying = false,
            isLoading = true,
            playbackState = Player.STATE_BUFFERING,
            position = 0L,
            duration = 0L,
            canSeek = false,
            error = null
        )

        val args = Bundle().apply {
            putStringArray(PlaybackService.EXTRA_VIDEO_IDS, list.map { it.videoId }.toTypedArray())
            putStringArray(PlaybackService.EXTRA_TITLES, list.map { it.title }.toTypedArray())
            putStringArray(PlaybackService.EXTRA_DETAILS_LIST, list.map { it.details }.toTypedArray())
            putStringArray(PlaybackService.EXTRA_THUMBNAILS, list.map { it.thumbnailUrl.orEmpty() }.toTypedArray())
            putInt(PlaybackService.EXTRA_START_INDEX, start)
//            putLong(PlaybackService.EXTRA_START_POSITION, startPositionMs.coerceAtLeast(0L))
        }
        return sendPlayCommand(args, PlaybackService.ACTION_PLAY_QUEUE)
    }

    @OptIn(UnstableApi::class)
    private fun sendPlayCommand(
        args: Bundle,
        action: String = PlaybackService.ACTION_PLAY_SONG,
    ): ListenableFuture<SessionResult>? {
        val controller = mediaController ?: run {
            _state.value = _state.value.copy(
                isLoading = false,
                error = "Playback controller is not connected."
            )
            ensureConnecting()
            return null
        }

        return try {
            controller.sendCustomCommand(
                SessionCommand(action, Bundle.EMPTY),
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

        // Mini player shows the saved song and the service has nothing yet: load it and play.
        pending?.let { snap ->
            if (controller.mediaItemCount == 0) {
                resumeSaved(snap, snap.index, snap.positionMs)
                return
            }
        }

        if (controller.isPlaying) {
            controller.pause()
            return
        }
        when (controller.playbackState) {
            Player.STATE_IDLE -> {
                // Failed or never started. Retry the SAME item at the SAME spot.
                // Old code rebuilt the whole queue for this one song.
                // Service drops the stale link on error, so prepare() resolves a fresh one.
                controller.prepare()
                controller.play()
            }
            else -> controller.play()     // READY, BUFFERING, ENDED
        }
        _state.value = _state.value.copy(error = null)
    }

    fun next() {
        if (released) return
        pending?.let { snap ->
            if (mediaController?.mediaItemCount == 0) {
                if (snap.index < snap.queue.lastIndex) resumeSaved(snap, snap.index + 1, 0L)
                return
            }
        }
        mediaController?.let { controller ->
            if (controller.hasNextMediaItem()) {
                controller.seekToNextMediaItem()
            }
        }
    }

    fun previous() {
        if (released) return
        pending?.let { snap ->
            if (mediaController?.mediaItemCount == 0) {
                resumeSaved(snap, (snap.index - 1).coerceAtLeast(0), 0L)
                return
            }
        }
        mediaController?.let { controller ->
            if (controller.currentPosition > PREVIOUS_RESTART_THRESHOLD_MS) {
                controller.seekTo(0L)
            } else if (controller.hasPreviousMediaItem()) {
                controller.seekToPreviousMediaItem()
            } else {
                controller.seekTo(0L)
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

    fun removeQueueItem(index: Int) {
        if (released) return
        val controller = mediaController ?: return
        if (index in 0 until controller.mediaItemCount) {
            if (controller.mediaItemCount == 1) lastStore.clear()   // queue emptied by the person
            controller.removeMediaItem(index)
        }
    }

    fun moveQueueItem(from: Int, to: Int) {
        if (released) return
        val controller = mediaController ?: return
        val count = controller.mediaItemCount
        if (from == to || from !in 0 until count || to !in 0 until count) return
        controller.moveMediaItem(from, to)
    }

    fun setShuffle(enabled: Boolean) {
        if (!released) mediaController?.shuffleModeEnabled = enabled
    }

    fun setRepeatMode(mode: Int) {
        if (!released) mediaController?.repeatMode = mode
    }

    fun release() {
        if (released) return
        released = true

        mainHandler.removeCallbacks(progressUpdater)
        mediaController?.let { runCatching { saveProgress(it, force = true) } }
        playerListener?.let { mediaController?.removeListener(it) }

        playerListener = null
        mediaController = null
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
    }

    private companion object {
        const val PREVIOUS_RESTART_THRESHOLD_MS = 3_000L
        const val MAX_QUEUE = 500
        const val MAX_RECONNECTS = 3
        const val SAVE_EVERY_MS = 5_000L
        const val END_RESTART_MARGIN_MS = 5_000L
    }
}