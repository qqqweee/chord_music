package com.example.chord.ui.detail

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.chord.data.explicit.ExplicitStore
import com.example.chord.data.home.HomeItem
import com.example.chord.data.home.HomeItemType
import com.example.chord.data.library.LibraryStore
import com.example.chord.data.model.Song
import com.example.chord.data.playlist.PlaylistHeader
import com.example.chord.data.playlist.PlaylistTrack
import com.example.chord.playback.DownloadStore
import com.example.chord.data.settings.AppSettings
import com.example.chord.ui.components.DownloadQualityDialog
import com.example.chord.ui.components.ChordMenu
import com.example.chord.ui.components.ChordMenuDivider
import com.example.chord.ui.components.ChordMenuItem
import com.example.chord.ui.components.CoverImage
import com.example.chord.ui.components.ExplicitMark
import com.example.chord.ui.components.LocalOnSearchArtist
import com.example.chord.ui.components.LocalSongActions
import com.example.chord.ui.components.NowPlayingOverlay
import com.example.chord.ui.components.ShimmerBox
import com.example.chord.ui.components.SongMenu
import com.example.chord.ui.components.pressScale
import com.example.chord.ui.library.LocalAddToPlaylist
import com.example.chord.ui.theme.ArtColors
import com.example.chord.ui.theme.ChordTheme
import com.example.chord.ui.theme.rememberArtColors
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import kotlinx.coroutines.launch

// Page follows the app theme (Dark / Black / Graphite). Not the grey sheet colour.
private val Page: Color get() = ChordTheme.bg
private val Dim = Color.White.copy(alpha = 0.62f)
private val Link = Color(0xFF8AB4F8)
private val Hairline = Color.White.copy(alpha = 0.12f)
private val Gutter = 16.dp
private val RowArt = 40.dp
private val RowArtShape = RoundedCornerShape(6.dp)
private val NumberCol = 28.dp
private val ControlGlass = Color(0x57000000)

// Compact sizes
private val BarHeight = 60.dp
private val ControlSize = 44.dp
private val ActionHeight = 42.dp

private enum class TrackSort(val label: String) {
    Default("Default order"), Title("Title"), Artist("Artist"), Length("Length")
}

/** Pixels the list is scrolled, or a huge number once the header is gone. */
private fun LazyListState.scrollPx(): Float =
    if (firstVisibleItemIndex == 0) firstVisibleItemScrollOffset.toFloat() else 100_000f

/**
 * Playlist / album page.
 *
 *   floating glass controls: back, and a pill with Search / Sort / More
 *   cover, title, artist, "Album · 2026 · 11 Tracks · 32 min"
 *   shuffle, Play, add to library
 *   About, then tracks. Each track has the full SongMenu (Haze glass).
 *
 * Tapping a track queues the whole shown list and starts there.
 */
