package com.example.chord.playback

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import com.example.chord.MainActivity
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.CacheDataSource
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
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.session.SessionError
import com.example.chord.playback.addon.AddonResolver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
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

    private val extractor by lazy { AudioStreamExtractor(applicationContext) }
    private var warmFactory: CacheDataSource.Factory? = null
    private var prefetchJob: Job? = null
    private var prefetchKey: String? = null

    /**
     * Get the next songs ready while the current one plays, so skip and song end do not wait
     * for search + link lookup on a slow link.
     *   1. Find the stream link for the next 1-2 songs (small requests).
     *   2. When the current song has a cushion, save the first ~0.75 MB of the next one to disk,
     *      so it starts at once.
     */
    private fun schedulePrefetch() {
        val cur = player.currentMediaItem?.mediaId ?: return
        val candidates = if (player.shuffleModeEnabled) {
            listOf(player.nextMediaItemIndex)
        } else {
            val i = player.currentMediaItemIndex
            listOf(i + 1, i + 2)
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

            for ((n, item) in items.withIndex()) {
                val uri = item.localConfiguration?.uri ?: continue
                if (uri.scheme != "chord") continue
                val ready = withContext(Dispatchers.IO) {
                    StreamResolver.prefetch(
                        extractor,
                        item.mediaId,
                        uri.getQueryParameter("t").orEmpty(),
                        uri.getQueryParameter("a").orEmpty(),
                    )
                }
                if (n == 0 && ready) warmCache(uri)
            }
        }
    }

    @Suppress("UNUSED_PARAMETER")
    private suspend fun warmCache(uri: android.net.Uri) {
        val factory = warmFactory ?: return
        val key = uri.toString()
        if (runCatching { AudioCacheManager.getInstance(applicationContext).isCached(key, 0, WARM_BYTES) }.getOrDefault(false)) return

        // Only when the current song has a cushion. Warm-up must never starve what is playing.
        var waited = 0L
        while (player.totalBufferedDuration < 10_000 && waited < 90_000) { delay(1_000); waited += 1_000 }
        if (player.totalBufferedDuration < 10_000) return

        withContext(Dispatchers.IO) {
            val writer = CacheWriter(
                factory.createDataSource(),
                DataSpec.Builder().setUri(uri).setPosition(0).setLength(WARM_BYTES).build(),
                null,
                null,
            )
            val handle = currentCoroutineContext()[Job]?.invokeOnCompletion { if (it != null) writer.cancel() }
            try {
                writer.cache()
            } catch (e: Exception) {
                android.util.Log.w("ChordPrefetch", "warm-up stopped: ${e.message}")
            } finally {
                handle?.dispose()
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
                AddonResolver.forgetMemo(cur)
                val uri = android.net.Uri.parse(StreamResolver.createDummyUri(cur))
                StreamResolver.prefetch(
                    extractor, cur.videoId,
                    uri.getQueryParameter("t").orEmpty(),
                    uri.getQueryParameter("a").orEmpty(),
                )
                AddonResolver.evictDisk(applicationContext, cur)
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
                        if (playbackState == Player.STATE_ENDED && ::queueManager.isInitialized) queueManager.onPlaybackEnded()
                    }
                })
            }

        queueManager = PlaybackQueueManager(
            player = player,
            repository = MusicRepository(applicationContext),
            mediaSourceFactory = mediaSourceFactory,
            scope = serviceScope
        )

        // Stream died mid-song: retry same spot with a fresh link, else skip. Never rebuild the queue.
        recovery = PlaybackRecovery(player, serviceScope)
        player.addListener(recovery)

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
        // Buffer follows the phone's memory. A 24-bit FLAC is 3-6 Mbps (~0.5-0.75 MB/s), so 24 MB
        // holds 30-45 s. Small phone (128 MB heap) gets 16 MB, big phone is capped at 48 MB.
        // Time limit is the same everywhere: hold 20-60 s, resume after a stall with 8 s cushion.
        val heapMb = (getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager).memoryClass
        val mainBytes = (heapMb / 8).coerceIn(16, 48) * 1024 * 1024
        // Helper player (crossfade) only plays the last seconds of a song: tiny buffer, so two
        // players never hold two big buffers at once.
        val loadControl = if (main) {
            DefaultLoadControl.Builder()
                .setBufferDurationsMs(
                    /* minBufferMs = */ 20_000,
                    /* maxBufferMs = */ 60_000,
                    /* bufferForPlaybackMs = */ 2_000,
                    /* bufferForPlaybackAfterRebufferMs = */ 8_000,
                )
                .setTargetBufferBytes(mainBytes)
                .setPrioritizeTimeOverSizeThresholds(false)
                .build()
        } else {
            DefaultLoadControl.Builder()
                .setBufferDurationsMs(5_000, 12_000, 1_500, 3_000)
                .setTargetBufferBytes(8 * 1024 * 1024)
                .setPrioritizeTimeOverSizeThresholds(false)
                .build()
        }
        return ExoPlayer.Builder(this, renderers)
            .apply {
                setLoadControl(loadControl)
                if (main) {
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
        // Default 8 s timeouts are too short for the Monochrome proxy on a cold track.
        val baseDataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(USER_AGENT)
            .setConnectTimeoutMs(10_000)
            .setReadTimeoutMs(15_000)   // dead socket: retry fast (Range resume is cheap) instead of 30 s of spinner
            .setAllowCrossProtocolRedirects(true)

        com.example.chord.playback.addon.AddonStore.init(applicationContext)
        com.example.chord.playback.addon.MonochromeResolver.init(applicationContext)
        val resolver = StreamResolver(extractor)

        // 1. First, create the Resolving DataSource to handle chord:// JIT extraction
        val resolvingDataSourceFactory = ResolvingDataSource.Factory(
            baseDataSourceFactory,
            resolver
        )

        // 2. Wrap it inside CacheDataSource.Factory so resolved streams get cached to disk
        val cache = AudioCacheManager.getInstance(applicationContext)
        val cacheDataSourceFactory = CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(resolvingDataSourceFactory)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        warmFactory = cacheDataSourceFactory

        // 3. Phone files and saved songs (file:// or content://) skip the cache: route by URI scheme.
        val local = androidx.media3.datasource.DefaultDataSource.Factory(applicationContext)
        return DefaultMediaSourceFactory(RoutingDataSourceFactory(local, cacheDataSourceFactory))
    }

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
        private const val WARM_BYTES = 768L * 1024
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

        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
    }
}