package com.example.chord

import com.example.chord.ui.theme.ChordTheme
import com.example.chord.data.auth.AuthStore
import com.example.chord.data.settings.AppSettings
import com.example.chord.ui.settings.SettingsScreen
import com.example.chord.data.auth.LoginScreen
import com.example.chord.ui.home.PlayTracker
import com.example.chord.ui.haptics.Haptic
import com.example.chord.ui.haptics.rememberHaptics
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.withContext
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.runtime.CompositionLocalProvider
import dev.chrisbanes.haze.hazeSource
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import dev.chrisbanes.haze.HazeState
import com.example.chord.ui.components.ChordMenuHost
import com.example.chord.ui.components.LocalMenuHaze
import com.example.chord.ui.components.LocalMenuHost
import com.example.chord.ui.components.MenuHostState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.Alignment
import com.example.chord.ui.artist.ArtistScreen
import com.example.chord.ui.components.BottomNavPill
import com.example.chord.ui.components.LocalOnSearchArtist
import com.example.chord.ui.explore.ExploreScreen
import com.example.chord.ui.library.LibraryScreen
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.media3.common.Player
import com.example.chord.di.AppContainer
import com.example.chord.ui.components.MiniPlayer
import com.example.chord.data.home.HomeItem
import com.example.chord.data.home.HomeItemType
import com.example.chord.ui.detail.DetailScreen
import com.example.chord.ui.home.HomeScreen
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import com.example.chord.data.auth.LoginScreen
import com.example.chord.ui.home.HomeViewModel
import com.example.chord.ui.player.PlayerScreen
import com.example.chord.ui.player.PlayerViewModel
import com.example.chord.ui.player.rememberPlayerExtras
import com.example.chord.ui.search.SearchScreen
import com.example.chord.ui.search.SearchViewModel
import com.example.chord.ui.theme.ChordTypography
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import kotlin.math.abs
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import com.example.chord.ui.player.playerSheetLayer
import com.example.chord.ui.player.rememberPlayerSheetState

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        AuthStore.init(applicationContext)
        AppSettings.init(applicationContext)
        com.example.chord.playback.DownloadStore.init(applicationContext)
        com.example.chord.data.explicit.ExplicitStore.init(applicationContext)
        com.example.chord.playback.StreamInfoStore.init(applicationContext)
        com.example.chord.playback.addon.AddonStore.init(applicationContext)
        com.example.chord.playback.addon.MonochromeResolver.init(applicationContext)
        com.example.chord.data.update.AppUpdater.init(applicationContext)
        val container = AppContainer(applicationContext)

        val searchViewModel = ViewModelProvider(this, container.searchViewModelFactory())[SearchViewModel::class.java]
        val playerViewModel = ViewModelProvider(this, container.playerViewModelFactory())[PlayerViewModel::class.java]
        val homeViewModel = ViewModelProvider(this)[HomeViewModel::class.java]

        // Check Explore once per 30 minutes on app launch, so the Home bell can
        // surface new releases even when the user has not opened the Explore tab.
        val exploreUpdates = com.example.chord.data.home.ExploreUpdatesStore.get(applicationContext)
        if (exploreUpdates.shouldCheck()) {
            lifecycleScope.launch {
                withContext(kotlinx.coroutines.Dispatchers.IO) {
                    com.example.chord.data.home.HomeRepository().explore()
                        .onSuccess { page -> exploreUpdates.compareAndSave(page.shelves) }
                }
            }
        }

        // Look for a newer Chord release on GitHub. Popup shows once per version.
        lifecycleScope.launch { com.example.chord.data.update.AppUpdater.check() }

        setContent {
            MaterialTheme(
                colorScheme = com.example.chord.ui.theme.chordColorScheme(),
                shapes = com.example.chord.ui.theme.ChordShapes,
                typography = ChordTypography,
            ) {
                ChordApp(searchViewModel, playerViewModel, homeViewModel)
            }
        }
    }
}

/** Order must match DefaultNavItems in BottomNav.kt. */
private enum class Destination { Home, Explore, Library, Search }
private val OpenEase = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)

