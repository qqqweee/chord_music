package com.example.chord.playback

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import com.example.chord.MainActivity
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.example.chord.data.model.Song
import com.example.chord.data.repository.MusicRepository
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import androidx.media3.session.SessionError
import com.example.chord.playback.addon.AddonResolver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext
import com.example.chord.data.history.HistoryStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@UnstableApi
class PlaybackService : MediaSessionService() {

    private lateinit var player: ExoPlayer
    private lateinit var auxPlayer: ExoPlayer
    private lateinit var crossfade: CrossfadeEngine
    private var appliedDeviceSpatial: Boolean? = null
    private lateinit var mediaSession: MediaSession
    private lateinit var queueManager: PlaybackQueueManager
    private lateinit var recovery: PlaybackRecovery

    // A thrown error in any launched job is logged, never allowed to kill the app.
    private val serviceScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Main.immediate +
                CoroutineExceptionHandler { _, e -> android.util.Log.e("ChordService", "Uncaught in service scope", e) }
    )
    private var countedId: String? = null
    private var bufferStartedAt = 0L

    private val extractor by lazy { AudioStreamExtractor(applicationContext) }
    private var prefetchJob: Job? = null
    private var prefetchKey: String? = null

    /**
     * Get the next song ready while the current one plays, so skip and song end do not wait
     * for search + link lookup on a slow link.
     * Find the stream link for the NEXT song only (i + 1; in shuffle, the real next one).
     */
    private fun schedulePrefetch() {
        val cur = player.currentMediaItem?.mediaId ?: return
        // Only the next track. (Before: i + 1 and i + 2, two extra requests on the same server.)
        val candidates = if (player.shuffleModeEnabled) {
            listOf(player.nextMediaItemIndex)
        } else {
            listOf(player.currentMediaItemIndex + 1)
        }
        val nextIndexes = candidates.filter { it in 0 until player.mediaItemCount }
        val items = nextIndexes.map { player.getMediaItemAt(it) }
        val key = cur + ">" + items.joinToString(",") { it.mediaId }
        if (key == prefetchKey) return
        prefetchKey = key

        prefetchJob?.cancel()
        prefetchJob = serviceScope.launch {
            // Current song gets the network first.
            var waited = 0L
            while (player.playbackState == Player.STATE_BUFFERING && waited < 30_000) { delay(300); waited += 300 }

            for (item in items) {
                val uri = item.localConfiguration?.uri ?: continue
                if (uri.scheme != "chord") continue
                withContext(Dispatchers.IO) {
                    StreamResolver.prefetch(
                        extractor,
                        item.mediaId,
                        uri.getQueryParameter("t").orEmpty(),
                        uri.getQueryParameter("a").orEmpty(),
                    )
                }
            }
        }
    }

    /**
     * Person asked for better quality (or Opus) on a song. Resolve the new link first while the
     * old one keeps playing, then swap only that item at the same spot. Queue stays.
     */
    private var sleepJob: Job? = null

    /** [minutes] <= 0 cancels. Else pause playback when the time is up. */
    private fun setSleepTimer(minutes: Int) {
        sleepJob?.cancel()
        sleepJob = null
        if (minutes <= 0) return
        sleepJob = serviceScope.launch {
            delay(minutes * 60_000L)
            player.pause()
        }
    }

    private fun reloadStream(song: Song) {
        val item = player.currentMediaItem
        if (item == null || item.mediaId != song.videoId) {
            // Not the playing song: old behaviour, play it fresh.
            serviceScope.launch {
                withContext(Dispatchers.IO) { AddonResolver.evictCache(applicationContext, song) }
                queueManager.playSingleAndStartRadio(song)
            }
            return
        }
        val cur = Song(
            title = item.mediaMetadata.title?.toString().orEmpty(),
            details = item.mediaMetadata.artist?.toString().orEmpty(),
            videoId = item.mediaId,
            thumbnailUrl = item.mediaMetadata.artworkUri?.toString(),
        )
        serviceScope.launch {
            withContext(Dispatchers.IO) {
                // Upgrade must beat every remembered choice: chosen source, YouTube link,
                // Monochrome "no match" / old link.
                StreamResolver.forget(cur.videoId)
                com.example.chord.playback.addon.MonochromeResolver.forget(cur)
                com.example.chord.playback.addon.MonochromeResolver.forgetSavedId(cur)
                AddonResolver.forgetMemo(cur)
                val uri = android.net.Uri.parse(StreamResolver.createDummyUri(cur))
                // Old cached copy must go BEFORE the lookup, or the "fully cached" shortcut hides the upgrade.
                AudioCacheManager.evict(cur.videoId)
                StreamInfoStore.setLastCacheKey(cur.videoId, null)
                StreamResolver.prefetch(
                    extractor, cur.videoId,
                    uri.getQueryParameter("t").orEmpty(),
                    uri.getQueryParameter("a").orEmpty(),
                )
                AddonResolver.evictDisk(applicationContext, cur)
                // Upgrade asked: drop every cached copy of this song (any provider). Keys are per
                // provider, so nothing could glue, but the old copy would be picked up again.
                AudioCacheManager.evict(cur.videoId)
                StreamInfoStore.setLastCacheKey(cur.videoId, null)
            }
            queueManager.swapCurrent(cur)
        }
    }

    override fun onCreate() {
        super.onCreate()
        DownloadStore.init(applicationContext)
        com.example.chord.data.settings.AppSettings.init(applicationContext)
        StreamInfoStore.init(applicationContext)

        val mediaSourceFactory = createCachingAndResolvingMediaSourceFactory()

        player = buildPlayer(mediaSourceFactory)
            .apply {
                addListener(object : Player.Listener {
                    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                        countedId = null
                        if (::queueManager.isInitialized) queueManager.maybeLoadMore()
                        schedulePrefetch()
                    }

                    // Queue filled or changed: the "next" song may be a new one.
                    override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
                        schedulePrefetch()
                    }

                    // Queue ran dry before radio answered: fetch more and carry on.
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        // Timing log: adb logcat -s ChordBuffer
                        val now = android.os.SystemClock.elapsedRealtime()
                        if (playbackState == Player.STATE_BUFFERING) bufferStartedAt = now
                        else if (bufferStartedAt != 0L) {
                            android.util.Log.i("ChordBuffer", "buffering ended after ${now - bufferStartedAt} ms -> state $playbackState, item ${currentMediaItem?.mediaId}")
                            bufferStartedAt = 0L
                        }
                        if (playbackState == Player.STATE_ENDED && ::queueManager.isInitialized) queueManager.onPlaybackEnded()
                    }

                    // Log every play/pause flip and who caused it: adb logcat -s ChordPlay
                    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                        val why = when (reason) {
                            Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST -> "USER_REQUEST"
                            Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS -> "AUDIO_FOCUS_LOSS"
                            Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY -> "AUDIO_BECOMING_NOISY"
                            Player.PLAY_WHEN_READY_CHANGE_REASON_REMOTE -> "REMOTE"
                            Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM -> "END_OF_MEDIA_ITEM"
                            else -> "reason=$reason"
                        }
                        android.util.Log.i("ChordPlay", "playWhenReady=$playWhenReady ($why) state=$playbackState item=${currentMediaItem?.mediaId}")
                    }
                })
            }

        queueManager = PlaybackQueueManager(
            player = player,
            repository = MusicRepository(applicationContext),
            scope = serviceScope
        )

        // Stream died mid-song: retry same spot with a fresh link, else skip. Never rebuild the queue.
        recovery = PlaybackRecovery(player, serviceScope, isOnline = ::isOnline)
        player.addListener(recovery)
        // Quality label comes from what the player really opened, not from what a resolver claimed.
        player.addListener(PlaybackQualityReporter(player))

        // Tap on the notification / lock screen player opens the app.
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                action = Intent.ACTION_MAIN
                addCategory(Intent.CATEGORY_LAUNCHER)
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        mediaSession = MediaSession.Builder(this, player)
            .setCallback(SessionCallback())
            .setSessionActivity(openApp)
            .build()

        // Crossfade: real overlap. A helper player plays the next song while this one fades out.
        // Same loop also applies the "device spatial audio" switch live.
        auxPlayer = buildPlayer(createCachingAndResolvingMediaSourceFactory(), main = false)
        crossfade = CrossfadeEngine(player, auxPlayer) { com.example.chord.data.settings.AppSettings.crossfadeSec }
        serviceScope.launch {
            while (true) {
                // 50 ms only while a fade can happen. Otherwise sleep: no battery burn when idle or paused.
                val fading = com.example.chord.data.settings.AppSettings.crossfadeSec > 0 && player.isPlaying
                delay(if (fading) 50L else 500L)
                // One bad tick must not end the loop for good.
                try {
                    crossfade.tick()
                    applyDeviceSpatial(com.example.chord.data.settings.AppSettings.deviceSpatial)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    android.util.Log.w("ChordService", "crossfade tick failed: ${e.message}")
                }
            }
        }

        val history = HistoryStore.get(applicationContext)
        serviceScope.launch {
            while (true) {
                delay(2_000)
                val item = player.currentMediaItem ?: continue
                if (!player.isPlaying || item.mediaId == countedId) continue
                val d = player.duration
                val need = if (d > 0) minOf(30_000L, d / 2) else 30_000L
                if (player.currentPosition < need) continue
                countedId = item.mediaId
                try {
                    history.record(
                        item.mediaId,
                        item.mediaMetadata.title?.toString().orEmpty(),
                        item.mediaMetadata.artist?.toString().orEmpty(),
                        item.mediaMetadata.artworkUri?.toString(),
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    android.util.Log.w("ChordService", "history record failed: ${e.message}")
                }
            }
        }
        com.example.chord.data.lastfm.Scrobbler.attach(applicationContext, player, serviceScope)
    }

    /**
     * Player with our spatial DSP in the audio path.
     * Main player owns audio focus. The helper (crossfade) must not fight it for focus.
     */
    private fun buildPlayer(factory: DefaultMediaSourceFactory, main: Boolean = true): ExoPlayer {
        val spatial = SpatialAudioProcessor()
        val renderers = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(
                context: android.content.Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean,
            ): AudioSink = DefaultAudioSink.Builder(context)
                // 24-bit FLAC as packed PCM plays silent on many phones. Float is safe everywhere.
                // Only hi-res PCM turns into float. 16-bit songs stay 16-bit (spatial DSP still works).
                .setEnableFloatOutput(true)
                .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                .setAudioProcessorChain(DefaultAudioSink.DefaultAudioProcessorChain(spatial))
                .build()
        }
        // Time decides, not bytes. A 24-bit FLAC is 3-9 Mbps (0.4-1.1 MB/s): with a byte cap in
        // charge, a small phone stopped loading at ~16 MB, which is only 15-40 s, below the
        // minimum, and the song stalled. Now loading goes on until MIN_BUFFER seconds are held,
        // whatever the bitrate (Opus needs ~0.6 MB for that, 24/96 FLAC ~25 MB). The byte target is
        // only a ceiling for the part above the minimum, scaled to this phone's heap.
        val heapMb = (getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager).memoryClass
        val mainBytes = (heapMb / 4).coerceIn(32, 96) * 1024 * 1024
        // Helper player (crossfade) only plays the last seconds of a song: tiny buffer, so two
        // players never hold two big buffers at once.
        val loadControl = if (main) {
            DefaultLoadControl.Builder()
                .setBufferDurationsMs(
                    /* minBufferMs = */ LOAD_CONTROL_MIN_BUFFER_MS,
                    /* maxBufferMs = */ LOAD_CONTROL_MAX_BUFFER_MS,
                    /* bufferForPlaybackMs = */ LOAD_CONTROL_START_MS,
                    /* bufferForPlaybackAfterRebufferMs = */ LOAD_CONTROL_REBUFFER_MS,
                )
                .setTargetBufferBytes(mainBytes)
                .setPrioritizeTimeOverSizeThresholds(true)
                .build()
        } else {
            DefaultLoadControl.Builder()
                .setBufferDurationsMs(5_000, 12_000, 1_500, 3_000)
                .setTargetBufferBytes(8 * 1024 * 1024)
                .setPrioritizeTimeOverSizeThresholds(true)
                .build()
        }
        return ExoPlayer.Builder(this, renderers)
            .apply {
                setLoadControl(loadControl)
                if (main) {
                    // Gapless: open the next song's source (and start its first bytes) while this
                    // one plays. 5 s of the next song, so the switch has audio ready.
                    // Screen off + streaming: keep CPU and Wi-Fi awake, else playback dies after a while.
                    // Needs <uses-permission android:name="android.permission.WAKE_LOCK"/> in the manifest.
                    if (checkSelfPermission(android.Manifest.permission.WAKE_LOCK) ==
                        android.content.pm.PackageManager.PERMISSION_GRANTED
                    ) setWakeMode(C.WAKE_MODE_NETWORK)
                }
            }
            .setMediaSourceFactory(factory)
            .setAudioAttributes(musicAttributes(com.example.chord.data.settings.AppSettings.deviceSpatial), main)
            .setHandleAudioBecomingNoisy(main)
            .build()
            .also {
                if (main) it.setPreloadConfiguration(ExoPlayer.PreloadConfiguration(5_000_000L))
            }
    }

    private fun musicAttributes(deviceSpatial: Boolean) = androidx.media3.common.AudioAttributes.Builder()
        .setUsage(C.USAGE_MEDIA)
        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
        .setSpatializationBehavior(
            if (deviceSpatial) C.SPATIALIZATION_BEHAVIOR_AUTO else C.SPATIALIZATION_BEHAVIOR_NEVER
        )
        .build()

    /** Lets the phone's own spatializer (Dolby Atmos on supported phones) handle the audio, or not. */
    private fun applyDeviceSpatial(on: Boolean) {
        if (appliedDeviceSpatial == on) return
        if (appliedDeviceSpatial == null) { appliedDeviceSpatial = on; return }   // built with it already
        appliedDeviceSpatial = on
        val attrs = musicAttributes(on)
        player.setAudioAttributes(attrs, true)
        auxPlayer.setAudioAttributes(attrs, false)
    }

    private fun createCachingAndResolvingMediaSourceFactory(): DefaultMediaSourceFactory {
        com.example.chord.playback.addon.AddonStore.init(applicationContext)
        com.example.chord.playback.addon.MonochromeResolver.init(applicationContext)
        // One data path for every player: resolve -> disk cache -> shared OkHttp client.
        return PlaybackDataSources.mediaSourceFactory(applicationContext, extractor)
    }

    /** Phone has a network. Unknown (no permission) counts as online, so recovery still works. */
    private fun isOnline(): Boolean = runCatching {
        val cm = getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return false)
        caps?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }.getOrDefault(true)

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession = mediaSession

    private inner class SessionCallback : MediaSession.Callback {
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo
        ): MediaSession.ConnectionResult {
            val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS
                .buildUpon()
                .add(SessionCommand(ACTION_PLAY_SONG, Bundle.EMPTY))
                .add(SessionCommand(ACTION_START_RADIO, Bundle.EMPTY))
                .add(SessionCommand(ACTION_PLAY_QUEUE, Bundle.EMPTY))
                .add(SessionCommand(ACTION_ENQUEUE, Bundle.EMPTY))
                .add(SessionCommand(ACTION_RELOAD, Bundle.EMPTY))
                .add(SessionCommand(ACTION_SLEEP, Bundle.EMPTY))
                .build()

            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(commands)
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle
        ): ListenableFuture<SessionResult> = try {
            handleCustomCommand(customCommand, args)
        } catch (e: Exception) {
            android.util.Log.e("ChordService", "custom command failed: ${customCommand.customAction}", e)
            Futures.immediateFuture(SessionResult(SessionError.ERROR_UNKNOWN))
        }

        private fun handleCustomCommand(
            customCommand: SessionCommand,
            args: Bundle
        ): ListenableFuture<SessionResult> {

            // Sleep timer lives here, so it still works after the app is swiped away.
            if (customCommand.customAction == ACTION_SLEEP) {
                setSleepTimer(args.getInt(EXTRA_MINUTES, 0))
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }

            // Whole playlist / album: queue it and start at the tapped track. No radio.
            if (customCommand.customAction == ACTION_PLAY_QUEUE) {
                val ids = args.getStringArray(EXTRA_VIDEO_IDS) ?: emptyArray()
                val titles = args.getStringArray(EXTRA_TITLES) ?: emptyArray()
                val details = args.getStringArray(EXTRA_DETAILS_LIST) ?: emptyArray()
                val thumbs = args.getStringArray(EXTRA_THUMBNAILS) ?: emptyArray()
                val songs = ids.mapIndexed { i, id ->
                    Song(
                        title = titles.getOrNull(i).orEmpty().ifBlank { "Unknown" },
                        details = details.getOrNull(i).orEmpty(),
                        videoId = id,
                        thumbnailUrl = thumbs.getOrNull(i)?.takeIf { it.isNotBlank() },
                    )
                }
                if (songs.isEmpty() || songs.any { it.videoId.isBlank() }) {
                    return Futures.immediateFuture(SessionResult(SessionError.ERROR_BAD_VALUE))
                }
                queueManager.playQueue(songs, args.getInt(EXTRA_START_INDEX, 0))
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }

            // Fall back to the playing item only if it is the SAME song.
            // Else song B would inherit song A's title / artwork.
            val videoId = args.getString(EXTRA_VIDEO_ID).orEmpty()
            val playing = player.currentMediaItem?.takeIf { it.mediaId == videoId }
            val song = Song(
                videoId = videoId,
                title = args.getString(EXTRA_TITLE)
                    ?: playing?.mediaMetadata?.title?.toString()
                    ?: "Unknown",
                details = args.getString(EXTRA_DETAILS)
                    ?: playing?.mediaMetadata?.artist?.toString().orEmpty(),
                thumbnailUrl = args.getString(EXTRA_THUMBNAIL_URL)?.takeIf { it.isNotBlank() }
                    ?: playing?.mediaMetadata?.artworkUri?.toString()
                    ?: videoId.takeIf { it.isNotBlank() }
                        ?.let { "https://i.ytimg.com/vi/$it/hqdefault.jpg" }
            )

            if (song.videoId.isBlank()) {
                return Futures.immediateFuture(SessionResult(SessionError.ERROR_BAD_VALUE))
            }

            when (customCommand.customAction) {
                ACTION_PLAY_SONG -> queueManager.playSingleAndStartRadio(song)
                ACTION_START_RADIO -> queueManager.startRadioFromSeed(song)
                ACTION_ENQUEUE -> queueManager.enqueue(song, args.getBoolean(EXTRA_NEXT, false))
                ACTION_RELOAD -> reloadStream(song)
                else -> return Futures.immediateFuture(
                    SessionResult(SessionError.ERROR_NOT_SUPPORTED)
                )
            }

            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }
    }

    /** App swiped away: if nothing is playing, stop the service. If playing, music goes on. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!::player.isInitialized || !player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        // Each step is guarded: one failure must not skip the rest (leaked player = stuck audio).
        runCatching { if (::recovery.isInitialized) recovery.release() }
        runCatching { com.example.chord.data.lastfm.Scrobbler.detach() }
        runCatching { if (::queueManager.isInitialized) queueManager.release() }
        runCatching { serviceScope.cancel() }
        runCatching { if (::crossfade.isInitialized) crossfade.release() }
        runCatching { if (::mediaSession.isInitialized) mediaSession.release() }
        runCatching { if (::player.isInitialized) player.release() }
        super.onDestroy()
    }

    companion object {
        const val ACTION_PLAY_SONG = "com.example.chord.action.PLAY_SONG"
        const val ACTION_START_RADIO = "com.example.chord.action.START_RADIO"
        const val ACTION_ENQUEUE = "com.example.chord.action.ENQUEUE"
        const val ACTION_RELOAD = "com.example.chord.action.RELOAD"
        const val ACTION_SLEEP = "com.example.chord.action.SLEEP"
        const val EXTRA_MINUTES = "minutes"

        // ===================== TUNABLES (safe to change) =====================
        /** TUNABLE (main player): keep loading until this much audio is buffered. */
        private const val LOAD_CONTROL_MIN_BUFFER_MS = 30_000
        /** TUNABLE (main player): stop loading at this much audio (byte cap may stop sooner). */
        private const val LOAD_CONTROL_MAX_BUFFER_MS = 90_000
        /** TUNABLE (main player): audio needed before a song STARTS. Lower = faster start, more risk of a stall. */
        private const val LOAD_CONTROL_START_MS = 5_000
        /** TUNABLE (main player): audio needed to RESUME after a stall. */
        private const val LOAD_CONTROL_REBUFFER_MS = 12_000
        // ====================================================================
        const val EXTRA_NEXT = "enqueue_next"
        const val EXTRA_VIDEO_ID = "video_id"
        const val EXTRA_TITLE = "title"
        const val EXTRA_DETAILS = "details"

        const val ACTION_PLAY_QUEUE = "com.example.chord.action.PLAY_QUEUE"
        const val EXTRA_VIDEO_IDS = "video_ids"
        const val EXTRA_TITLES = "titles"
        const val EXTRA_DETAILS_LIST = "details_list"
        const val EXTRA_THUMBNAILS = "thumbnails"
        const val EXTRA_START_INDEX = "start_index"

        // ---> Added this extra key here <---
        const val EXTRA_THUMBNAIL_URL = "thumbnail_url"
    }
}