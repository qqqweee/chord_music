@file:OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)

package com.example.chord.ui.home

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.chord.data.auth.AuthStore
import com.example.chord.data.home.HomeItem
import com.example.chord.ui.components.ShimmerBox
import com.example.chord.ui.components.pressScale
import com.example.chord.ui.theme.ChordTheme
import com.example.chord.ui.theme.ChordType
import com.example.chord.ui.theme.artGradient
import com.example.chord.ui.theme.rememberArtColors
import kotlinx.coroutines.flow.distinctUntilChanged

private const val TYPE_HEADER = -1
private const val TYPE_FOOTER = -2

/**
 * Home. Pieces live in their own files:
 *   HomeHeader.kt   greeting, search, account
 *   HomeShelves.kt  hero, speed dial, cards, quick picks
 *   HomeViewModel   history + YouTube shelves, refresh
 *   PlayTracker.kt  teaches Home what you play
 *
 * Pull down to refresh. The cover colour of the top pick tints the page.
 */
@Composable
fun HomeScreen(
    onPlaySong: (HomeItem) -> Unit,
    onOpenBrowse: (HomeItem) -> Unit,
    onSearch: () -> Unit = {},
    onLogin: () -> Unit = {},
    onSettings: () -> Unit = {},
    hasUpdates: Boolean = false,
    onUpdates: () -> Unit = {},
    modifier: Modifier = Modifier,
    nowPlayingId: String? = null,
    isPlaying: Boolean = false,
    recapReady: com.example.chord.data.stats.RecapPeriod? = null,
    onOpenRecap: (com.example.chord.data.stats.RecapPeriod) -> Unit = {},
    onDismissRecap: (com.example.chord.data.stats.RecapPeriod) -> Unit = {},
    viewModel: HomeViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsState()
    val signedIn by AuthStore.signedIn.collectAsState()
    val listState = rememberLazyListState()

    LaunchedEffect(viewModel) { viewModel.onShown() }
    // Ask once for a Last.fm key (it unlocks the Last.fm mixes). Settings has the same steps.
    val promptOn = !com.example.chord.data.settings.AppSettings.lastFmOn &&
        !com.example.chord.data.settings.AppSettings.lastFmPromptSeen && state.shelves.isNotEmpty()
    if (promptOn) LastFmPrompt(
        onSettings = { com.example.chord.data.settings.AppSettings.setLastFmPromptSeen(); onSettings() },
        onLater = com.example.chord.data.settings.AppSettings::setLastFmPromptSeen,
    )

    // Prefetch the next page while the last shelves are still ahead.
    LaunchedEffect(listState, viewModel) {
        snapshotFlow {
            val info = listState.layoutInfo
            // FIX: was 3. Start loading earlier so the append lands before you hit the end.
            (info.visibleItemsInfo.lastOrNull()?.index ?: 0) >= info.totalItemsCount - 5
        }
            .distinctUntilChanged()
            .collect { nearEnd -> if (nearEnd) viewModel.loadMore() }
    }

    val onItem = remember(onPlaySong, onOpenBrowse) {
        { item: HomeItem -> if (item.videoId != null) onPlaySong(item) else onOpenBrowse(item) }
    }

    // Page tint from the first cover. Covers the whole screen and stays put while you scroll.
    val tintOn = ChordTheme.coverTint
    val tint = rememberArtColors(if (tintOn) state.shelves.firstOrNull()?.items?.firstOrNull()?.thumbnailUrl else null)

    Box(modifier.fillMaxSize().background(Bg)) {
        if (tintOn) Box(
            // FIX: build the gradient once and reuse it. Before, a new Brush was made on every redraw.
            // Reading the tint inside the cache block rebuilds it only when the colours change.
            Modifier.fillMaxSize().drawWithCache {
                val brush = Brush.verticalGradient(
                    0f to tint.top.value,
                    0.5f to tint.mid.value,
                    1f to lerp(tint.mid.value, Bg, 0.55f),
                )
                onDrawBehind { drawRect(brush, alpha = 0.85f) }
            }
        )

        val mode = when {
            state.loading -> Mode.Loading
            state.error != null && state.shelves.isEmpty() -> Mode.Error
            else -> Mode.Content
        }
        Crossfade(targetState = mode, label = "homeMode", modifier = Modifier.fillMaxSize()) { m ->
            when (m) {
                Mode.Loading -> HomeSkeleton(signedIn, onSearch, onLogin, onSettings, hasUpdates, onUpdates)
                Mode.Error -> HomeError(state.error.orEmpty(), onRetry = viewModel::refresh)
                Mode.Content -> PullToRefreshBox(
                    isRefreshing = state.refreshing,
                    onRefresh = viewModel::refresh,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        state = listState,
                        contentPadding = PaddingValues(bottom = 220.dp),
                    ) {
                        item(key = "header", contentType = TYPE_HEADER) {
                            HomeHeader(signedIn, onSearch, onLogin, onSettings, hasUpdates, onUpdates)
                        }
                        if (recapReady != null) {
                            item(key = "recap-teaser-${recapReady.name}") {
                                RecapTeaser(recapReady, { onOpenRecap(recapReady) }, { onDismissRecap(recapReady) })
                            }
                        }
                        items(
                            items = state.shelves,
                            key = { it.stableKey() },
                            contentType = { it.style.ordinal + if (it.isList) 10 else 0 },
                        ) { shelf ->
                            Box(Modifier.animateItem(fadeInSpec = tween(360), placementSpec = tween(320), fadeOutSpec = tween(160))) {
                                Shelf(shelf, nowPlayingId, isPlaying, onItem, viewModel::refreshLastFm)
                            }
                        }
                        if (com.example.chord.data.settings.AppSettings.lastFmOn && state.shelves.none { it.title == "Last.fm" }) {
                            item(key = "lastfm-empty") { LastFmSectionHeader(viewModel::refreshLastFm) }
                        }
                        if (state.loadingMore || state.moreFailed) {
                            item(key = "footer", contentType = TYPE_FOOTER) {
                                Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                                    if (state.loadingMore) {
                                        CircularProgressIndicator(Modifier.size(26.dp), color = Color.White, strokeWidth = 2.dp)
                                    } else {
                                        Text(
                                            "Couldn't load more. Tap to retry",
                                            style = ChordType.CardSub, color = Dim,
                                            modifier = Modifier.clip(RoundedCornerShape(50)).clickable { viewModel.loadMore() }
                                                .padding(horizontal = 16.dp, vertical = 8.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private enum class Mode { Loading, Error, Content }

@Composable
private fun HomeError(message: String, onRetry: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Couldn't load Home", style = ChordType.Section, color = Color.White)
        Text(message, style = ChordType.CardSub, color = Dim, modifier = Modifier.padding(top = 6.dp))
        Spacer(Modifier.height(18.dp))
        Text(
            "Retry", color = Color.Black, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.clip(RoundedCornerShape(50)).background(Color.White)
                .pressScale(onClick = onRetry).padding(horizontal = 26.dp, vertical = 10.dp),
        )
    }
}

@Composable
private fun HomeSkeleton(signedIn: Boolean, onSearch: () -> Unit, onLogin: () -> Unit, onSettings: () -> Unit, hasUpdates: Boolean, onUpdates: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        HomeHeader(signedIn, onSearch, onLogin, onSettings, hasUpdates, onUpdates)
        ShimmerBox(
            Modifier.padding(horizontal = PageGutter).fillMaxWidth().aspectRatio(1.1f),
            RoundedCornerShape(26.dp),
        )
        Spacer(Modifier.height(26.dp))
        ShimmerBox(Modifier.padding(horizontal = PageGutter).size(160.dp, 22.dp))
        Spacer(Modifier.height(14.dp))
        Row(Modifier.padding(horizontal = PageGutter), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            repeat(3) { ShimmerBox(Modifier.size(120.dp), RoundedCornerShape(14.dp)) }
        }
    }
}