@Composable
private fun ChordApp(
    searchViewModel: SearchViewModel,
    playerViewModel: PlayerViewModel,
    homeViewModel: HomeViewModel,
) {
    // Position ticks twice a second. Keep it OUT of this state so the whole app
    // does not recompose. Progress and the player sheet read it on their own.
    val coarse = remember(playerViewModel) {
        playerViewModel.state.map { it.copy(position = 0L) }.distinctUntilChanged()
    }
    val playbackState by coarse.collectAsState(initial = playerViewModel.state.value.copy(position = 0L))
    val availableLyrics by playerViewModel.availableLyrics.collectAsState()
    val isLoadingLyrics by playerViewModel.isLoadingLyrics.collectAsState()
    val selectedLyrics by playerViewModel.selectedLyrics.collectAsState()
    val progressState = remember(playerViewModel) {
        playerViewModel.state
            .map { if (it.duration > 0L) (it.position.toFloat() / it.duration * 200f).toInt() / 200f else 0f }
            .distinctUntilChanged()
    }.collectAsState(initial = 0f)

    var destination by remember { mutableStateOf(Destination.Home) }
    var showLogin by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showExploreUpdates by remember { mutableStateOf(false) }
    val updatesContext = androidx.compose.ui.platform.LocalContext.current
    val updatesStore = remember(updatesContext) { com.example.chord.data.home.ExploreUpdatesStore.get(updatesContext) }
    val exploreUpdates by updatesStore.updates.collectAsState()
    val hasUnreadExploreUpdates = exploreUpdates.any { !it.read }
    val appUpdate by com.example.chord.data.update.AppUpdater.available.collectAsState()
    val appUpdatePhase by com.example.chord.data.update.AppUpdater.phase.collectAsState()
    LaunchedEffect(showExploreUpdates) { if (showExploreUpdates) updatesStore.markAllRead() }
    // Open playlist / album page. [lastDetail] keeps it drawn while it slides out.
    var detail by remember { mutableStateOf<HomeItem?>(null) }
    var lastDetail by remember { mutableStateOf<HomeItem?>(null) }
    // Generated mix page (Daily Mix, Replay, Last.fm). Id is MixEngine's mix id.
    var mixId by remember { mutableStateOf<String?>(null) }
    var lastMix by remember { mutableStateOf<String?>(null) }
    if (mixId != null) lastMix = mixId
    if (detail != null) lastDetail = detail
    // Open artist page. [lastArtist] keeps it drawn while it slides out.
    var artist by remember { mutableStateOf<HomeItem?>(null) }
    var lastArtist by remember { mutableStateOf<HomeItem?>(null) }
    if (artist != null) lastArtist = artist

    val currentSong = playbackState.queue.getOrNull(playbackState.currentQueueIndex)
    // Song waiting in the "Add to playlist" sheet. null = sheet closed.
    var playlistSong by remember { mutableStateOf<com.example.chord.data.model.Song?>(null) }
    val extras = rememberPlayerExtras(playerViewModel).copy(
        onMore = { currentSong?.takeIf { it.videoId.isNotBlank() }?.let { playlistSong = it } },
    )
    // Quality line for the player: "Opus · 160 kbps · 48 kHz". "· Saved" when played from a download.
    val qualityLabels by com.example.chord.playback.StreamInfoStore.labels.collectAsState()
    val savedSongs by com.example.chord.playback.DownloadStore.songs.collectAsState()
    val audioInfo = currentSong?.videoId?.let { id ->
        val q = qualityLabels[id].orEmpty()
        val saved = savedSongs.any { it.videoId == id }
        listOf(q.ifBlank { null }, if (saved) "Saved" else null).filterNotNull().joinToString(" · ").ifBlank { null }
    }
    val busyIds by com.example.chord.playback.StreamInfoStore.busy.collectAsState()
    val upgrading = currentSong?.videoId in busyIds
    val thumbnailUrl = currentSong?.thumbnailUrl?.takeIf { it.isNotBlank() }
        ?: currentSong?.videoId?.takeIf { it.isNotBlank() }?.let { "https://i.ytimg.com/vi/$it/hqdefault.jpg" }

    // Teaches Home what you listen to.
    PlayTracker(currentSong?.videoId, currentSong?.title, currentSong?.details, thumbnailUrl, playbackState.isPlaying)
    com.example.chord.ui.stats.ListenTimeTracker(currentSong?.videoId, currentSong?.title, currentSong?.details, thumbnailUrl, playbackState.isPlaying)

    val scope = rememberCoroutineScope()
    val haptics = rememberHaptics()
    val density = LocalDensity.current
    // One number runs the player sheet: 0 = mini player, 1 = full player.
    val sheet = rememberPlayerSheetState()
    // Derived, so the whole app does not recompose on every drag frame.
    val sheetOpen by remember { derivedStateOf { sheet.progress > 0.001f } }

    fun dismissPlayer() {
        haptics.perform(Haptic.Dismiss)
        sheet.collapse()
    }

    BackHandler(enabled = sheetOpen || mixId != null || detail != null || artist != null || destination != Destination.Home) {
        when {
            sheetOpen -> dismissPlayer()
            mixId != null -> mixId = null
            detail != null -> detail = null
            artist != null -> artist = null
            else -> destination = Destination.Home
        }
    }

    // Same lambda objects every time, so Home and Library skip recomposition when playback state ticks.
    val playHome: (HomeItem) -> Unit = remember(playerViewModel) { { item: HomeItem -> playerViewModel.playHomeItem(item) } }
    val goSearch: () -> Unit = remember { { destination = Destination.Search } }
    val goLogin: () -> Unit = remember { { showLogin = true } }
    val goSettings: () -> Unit = remember { { showSettings = true } }
    var showSleep by remember { mutableStateOf(false) }
    var showOffset by remember { mutableStateOf(false) }
    var sleepJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    val sleepScope = androidx.compose.runtime.rememberCoroutineScope()
    val playingNow by androidx.compose.runtime.rememberUpdatedState(playbackState.isPlaying)
    val appContext = androidx.compose.ui.platform.LocalContext.current
    val songActions = remember(playerViewModel) {
        com.example.chord.ui.components.SongActions(
            playNext = { playerViewModel.enqueue(it, true) },
            addToQueue = { playerViewModel.enqueue(it, false) },
            sleepTimer = { showSleep = true },
            lyricsOffset = { showOffset = true },
            upgradeQuality = { song ->
                val toast = { m: String -> android.widget.Toast.makeText(appContext, m, android.widget.Toast.LENGTH_SHORT).show() }
                when {
                    com.example.chord.playback.DownloadStore.isDownloaded(song.videoId) -> toast("Remove the download, then download again")
                    else -> {
                        // Monochrome is always a source, so no addon is not a reason to stop.
                        com.example.chord.playback.StreamInfoStore.setForcedYoutube(song.videoId, false)
                        toast("Looking for better quality…")
                        // Service looks up the new link in the background, then swaps this song only.
                        playerViewModel.reload(song)
                    }
                }
            },
            useOpus = { song ->
                val toast = { m: String -> android.widget.Toast.makeText(appContext, m, android.widget.Toast.LENGTH_SHORT).show() }
                if (com.example.chord.playback.DownloadStore.isDownloaded(song.videoId)) {
                    toast("Remove the download first")
                } else {
                    com.example.chord.playback.StreamInfoStore.setForcedYoutube(song.videoId, true)
                    toast("Switching to Opus…")
                    playerViewModel.reload(song)
                }
            },
            startRadio = { playerViewModel.playHomeItem(HomeItem(it.title, it.details, it.thumbnailUrl.orEmpty(), it.videoId, null, com.example.chord.data.home.HomeItemType.Song)) },
        )
    }

    // One router for every tap on an item card or row.
    val openItem: (HomeItem) -> Unit = remember(playerViewModel, searchViewModel) { { item: HomeItem ->
        when {
            item.browseId?.startsWith("mix:") == true -> mixId = item.browseId!!.removePrefix("mix:")
            item.videoId != null -> playerViewModel.playHomeItem(item)
            item.type == HomeItemType.Artist && item.browseId != null -> artist = item
            item.type == HomeItemType.Artist -> { searchViewModel.submit(item.title); destination = Destination.Search }
            item.browseId != null && (item.type == HomeItemType.Album || item.type == HomeItemType.Playlist) -> detail = item
        }
    } }
    // Menu "Open artist": find the artist, open its page. If not found, fall back to search.
    val openArtistByName: (String) -> Unit = { name ->
        if (name.isNotBlank()) sleepScope.launch {
            val hit = com.example.chord.data.home.HomeRepository().search(name).getOrNull()
                ?.firstOrNull { it.type == HomeItemType.Artist && it.browseId != null }
            if (hit != null) { artist = hit; detail = null }
            else { searchViewModel.submit(name); destination = Destination.Search; artist = null; detail = null }
        }
    }
    val searchArtist: (String) -> Unit = { name ->
        if (name.isNotBlank()) {
            searchViewModel.submit(name)
            destination = Destination.Search
            artist = null
            detail = null
        }
    }

    if (showSleep) com.example.chord.ui.components.SleepTimerDialog(
        onPick = { m ->
            showSleep = false
            // Service owns the timer: it survives the app being closed.
            playerViewModel.setSleepTimer(m)
            if (m > 0) {
                android.widget.Toast.makeText(appContext, "Sleep in $m min", android.widget.Toast.LENGTH_SHORT).show()
            }
        },
        onDismiss = { showSleep = false },
    )
    if (showOffset) com.example.chord.ui.components.LyricsOffsetDialog { showOffset = false }

    // navHaze: glass of the mini player and nav pill. menuHaze: glass of popup menus, sees the whole app.
    val navHaze = remember { HazeState() }
    // navBackdrop: source for true liquid glass (lens). Used only when that switch is on and API 33+.
    val navBackdrop = rememberLayerBackdrop()
    val liquidGlass = com.example.chord.data.settings.AppSettings.glass &&
            com.example.chord.data.settings.AppSettings.trueGlass &&
            android.os.Build.VERSION.SDK_INT >= 33
    val menuHaze = remember { HazeState() }
    val menuHost = remember { MenuHostState() }

    CompositionLocalProvider(
        LocalMenuHost provides menuHost,
        LocalMenuHaze provides menuHaze,
        LocalOnSearchArtist provides openArtistByName,
        com.example.chord.ui.library.LocalAddToPlaylist provides { song -> playlistSong = song },
        com.example.chord.ui.components.LocalSongActions provides songActions,
    ) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            Box(Modifier.fillMaxSize().hazeSource(menuHaze)) {
                Box(
                    Modifier.fillMaxSize().background(ChordTheme.bg).graphicsLayer {
                        val closed = 1f - sheet.progress
                        val sc = 0.94f + (0.06f * closed)
                        scaleX = sc
                        scaleY = sc
                        alpha = 0.55f + (0.45f * closed)
                    }
                ) {
                    // Everything the glass nav blurs lives in this box.
                    Box(
                        Modifier.fillMaxSize().then(
                            when {
                                liquidGlass -> Modifier.layerBackdrop(navBackdrop)
                                com.example.chord.data.settings.AppSettings.glass -> Modifier.hazeSource(navHaze)
                                else -> Modifier
                            }
                        )
                    ) {
                        AnimatedContent(
                            targetState = destination,
                            transitionSpec = {
                                (fadeIn(tween(240, delayMillis = 70)) + scaleIn(tween(320, easing = OpenEase), initialScale = 0.97f)) togetherWith
                                        fadeOut(tween(110))
                            },
                            label = "destination",
                        ) { dest ->
                            when (dest) {
                                Destination.Home -> HomeScreen(
                                    onPlaySong = playHome,
                                    onOpenBrowse = openItem,
                                    onSearch = goSearch,
                                    onLogin = goLogin,
                                    onSettings = goSettings,
                                    hasUpdates = hasUnreadExploreUpdates,
                                    onUpdates = { showExploreUpdates = true },
                                    nowPlayingId = currentSong?.videoId,
                                    isPlaying = playbackState.isPlaying,
                                    viewModel = homeViewModel,
                                )
                                Destination.Explore -> ExploreScreen(
                                    nowPlayingId = currentSong?.videoId,
                                    isPlaying = playbackState.isPlaying,
                                    onItem = openItem,
                                )
                                Destination.Library -> LibraryScreen(
                                    nowPlayingId = currentSong?.videoId,
                                    isPlaying = playbackState.isPlaying,
                                    onPlaySong = playHome,
                                    onPlayQueue = playerViewModel::playQueue,
                                    onSearchArtist = searchArtist,
                                    onLogin = goLogin,
                                    onOpenBrowse = openItem,
                                )
                                Destination.Search -> SearchScreen(
                                    searchViewModel = searchViewModel,
                                    playbackState = playbackState,
                                    onPlaySong = playerViewModel::play,
                                    onOpenItem = openItem,
                                )
                            }
                        }

                        ArtistOverlay(
                            visible = artist != null,
                            item = lastArtist,
                            nowPlayingId = currentSong?.videoId,
                            isPlaying = playbackState.isPlaying,
                            onBack = { artist = null },
                            onOpenBrowse = openItem,
                            onPlayQueue = playerViewModel::playQueue,
                        )
                        com.example.chord.ui.mixes.MixOverlay(
                            visible = mixId != null,
                            id = lastMix,
                            nowPlayingId = currentSong?.videoId,
                            isPlaying = playbackState.isPlaying,
                            onBack = { mixId = null },
                            onPlayQueue = playerViewModel::playQueue,
                        )
                        DetailOverlay(
                            visible = detail != null,
                            item = lastDetail,
                            nowPlayingId = currentSong?.videoId,
                            isPlaying = playbackState.isPlaying,
                            onBack = { detail = null },
                            onPlayQueue = playerViewModel::playQueue,
                        )
                    }

                    // Floating bars: mini player above the nav pill. Lists leave 200dp at the bottom.
                    Column(
                        Modifier.align(Alignment.BottomCenter).navigationBarsPadding()
                            .padding(horizontal = 12.dp).padding(bottom = 8.dp)
                    ) {
                        if (playbackState.title.isNotBlank()) {
                            Box(
                                Modifier
                                    .onGloballyPositioned { c ->
                                        // Measure only while closed, so scale and drag do not skew it.
                                        if (!sheetOpen) {
                                            val y = c.positionInRoot().y
                                            if (abs(y - sheet.travelPx) > 0.5f) sheet.travelPx = y
                                            sheet.miniHeightPx = c.size.height.toFloat()
                                        }
                                    }
                                    .graphicsLayer { alpha = 1f - (sheet.progress / 0.3f).coerceIn(0f, 1f) }
                            ) {
                                MiniPlayer(
                                    title = playbackState.title,
                                    details = currentSong?.details.orEmpty(),
                                    thumbnailUrl = thumbnailUrl,
                                    isPlaying = playbackState.isPlaying,
                                    isLoading = playbackState.isLoading ||
                                            playbackState.playbackState == Player.STATE_BUFFERING,
                                    onTogglePlayPause = playerViewModel::togglePlayPause,
                                    onOpenPlayer = { sheet.expand() },
                                    onVerticalDrag = sheet::dragBy,
                                    // Open if pulled up a little or flicked.
                                    onVerticalDragEnd = { v -> sheet.settle(v, keepOpenAbove = 0.2f) },
                                    onNext = playerViewModel::next,
                                    onPrevious = playerViewModel::previous,
                                    progress = { progressState.value },
                                    hazeState = navHaze,
                                    backdrop = navBackdrop,
                                    // The cover flies to / from the big player, so hide it here while open.
                                    onCoverBounds = { r -> if (!sheetOpen) sheet.coverRect = r },
                                    coverVisible = !sheetOpen,
                                )
                            }
                            Spacer(Modifier.height(8.dp))
                        }
                        BottomNavPill(
                            hazeState = navHaze,
                            backdrop = navBackdrop,
                            selected = if (showSettings) 4 else destination.ordinal,
                            onSelect = { i ->
                                mixId = null
                                detail = null
                                artist = null
                                if (i == 4) showSettings = true
                                else { showSettings = false; destination = Destination.values()[i] }
                            },
                        )
                    }
                }

                if (showSettings) {
                    SettingsScreen(
                        onBack = { showSettings = false },
                        onLogin = goLogin,
                    )
                }
                if (showExploreUpdates) {
                    com.example.chord.ui.home.ExploreUpdatesDialog(
                        updates = exploreUpdates,
                        onDismiss = { showExploreUpdates = false },
                        onOpen = { update ->
                            showExploreUpdates = false
                            openItem(HomeItem(update.title, update.subtitle, update.thumbnailUrl,
                                update.videoId, update.browseId, update.type))
                        },
                    )
                }

                appUpdate?.let { release ->
                    com.example.chord.ui.update.UpdateDialog(
                        release = release,
                        phase = appUpdatePhase,
                        onUpdate = { com.example.chord.data.update.AppUpdater.startUpdate() },
                        onLater = { com.example.chord.data.update.AppUpdater.dismiss() },
                    )
                }

                // YouTube Music sign-in, above the bars.
                if (showLogin) {
                    LoginScreen(onDone = { showLogin = false }, onClose = { showLogin = false })
                }

                // Global Full Player Sheet
                if (sheetOpen) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .playerSheetLayer(
                                state = sheet,
                                miniInsetPx = with(density) { 12.dp.toPx() },  // same as bar side padding
                                miniRadiusPx = with(density) { 30.dp.toPx() }, // same as mini corner
                            )
                    ) {
                        // Full state (with position) is read only in here.
                        LivePlayback(playerViewModel) { livePlayback ->
                            PlayerScreen(
                                playbackState = livePlayback,
                                audioInfo = audioInfo,
                                isLossless = com.example.chord.playback.StreamInfoStore.isLosslessLabel(audioInfo),
                                upgrading = upgrading,
                                thumbnailUrl = thumbnailUrl,
                                syncedLyrics = selectedLyrics?.syncedLyrics,
                                plainLyrics = selectedLyrics?.plainLyrics,
                                isLoadingLyrics = isLoadingLyrics,
                                onTogglePlayPause = playerViewModel::togglePlayPause,
                                onBack = { dismissPlayer() },
                                onSeek = { positionMs -> playerViewModel.seekTo(positionMs) },
                                onPrevious = playerViewModel::previous,
                                onNext = playerViewModel::next,
                                onPlayQueueItem = playerViewModel::playQueueItem,
                                extras = extras,
                                sheetState = sheet,
                                onSheetDrag = sheet::dragBy,
                                onSheetDragEnd = { v ->
                                    // Close if pulled down a bit or flicked down.
                                    if (v > 700f || (v > -700f && sheet.progress < 0.75f)) haptics.perform(Haptic.Dismiss)
                                    sheet.settle(v, keepOpenAbove = 0.75f)
                                },
                            )
                        }
                    }
                }
                playlistSong?.let { song ->
                    com.example.chord.ui.library.AddToPlaylistSheet(song = song, onDismiss = { playlistSong = null })
                }
            }
            // Last, so it blurs everything above. Popup menus draw here.
            ChordMenuHost(menuHost, menuHaze)
        }
    }
}
/** Reads the live state (position ticks) in its own scope so ChordApp stays calm. */
@Composable
private fun LivePlayback(vm: PlayerViewModel, content: @Composable (com.example.chord.playback.PlaybackState) -> Unit) {
    val live by vm.state.collectAsState()
    content(live)
}

