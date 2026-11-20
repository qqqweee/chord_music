package com.example.chord

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import com.example.chord.ui.home.HomeViewModel
import com.example.chord.ui.player.PlayerScreen
import com.example.chord.ui.player.PlayerViewModel
import com.example.chord.ui.player.rememberPlayerExtras
import com.example.chord.ui.search.SearchScreen
import com.example.chord.ui.search.SearchViewModel
import com.example.chord.ui.theme.ChordTypography
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val container = AppContainer(applicationContext)

        val searchViewModel = ViewModelProvider(this, container.searchViewModelFactory())[SearchViewModel::class.java]
        val playerViewModel = ViewModelProvider(this, container.playerViewModelFactory())[PlayerViewModel::class.java]
        val homeViewModel = ViewModelProvider(this)[HomeViewModel::class.java]

        setContent {
            MaterialTheme(typography = ChordTypography) {
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
    val playbackState by playerViewModel.state.collectAsState()
    val availableLyrics by playerViewModel.availableLyrics.collectAsState()
    val isLoadingLyrics by playerViewModel.isLoadingLyrics.collectAsState()
    val selectedLyrics by playerViewModel.selectedLyrics.collectAsState()
    val extras = rememberPlayerExtras(playerViewModel)

    var destination by remember { mutableStateOf(Destination.Home) }
    var showFullPlayer by remember { mutableStateOf(false) }
    // Open playlist / album page. [lastDetail] keeps it drawn while it slides out.
    var detail by remember { mutableStateOf<HomeItem?>(null) }
    var lastDetail by remember { mutableStateOf<HomeItem?>(null) }
    if (detail != null) lastDetail = detail
    // Open artist page. [lastArtist] keeps it drawn while it slides out.
    var artist by remember { mutableStateOf<HomeItem?>(null) }
    var lastArtist by remember { mutableStateOf<HomeItem?>(null) }
    if (artist != null) lastArtist = artist

    val currentSong = playbackState.queue.getOrNull(playbackState.currentQueueIndex)
    val thumbnailUrl = currentSong?.thumbnailUrl
        ?: currentSong?.videoId?.let { "https://i.ytimg.com/vi/$it/hqdefault.jpg" }

    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val heightPx = with(density) { LocalConfiguration.current.screenHeightDp.dp.toPx() } * 1.06f
    val offsetY = remember { Animatable(heightPx) }

    fun dismissPlayer() = scope.launch {
        offsetY.animateTo(heightPx, tween(340, easing = CubicBezierEasing(0.4f, 0f, 0.9f, 0.6f)))
        showFullPlayer = false
    }

    LaunchedEffect(showFullPlayer) {
        if (showFullPlayer) offsetY.animateTo(0f, tween(480, easing = OpenEase))
    }

    BackHandler(enabled = showFullPlayer || detail != null || artist != null || destination != Destination.Home) {
        when {
            showFullPlayer -> dismissPlayer()
            detail != null -> detail = null
            artist != null -> artist = null
            else -> destination = Destination.Home
        }
    }

    // Read in a derivedStateOf so the whole app does not recompose on every drag frame.
    val sheetVisible by remember { derivedStateOf { showFullPlayer || offsetY.value < heightPx } }

    // One router for every tap on an item card or row.
    val openItem: (HomeItem) -> Unit = { item ->
        when {
            item.videoId != null -> playerViewModel.playHomeItem(item)
            item.type == HomeItemType.Artist && item.browseId != null -> artist = item
            item.type == HomeItemType.Artist -> { searchViewModel.submit(item.title); destination = Destination.Search }
            item.browseId != null && (item.type == HomeItemType.Album || item.type == HomeItemType.Playlist) -> detail = item
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

    CompositionLocalProvider(LocalOnSearchArtist provides searchArtist) {
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Box(
            Modifier.fillMaxSize().background(Color(0xFF0B0B0B)).graphicsLayer {
                val closed = (offsetY.value / heightPx).coerceIn(0f, 1f)
                val sc = 0.94f + (0.06f * closed)
                scaleX = sc
                scaleY = sc
                alpha = 0.55f + (0.45f * closed)
            }
        ) {
            Box(Modifier.fillMaxSize()) {
                when (destination) {
                    Destination.Home -> HomeScreen(
                        onPlaySong = { playerViewModel.playHomeItem(it) },
                        onOpenBrowse = openItem,
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
                        onPlaySong = { playerViewModel.playHomeItem(it) },
                        onSearchArtist = searchArtist,
                    )
                    Destination.Search -> SearchScreen(
                        searchViewModel = searchViewModel,
                        playbackState = playbackState,
                        onPlaySong = playerViewModel::play,
                        onOpenItem = openItem,
                    )
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
                    MiniPlayer(
                        title = playbackState.title,
                        details = currentSong?.details.orEmpty(),
                        thumbnailUrl = thumbnailUrl,
                        isPlaying = playbackState.isPlaying,
                        isLoading = playbackState.isLoading ||
                                playbackState.playbackState == Player.STATE_BUFFERING,
                        onTogglePlayPause = playerViewModel::togglePlayPause,
                        onOpenPlayer = { showFullPlayer = true },
                        onNext = playerViewModel::next,
                        onPrevious = playerViewModel::previous,
                        progress = if (playbackState.duration > 0L)
                            playbackState.position.toFloat() / playbackState.duration else 0f
                    )
                    Spacer(Modifier.height(8.dp))
                }
                BottomNavPill(
                    selected = destination.ordinal,
                    onSelect = { i ->
                        detail = null
                        artist = null
                        destination = Destination.values()[i]
                    },
                )
            }
        }

        // Global Full Player Sheet
        if (sheetVisible) {
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        translationY = offsetY.value
                        val c = 28f * density * (offsetY.value / 240f).coerceIn(0f, 1f)
                        shape = RoundedCornerShape(CornerSize(c), CornerSize(c), CornerSize(0f), CornerSize(0f))
                        clip = true
                    }
            ) {
                PlayerScreen(
                    playbackState = playbackState,
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
                    onSheetDrag = { dy -> scope.launch { offsetY.snapTo((offsetY.value + dy).coerceAtLeast(0f)) } },
                    onSheetDragEnd = { velocity ->
                        if (offsetY.value > heightPx * 0.22f || velocity > 1400f) dismissPlayer()
                        else scope.launch {
                            offsetY.animateTo(0f, spring(dampingRatio = 0.82f, stiffness = Spring.StiffnessMediumLow))
                        }
                    },
                )
            }
        }
    }
    }
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