@Composable
fun DetailScreen(
    item: HomeItem,
    nowPlayingId: String?,
    isPlaying: Boolean,
    onBack: () -> Unit,
    onPlayQueue: (List<Song>, Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val vm: DetailViewModel = viewModel(
        key = item.browseId ?: item.title,
        factory = remember(item) { DetailViewModel.Factory(item) },
    )
    val state by vm.state.collectAsState()
    val listState = rememberLazyListState()
    val scrollPx = remember(listState) { { listState.scrollPx() } }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val haze = remember { HazeState() }
    val actions = LocalSongActions.current

    val isAlbum = item.type == HomeItemType.Album
    val header = state.header
    val colors = rememberArtColors(header.thumbnailUrl)

    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var sort by remember { mutableStateOf(TrackSort.Default) }
    var askQuality by remember { mutableStateOf(false) }

    // What is on screen: filtered, then sorted. Play and Shuffle use this same list.
    val shown = remember(state.tracks, query, sort) {
        val q = query.trim()
        val filtered = if (q.isEmpty()) state.tracks else state.tracks.filter {
            it.title.contains(q, ignoreCase = true) || it.artist.contains(q, ignoreCase = true)
        }
        when (sort) {
            TrackSort.Default -> filtered
            TrackSort.Title -> filtered.sortedBy { it.title.lowercase() }
            TrackSort.Artist -> filtered.sortedBy { it.artist.lowercase() }
            TrackSort.Length -> filtered.sortedBy { seconds(it.duration) }
        }
    }
    val songs = remember(shown, header.thumbnailUrl) {
        shown.map { Song(it.title, it.artist, it.videoId, it.thumbnailUrl ?: header.thumbnailUrl) }
    }
    val allSongs = remember(state.tracks, header.thumbnailUrl) {
        state.tracks.map { Song(it.title, it.artist, it.videoId, it.thumbnailUrl ?: header.thumbnailUrl) }
    }
    val canPlay = songs.isNotEmpty()

    Box(
        modifier
            .fillMaxSize()
            .background(Page)
            // Swallow taps so nothing underneath (Home) reacts through the page.
            .pointerInput(Unit) { detectTapGestures { } },
    ) {
        Backdrop(colors, scrollPx)

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().hazeSource(haze),
            contentPadding = PaddingValues(bottom = 200.dp),
        ) {
            item(key = "header", contentType = "header") {
                HeaderBlock(
                    header = header,
                    isAlbum = isAlbum,
                    tracks = state.tracks,
                    songs = allSongs,
                    canPlay = state.tracks.isNotEmpty(),
                    scrollPx = scrollPx,
                    onPlay = { onPlayQueue(songs, 0) },
                    onShuffle = { onPlayQueue(songs.shuffled(), 0) },
                )
            }
            when {
                state.loading && state.tracks.isEmpty() ->
                    items(8, key = { "sk$it" }, contentType = { "skeleton" }) { TrackSkeleton(it) }

                state.error != null && state.tracks.isEmpty() ->
                    item(key = "error", contentType = "error") { ErrorBlock(state.error.orEmpty(), vm::retry) }

                shown.isEmpty() && state.tracks.isNotEmpty() ->
                    item(key = "none", contentType = "error") {
                        Text("No match for \"$query\"", color = Dim, fontSize = 15.sp, modifier = Modifier.padding(28.dp))
                    }

                else -> itemsIndexed(
                    items = shown,
                    key = { i, t -> t.videoId + "#" + i },
                    contentType = { _, _ -> "track" },
                ) { index, track ->
                    TrackRow(
                        track = track,
                        number = index + 1,
                        showNumber = isAlbum,
                        albumName = if (isAlbum) header.title else null,
                        fallbackThumb = header.thumbnailUrl,
                        playing = track.videoId == nowPlayingId,
                        isPlaying = isPlaying,
                        divider = index < shown.lastIndex,
                        onClick = { onPlayQueue(songs, index) },
                        onOpenAlbum = { scope.launch { listState.animateScrollToItem(0) } },
                    )
                }
            }
            if (state.loadingMore) {
                items(2, key = { "more$it" }, contentType = { "skeleton" }) { TrackSkeleton(it) }
            }
        }

        if (askQuality) DownloadQualityDialog(allSongs) { askQuality = false }
        TopControls(
            haze = haze,
            title = header.title,
            scrollPx = scrollPx,
            searching = searching,
            query = query,
            sort = sort,
            canPlay = canPlay,
            onBack = onBack,
            onQuery = { query = it },
            onSearching = { on -> searching = on; if (!on) query = "" },
            onSort = { sort = it },
            onPlayNext = { allSongs.asReversed().forEach { s -> actions?.playNext?.invoke(s) } },
            onAddToQueue = { allSongs.forEach { s -> actions?.addToQueue?.invoke(s) } },
            onDownloadAll = {
                if (AppSettings.askDownloadQuality) askQuality = true else DownloadStore.downloadAll(allSongs)
            },
            onShuffle = { onPlayQueue(songs.shuffled(), 0) },
            onShare = {
                val id = item.browseId.orEmpty()
                val url = when {
                    id.startsWith("VL") -> "https://music.youtube.com/playlist?list=${id.removePrefix("VL")}"
                    id.isNotBlank() -> "https://music.youtube.com/browse/$id"
                    else -> ""
                }
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, listOf(header.title, url).filter { it.isNotBlank() }.joinToString("\n"))
                }
                context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            },
        )
    }
}

