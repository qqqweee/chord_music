package com.example.chord.playback

import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.example.chord.data.model.Song
import com.example.chord.data.repository.MusicRepository
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

@UnstableApi
class PlaybackService : MediaSessionService() {

    private lateinit var player: ExoPlayer
    private lateinit var mediaSession: MediaSession
    private lateinit var queueManager: PlaybackQueueManager

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()

        val mediaSourceFactory = createCachingAndResolvingMediaSourceFactory()

        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(mediaSourceFactory)
            .build()
            .apply {
                addListener(object : Player.Listener {
                    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                        queueManager.maybeLoadMore()
                    }
                })
            }

        queueManager = PlaybackQueueManager(
            player = player,
            repository = MusicRepository(),
            mediaSourceFactory = mediaSourceFactory,
            scope = serviceScope
        )

        mediaSession = MediaSession.Builder(this, player)
            .setCallback(SessionCallback())
            .build()
    }

    private fun createCachingAndResolvingMediaSourceFactory(): DefaultMediaSourceFactory {
        val baseDataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(USER_AGENT)

        val resolver = StreamResolver(AudioStreamExtractor(applicationContext))

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

        return DefaultMediaSourceFactory(cacheDataSourceFactory)
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
        ): ListenableFuture<SessionResult> {
            val song = Song(
                videoId = args.getString(EXTRA_VIDEO_ID).orEmpty(),
                title = args.getString(EXTRA_TITLE)
                    ?: player.currentMediaItem?.mediaMetadata?.title?.toString()
                    ?: "Unknown",
                details = args.getString(EXTRA_DETAILS)
                    ?: player.currentMediaItem?.mediaMetadata?.artist?.toString().orEmpty()
            )

            if (song.videoId.isBlank()) {
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_BAD_VALUE))
            }

            when (customCommand.customAction) {
                ACTION_PLAY_SONG -> queueManager.playSingleAndStartRadio(song)
                ACTION_START_RADIO -> queueManager.startRadioFromSeed(song)
                else -> return Futures.immediateFuture(
                    SessionResult(SessionResult.RESULT_ERROR_NOT_SUPPORTED)
                )
            }

            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }
    }

    override fun onDestroy() {
        queueManager.release()
        serviceScope.cancel()
        mediaSession.release()
        player.release()
        super.onDestroy()
    }

    companion object {
        const val ACTION_PLAY_SONG = "com.example.chord.action.PLAY_SONG"
        const val ACTION_START_RADIO = "com.example.chord.action.START_RADIO"
        const val EXTRA_VIDEO_ID = "video_id"
        const val EXTRA_TITLE = "title"
        const val EXTRA_DETAILS = "details"

        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
    }
}