/**
 * Own function so AnimatedVisibility has no Column/Box scope around it.
 * Inside ChordApp's Column it hits the ColumnScope overload and fails to compile.
 */
@Composable
private fun DetailOverlay(
    visible: Boolean,
    item: HomeItem?,
    nowPlayingId: String?,
    isPlaying: Boolean,
    onBack: () -> Unit,
    onPlayQueue: (List<com.example.chord.data.model.Song>, Int) -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
        enter = slideInHorizontally(tween(320, easing = OpenEase)) { it / 3 } + fadeIn(tween(220)),
        exit = slideOutHorizontally(tween(260)) { it / 3 } + fadeOut(tween(200)),
    ) {
        item?.let {
            DetailScreen(
                item = it,
                nowPlayingId = nowPlayingId,
                isPlaying = isPlaying,
                onBack = onBack,
                onPlayQueue = onPlayQueue,
            )
        }
    }
}

@Composable
private fun ArtistOverlay(
    visible: Boolean,
    item: HomeItem?,
    nowPlayingId: String?,
    isPlaying: Boolean,
    onBack: () -> Unit,
    onOpenBrowse: (HomeItem) -> Unit,
    onPlayQueue: (List<com.example.chord.data.model.Song>, Int) -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
        enter = slideInHorizontally(tween(320, easing = OpenEase)) { it / 3 } + fadeIn(tween(220)),
        exit = slideOutHorizontally(tween(260)) { it / 3 } + fadeOut(tween(200)),
    ) {
        item?.let {
            ArtistScreen(
                item = it,
                nowPlayingId = nowPlayingId,
                isPlaying = isPlaying,
                onBack = onBack,
                onOpenBrowse = onOpenBrowse,
                onPlayQueue = onPlayQueue,
            )
        }
    }
}