/** Force any colour to a deep, rich tone. Grey or bright art can never wash the page out. */
private fun Color.deep(maxV: Float, minV: Float = 0.14f): Color {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(toArgb(), hsv)
    hsv[1] = hsv[1].coerceIn(0f, 0.85f)
    hsv[2] = hsv[2].coerceIn(minV, maxV)
    return Color(android.graphics.Color.HSVToColor(hsv))
}

/**
 * Cover colour flowing into the dark page, full screen.
 * Both ends are opaque, so there is no see-through blend and no grey fog.
 * Colours are forced dark, so even a grey or neon cover looks rich.
 */
@Composable
private fun Backdrop(colors: ArtColors, scrollPx: () -> Float) {
    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer { translationY = -scrollPx().coerceAtMost(900f) * 0.30f }
            .drawBehind {
                val top = colors.top.value.deep(maxV = 0.42f)
                val mid = colors.mid.value.deep(maxV = 0.30f)
                drawRect(
                    Brush.verticalGradient(
                        0.00f to top,
                        0.35f to mid,
                        0.70f to lerp(mid, Page, 0.70f),
                        1.00f to Page,
                    )
                )
            },
    )
}

// ---- Top controls -------------------------------------------------------------

@Composable
private fun TopControls(
    haze: HazeState,
    title: String,
    scrollPx: () -> Float,
    searching: Boolean,
    query: String,
    sort: TrackSort,
    canPlay: Boolean,
    onBack: () -> Unit,
    onQuery: (String) -> Unit,
    onSearching: (Boolean) -> Unit,
    onSort: (TrackSort) -> Unit,
    onPlayNext: () -> Unit,
    onAddToQueue: () -> Unit,
    onDownloadAll: () -> Unit,
    onShuffle: () -> Unit,
    onShare: () -> Unit,
) {
    val glass = remember {
        HazeStyle(backgroundColor = ControlGlass, tint = HazeTint(Color.Transparent), blurRadius = 24.dp, noiseFactor = 0.04f)
    }
    Box(Modifier.fillMaxWidth()) {
        // Full-width glass bar fades in once the big cover has scrolled away.
        Box(
            Modifier.matchParentSize()
                .graphicsLayer { alpha = ((scrollPx() - 230f) / 120f).coerceIn(0f, 1f) }
                .hazeEffect(haze, glass),
        )
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().height(BarHeight).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GlassCircle(haze, glass, onClick = if (searching) ({ onSearching(false) }) else onBack) {
                Icon(if (searching) Icons.Rounded.Close else Icons.AutoMirrored.Rounded.ArrowBack, if (searching) "Close search" else "Back", tint = Color.White)
            }
            if (searching) {
                val focus = remember { FocusRequester() }
                androidx.compose.runtime.LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
                Row(
                    Modifier.weight(1f).padding(start = 10.dp).height(ControlSize)
                        .clip(CircleShape).hazeEffect(haze, glass).border(0.7.dp, Hairline, CircleShape)
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.Search, null, tint = Dim, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                    Box(Modifier.weight(1f)) {
                        if (query.isEmpty()) Text("Search in this list", color = Dim, fontSize = 15.sp)
                        BasicTextField(
                            value = query, onValueChange = onQuery, singleLine = true,
                            textStyle = TextStyle(color = Color.White, fontSize = 15.sp),
                            cursorBrush = SolidColor(Color.White),
                            modifier = Modifier.fillMaxWidth().focusRequester(focus),
                        )
                    }
                }
            } else {
                Text(
                    title,
                    color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f).padding(horizontal = 12.dp)
                        .graphicsLayer { alpha = ((scrollPx() - 260f) / 90f).coerceIn(0f, 1f) },
                )
                val pill = RoundedCornerShape(50)
                Row(
                    Modifier.height(ControlSize).clip(pill).hazeEffect(haze, glass)
                        .border(0.7.dp, Hairline, pill),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PillIcon(Icons.Rounded.Search, "Search") { onSearching(true) }

                    var sortOpen by remember { mutableStateOf(false) }
                    Box {
                        PillIcon(Icons.AutoMirrored.Rounded.Sort, "Sort") { sortOpen = true }
                        ChordMenu(expanded = sortOpen, onDismiss = { sortOpen = false }, anchorSize = ControlSize) {
                            TrackSort.values().forEach { s ->
                                ChordMenuItem(s.label, if (s == sort) Icons.Rounded.Check else Icons.AutoMirrored.Rounded.Sort) {
                                    sortOpen = false; onSort(s)
                                }
                            }
                        }
                    }

                    var moreOpen by remember { mutableStateOf(false) }
                    Box {
                        PillIcon(Icons.Rounded.MoreHoriz, "More") { moreOpen = true }
                        ChordMenu(expanded = moreOpen, onDismiss = { moreOpen = false }, anchorSize = ControlSize) {
                            if (canPlay) {
                                ChordMenuItem("Shuffle", Icons.Rounded.Shuffle) { moreOpen = false; onShuffle() }
                                ChordMenuItem("Play next", Icons.AutoMirrored.Rounded.PlaylistPlay) { moreOpen = false; onPlayNext() }
                                ChordMenuItem("Add to queue", Icons.AutoMirrored.Rounded.QueueMusic) { moreOpen = false; onAddToQueue() }
                                ChordMenuItem("Download all", Icons.Rounded.Download) { moreOpen = false; onDownloadAll() }
                                ChordMenuDivider()
                            }
                            ChordMenuItem("Share", Icons.Rounded.Share) { moreOpen = false; onShare() }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GlassCircle(haze: HazeState, glass: HazeStyle, onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        Modifier.size(ControlSize).clip(CircleShape).hazeEffect(haze, glass)
            .border(0.7.dp, Hairline, CircleShape)
            .pressScale(pressedScale = 0.9f, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { content() }
}

@Composable
private fun PillIcon(icon: ImageVector, label: String, onClick: () -> Unit) {
    Box(
        Modifier.size(width = 48.dp, height = ControlSize).clip(CircleShape).pressScale(pressedScale = 0.88f, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, label, tint = Color.White, modifier = Modifier.size(22.dp)) }
}

// ---- Header -------------------------------------------------------------------

@Composable
private fun HeaderBlock(
    header: PlaylistHeader,
    isAlbum: Boolean,
    tracks: List<PlaylistTrack>,
    songs: List<Song>,
    canPlay: Boolean,
    scrollPx: () -> Float,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { LibraryStore.get(context) }
    val playlists by remember(store) { store.playlists() }.collectAsState(initial = emptyList())
    val inLibrary = playlists.any { it.name.equals(header.title, ignoreCase = true) }
    // Smaller cover, capped so tablets do not get a giant one.
    val artSize = (LocalConfiguration.current.screenWidthDp * 0.52f).coerceAtMost(220f).dp

    Column(
        Modifier.fillMaxWidth().statusBarsPadding().padding(top = BarHeight, bottom = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .graphicsLayer {
                    val p = (scrollPx() / 520f).coerceIn(0f, 1f)
                    val s = 1f - 0.12f * p
                    scaleX = s; scaleY = s; alpha = 1f - p
                }
                .shadow(18.dp, RoundedCornerShape(14.dp), ambientColor = Color.Black, spotColor = Color.Black),
        ) {
            CoverImage(header.thumbnailUrl, artSize, RoundedCornerShape(14.dp))
        }
        Spacer(Modifier.height(14.dp))
        Text(
            header.title,
            color = Color.White, fontSize = 20.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold,
            letterSpacing = (-0.3).sp, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 28.dp),
        )
        val artist = artistLine(header, isAlbum)
        if (artist.isNotBlank()) {
            Text(
                artist, color = Color.White.copy(alpha = 0.78f), fontSize = 14.sp, fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp, start = 28.dp, end = 28.dp),
            )
        }
        val meta = metaLine(header, isAlbum, tracks)
        if (meta.isNotBlank()) {
            Text(
                meta, color = Dim, fontSize = 11.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 4.dp, start = 28.dp, end = 28.dp),
            )
        }
        Spacer(Modifier.height(14.dp))
        Row(
            Modifier.fillMaxWidth().padding(horizontal = Gutter),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RoundAction(Icons.Rounded.Shuffle, "Shuffle", canPlay, onShuffle)
            Row(
                Modifier.weight(1f).height(ActionHeight)
                    .graphicsLayer { alpha = if (canPlay) 1f else 0.4f }
                    .clip(RoundedCornerShape(50)).background(Color.White)
                    .then(if (canPlay) Modifier.pressScale(pressedScale = 0.96f, onClick = onPlay) else Modifier),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.PlayArrow, null, tint = Color.Black, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(4.dp))
                Text("Play", color = Color.Black, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }
            RoundAction(if (inLibrary) Icons.Rounded.Check else Icons.Rounded.Add, if (inLibrary) "In library" else "Save to library", canPlay) {
                if (inLibrary) {
                    Toast.makeText(context, "Already in your library", Toast.LENGTH_SHORT).show()
                } else scope.launch {
                    val id = store.create(header.title)
                    store.addAll(id, songs)
                    Toast.makeText(context, "Saved to library", Toast.LENGTH_SHORT).show()
                }
            }
        }
        if (header.description.isNotBlank()) {
            Column(Modifier.fillMaxWidth().padding(horizontal = Gutter).padding(top = 14.dp, bottom = 4.dp)) {
                Text(
                    header.description, color = Dim, fontSize = 13.sp, lineHeight = 18.sp,
                    maxLines = if (expanded) 40 else 2, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    if (expanded) "Less" else "More", color = Link, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(top = 4.dp).clickable { expanded = !expanded },
                )
            }
        } else {
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun RoundAction(icon: ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(ActionHeight)
            .graphicsLayer { alpha = if (enabled) 1f else 0.4f }
            .clip(CircleShape).background(Color.White.copy(alpha = 0.10f))
            .border(0.7.dp, Hairline, CircleShape)
            .then(if (enabled) Modifier.pressScale(pressedScale = 0.9f, onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, label, tint = Color.White, modifier = Modifier.size(20.dp)) }
}

private fun seconds(d: String): Int {
    val p = d.split(":").mapNotNull { it.trim().toIntOrNull() }
    return when (p.size) { 2 -> p[0] * 60 + p[1]; 3 -> p[0] * 3600 + p[1] * 60 + p[2]; else -> 0 }
}

/** Artist line. Album pages: byline, else the second part of "Album • Artist • 2026". */
private fun artistLine(h: PlaylistHeader, isAlbum: Boolean): String {
    if (h.byline.isNotBlank()) return h.byline
    if (!isAlbum) return ""
    return h.subtitle.split("•", "·").map { it.trim() }
        .filter { it.isNotBlank() && !it.equals("Album", true) && !it.equals("Single", true) && !it.equals("EP", true) && !Regex("(19|20)\\d{2}").matches(it) }
        .firstOrNull().orEmpty()
}

/** "Album · 2026 · 11 Tracks · 32 min". Falls back to the raw subtitle while tracks load. */
private fun metaLine(h: PlaylistHeader, isAlbum: Boolean, tracks: List<PlaylistTrack>): String {
    if (tracks.isEmpty()) return listOf(h.subtitle, h.secondSubtitle).filter { it.isNotBlank() }.joinToString(" · ")
    val type = if (isAlbum) h.subtitle.split("•", "·").map { it.trim() }.firstOrNull { it.equals("Single", true) || it.equals("EP", true) } ?: "Album" else "Playlist"
    val year = Regex("(19|20)\\d{2}").find(h.subtitle)?.value
    val secs = tracks.sumOf { seconds(it.duration) }
    val dur = when {
        secs <= 0 -> null
        secs >= 3600 -> "${secs / 3600} hr ${(secs % 3600) / 60} min"
        else -> "${(secs + 30) / 60} min"
    }
    return listOfNotNull(type, year, "${tracks.size} Tracks", dur).joinToString(" · ")
}

// ---- Rows ---------------------------------------------------------------------

@Composable
private fun TrackRow(
    track: PlaylistTrack,
    number: Int,
    showNumber: Boolean,
    albumName: String?,
    fallbackThumb: String?,
    playing: Boolean,
    isPlaying: Boolean,
    divider: Boolean,
    onClick: () -> Unit,
    onOpenAlbum: () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth()
                .background(if (playing) Color.White.copy(alpha = 0.06f) else Color.Transparent)
                .pressScale(pressedScale = 0.985f, haptic = false, onClick = onClick)
                .padding(start = Gutter, end = 6.dp, top = 5.dp, bottom = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (showNumber) {
                Box(Modifier.width(NumberCol).height(28.dp), contentAlignment = Alignment.CenterStart) {
                    if (playing) NowPlayingOverlay(isPlaying, Modifier.size(20.dp).clip(RoundedCornerShape(5.dp)))
                    else Text(number.toString(), color = Dim, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                }
                Spacer(Modifier.width(6.dp))
            } else {
                Box(Modifier.size(RowArt), contentAlignment = Alignment.Center) {
                    CoverImage(track.thumbnailUrl ?: fallbackThumb, RowArt, RowArtShape)
                    if (playing) NowPlayingOverlay(isPlaying, Modifier.size(RowArt).clip(RowArtShape))
                }
                Spacer(Modifier.width(12.dp))
            }
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ExplicitMark(ExplicitStore.has(track.videoId), gap = 5.dp)
                    Text(
                        track.title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                if (track.artist.isNotBlank()) Text(
                    track.artist, color = Dim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            if (track.duration.isNotBlank()) {
                Spacer(Modifier.width(10.dp))
                Text(track.duration, color = Dim, fontSize = 12.sp)
            }
            TrackMenu(track, fallbackThumb, albumName, onClick, onOpenAlbum)
        }
        if (divider) Box(
            Modifier.padding(start = if (showNumber) Gutter + NumberCol + 6.dp else Gutter + RowArt + 12.dp)
                .fillMaxWidth().height(0.6.dp).background(Color.White.copy(alpha = 0.08f)),
        )
    }
}

@Composable
private fun TrackMenu(
    track: PlaylistTrack,
    fallbackThumb: String?,
    albumName: String?,
    onPlay: () -> Unit,
    onOpenAlbum: () -> Unit,
) {
    val moreFromArtist = LocalOnSearchArtist.current
    val addToPlaylist = LocalAddToPlaylist.current
    var open by remember { mutableStateOf(false) }
    Box {
        Box(
            Modifier.size(36.dp).clip(CircleShape).clickable { open = true },
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Rounded.MoreVert, "More", tint = Dim, modifier = Modifier.size(20.dp)) }
        SongMenu(
            expanded = open,
            onDismiss = { open = false },
            song = Song(track.title, track.artist, track.videoId, track.thumbnailUrl ?: fallbackThumb),
            onPlay = onPlay,
            onAddToPlaylist = addToPlaylist,
            onOpenArtist = moreFromArtist?.let { go -> { name: String -> go(name.substringBefore(",").trim()) } },
            artist = track.artist,
            showPlay = false,
            album = albumName,
            onOpenAlbum = { onOpenAlbum() },
        )
    }
}

private val TitleWidths = listOf(110.dp, 150.dp, 90.dp, 130.dp, 170.dp)

@Composable
private fun TrackSkeleton(index: Int) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = Gutter, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ShimmerBox(Modifier.size(24.dp), RoundedCornerShape(6.dp))
        Spacer(Modifier.width(16.dp))
        Column {
            ShimmerBox(Modifier.width(TitleWidths[index % TitleWidths.size]).height(15.dp))
            Spacer(Modifier.height(8.dp))
            ShimmerBox(Modifier.width(80.dp).height(12.dp))
        }
    }
}

@Composable
private fun ErrorBlock(message: String, onRetry: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Couldn't load this page", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        Text(message, color = Dim, fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp))
        Text(
            "Retry", color = Color.Black, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 16.dp).clip(RoundedCornerShape(50)).background(Color.White)
                .pressScale(onClick = onRetry).padding(horizontal = 26.dp, vertical = 10.dp),
        )
    }
}
