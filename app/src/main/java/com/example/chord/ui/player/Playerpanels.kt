package com.example.chord.ui.player

import android.content.Context
import android.content.Intent
import androidx.compose.ui.platform.LocalContext
import com.example.chord.data.model.Song
import com.example.chord.ui.theme.rememberArtColors
import android.os.Build
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AllInclusive
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material.icons.rounded.FormatListBulleted
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.IosShare
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import coil.compose.AsyncImage
import com.example.chord.ui.lyrics.WordByWordLyricsView

// ───────────────────────── models ─────────────────────────

enum class ProviderStatus { Current, Found, Idle, Loading, NotFound }
enum class RepeatMode { Off, All, One }

data class LyricsProviderOption(val id: String, val name: String, val status: ProviderStatus)
data class QueueRow(val title: String, val subtitle: String, val thumbnailUrl: String?)

/** Everything the lyrics / queue tabs need beyond PlaybackState. All fields have safe defaults. */
data class PlayerExtras(
    val isFavorite: Boolean = false,
    val onToggleFavorite: () -> Unit = {},
    val onShare: () -> Unit = {},
    val onMore: () -> Unit = {},
    val lyricsProviderName: String? = null,
    val providers: List<LyricsProviderOption> = emptyList(),
    val onSelectProvider: (String) -> Unit = {},
    /** Leave empty to build rows from playbackState.queue (no thumbnails). */
    val queueRows: List<QueueRow> = emptyList(),
    val onRemoveQueueItem: (Int) -> Unit = {},
    /** Drag reorder. from = old queue index, to = new queue index. Wire to player.moveMediaItem(from, to). */
    val onMoveQueueItem: (from: Int, to: Int) -> Unit = { _, _ -> },
    val shuffle: Boolean = false,
    val onToggleShuffle: () -> Unit = {},
    val repeat: RepeatMode = RepeatMode.Off,
    val onCycleRepeat: () -> Unit = {},
    val autoplay: Boolean = true,
    val onToggleAutoplay: () -> Unit = {},
)

/** Share sheet: "Title - Artist" and a YouTube Music link. */
fun shareSong(context: Context, song: Song) {
    val link = "https://music.youtube.com/watch?v=${song.videoId}"
    val text = listOf(song.title, com.example.chord.data.history.HistoryStore.cleanArtist(song.details))
        .filter { it.isNotBlank() }.joinToString(" - ") + "\n" + link
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    runCatching {
        context.startActivity(Intent.createChooser(send, "Share song").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

/** Opens the system media-output switcher (Bluetooth / speaker / cast). */
fun openAudioOutput(context: Context) {
    val intent = if (Build.VERSION.SDK_INT >= 29) Intent(Settings.Panel.ACTION_VOLUME)
    else Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
    runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

private val Dim = Color.White.copy(alpha = 0.60f)
private val Chip = Color.White.copy(alpha = 0.16f)

// ───────────────────────── shared pieces ─────────────────────────

@Composable
private fun CircleChip(
    icon: ImageVector,
    desc: String,
    tint: Color = Color.White,
    size: Dp = 34.dp,
    bg: Color = Chip,
    onClick: () -> Unit,
) {
    Box(
        Modifier.size(size).clip(CircleShape).background(bg).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, desc, tint = tint, modifier = Modifier.size(size * 0.5f)) }
}

@Composable
private fun PanelHeader(
    title: String,
    artist: String,
    thumbnailUrl: String?,
    extras: PlayerExtras,
    trailing: ImageVector,
    trailingDesc: String,
    onTrailing: () -> Unit,
    /** Colour of the icon buttons. Lyrics page passes the cover colour. */
    tint: Color = Color.White,
    /** Extra button after [trailing], e.g. the More menu. */
    after: (@Composable () -> Unit)? = null,
) {
    val chipBg = if (tint == Color.White) Chip else tint.copy(alpha = 0.20f)
    Column(Modifier.fillMaxWidth().statusBarsPadding()) {
        Box(
            Modifier.align(Alignment.CenterHorizontally).padding(top = 6.dp)
                .size(32.dp, 4.dp).clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.55f))
        )
        Spacer(Modifier.height(8.dp))
        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            AsyncImage(
                model = thumbnailUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(44.dp).clip(RoundedCornerShape(8.dp)).background(Chip),
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    title.ifBlank { "Nothing playing" }, color = Color.White, fontSize = 15.sp,
                    fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(artist.ifBlank { " " }, color = Dim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(6.dp))
            CircleChip(if (extras.isFavorite) Icons.Rounded.Star else Icons.Rounded.StarBorder, "Favourite", tint = tint, bg = chipBg, onClick = extras.onToggleFavorite)
            Spacer(Modifier.width(8.dp))
            CircleChip(trailing, trailingDesc, tint = tint, bg = chipBg, onClick = onTrailing)
            if (after != null) {
                Spacer(Modifier.width(8.dp))
                after()
            }
        }
    }
}

// ───────────────────────── lyrics tab ─────────────────────────

@Composable
fun LyricsTab(
    title: String,
    artist: String,
    thumbnailUrl: String?,
    lyrics: String?,
    isLoading: Boolean,
    position: Long,
    isPlaying: Boolean,
    onSeek: (Long) -> Unit,
    extras: PlayerExtras,
    modifier: Modifier = Modifier,
    immersive: Boolean = false,
    onImmersiveChange: (Boolean) -> Unit = {},
    plainLyrics: String? = null,
    song: Song? = null,
) {
    var showSheet by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val art = rememberArtColors(thumbnailUrl)
    val accent by art.accent
    var moreOpen by remember { mutableStateOf(false) }

    Column(modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = !immersive,
            enter = fadeIn(tween(150)),
            exit = fadeOut(tween(110)),
        ) {
            PanelHeader(
                title, artist, thumbnailUrl, extras, Icons.Rounded.IosShare, "Share",
                onTrailing = { song?.let { shareSong(context, it) } ?: extras.onShare() },
                tint = accent,
                after = {
                    Box {
                        CircleChip(
                            Icons.Rounded.MoreHoriz,
                            "More",
                            tint = accent,
                            bg = accent.copy(alpha = 0.20f),
                        ) { moreOpen = true }
                        if (song != null && song.videoId.isNotBlank()) {
                            com.example.chord.ui.components.SongMenu(
                                expanded = moreOpen,
                                onDismiss = { moreOpen = false },
                                song = song,
                                onPlay = {},
                                showPlay = false,
                                onAddToPlaylist = { extras.onMore() },
                                onOpenArtist = null,
                                artist = song.details,
                            )
                        }
                    }
                },
            )
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                isLoading -> CircularProgressIndicator(
                    Modifier.align(Alignment.Center),
                    color = Color.White,
                    strokeWidth = 2.dp,
                )

                !lyrics.isNullOrBlank() -> key(lyrics) {
                    WordByWordLyricsView(
                        rawLyrics = lyrics,
                        currentPositionMs = position,
                        onSeekRequested = onSeek,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(start = 22.dp, end = 22.dp, top = 18.dp, bottom = 12.dp),
                        isPlaying = isPlaying,
                        onUserScrollChange = onImmersiveChange,
                    )
                }

                !plainLyrics.isNullOrBlank() -> Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 24.dp, vertical = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        "LYRICS",
                        color = accent.copy(alpha = 0.9f),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.5.sp,
                    )
                    Text(
                        plainLyrics,
                        color = Color.White.copy(alpha = 0.92f),
                        fontSize = 19.sp,
                        fontWeight = FontWeight.Medium,
                        lineHeight = 31.sp,
                    )
                    Spacer(Modifier.height(48.dp))
                }

                else -> Column(
                    Modifier
                        .align(Alignment.Center)
                        .padding(horizontal = 32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        "Lyrics unavailable",
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        if (extras.providers.isNotEmpty()) {
                            "Choose another lyrics provider to try this track."
                        } else {
                            "There are no synced or plain lyrics for this track yet."
                        },
                        color = Dim,
                        fontSize = 13.sp,
                        lineHeight = 19.sp,
                        textAlign = TextAlign.Center,
                    )
                    if (extras.providers.isNotEmpty()) {
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(24.dp))
                                .background(Color.White.copy(alpha = 0.10f))
                                .clickable { showSheet = true }
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                        ) {
                            Text(
                                "Choose provider",
                                color = Color.White,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }
            }
        }

        val providerLabel = extras.lyricsProviderName
            ?: if (extras.providers.isNotEmpty()) "Choose lyrics provider" else null

        if (!immersive && providerLabel != null) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 20.dp, bottom = 10.dp, top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    providerLabel,
                    color = Color.White.copy(alpha = 0.66f),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(Color.White.copy(alpha = 0.07f))
                        .clickable { showSheet = true }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                )
                Spacer(Modifier.weight(1f))
                Text(
                    "Tap a lyric line to seek",
                    color = Color.White.copy(alpha = 0.38f),
                    fontSize = 11.sp,
                )
            }
        }
    }

    if (showSheet) {
        ProviderSheet(
            extras.providers,
            { extras.onSelectProvider(it); showSheet = false },
        ) { showSheet = false }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProviderSheet(providers: List<LyricsProviderOption>, onSelect: (String) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Color(0xFF141414), contentColor = Color.White) {
        Text(
            "Lyrics provider", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 20.dp, bottom = 10.dp),
        )
        LazyColumn(
            contentPadding = PaddingValues(start = 14.dp, end = 14.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(providers, key = { it.id }) { p ->
                val current = p.status == ProviderStatus.Current
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))
                        .background(if (current) Color(0xFF2B2B2B) else Color(0xFF1E1E1E))
                        .clickable(enabled = p.status != ProviderStatus.Loading) { onSelect(p.id) }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(34.dp).clip(CircleShape).background(Color(0xFF333333)), contentAlignment = Alignment.Center) {
                        when (p.status) {
                            ProviderStatus.Current, ProviderStatus.Found -> Icon(Icons.Rounded.Check, null, tint = Color.White, modifier = Modifier.size(18.dp))
                            ProviderStatus.Idle -> Icon(Icons.Rounded.Search, null, tint = Color.White, modifier = Modifier.size(18.dp))
                            ProviderStatus.NotFound -> Icon(Icons.Rounded.Close, null, tint = Dim, modifier = Modifier.size(18.dp))
                            ProviderStatus.Loading -> CircularProgressIndicator(Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp)
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(p.name, color = Color.White, fontSize = 15.sp, fontWeight = if (current) FontWeight.Bold else FontWeight.Normal)
                        Text(
                            when (p.status) {
                                ProviderStatus.Current -> "Current provider"
                                ProviderStatus.Found -> "Lyrics found · Tap to use"
                                ProviderStatus.Idle -> "Tap to fetch"
                                ProviderStatus.Loading -> "Fetching…"
                                ProviderStatus.NotFound -> "Lyrics not found"
                            },
                            color = Dim, fontSize = 12.sp,
                        )
                    }
                }
            }
        }
    }
}

// ───────────────────────── queue tab ─────────────────────────

/**
 * Holds drag-reorder state. List is reordered locally while finger moves,
 * real move (onMove) fires once when finger lifts.
 */
private class QueueDragState(
    private val list: SnapshotStateList<IndexedValue<QueueRow>>,
    private val listState: LazyListState,
) {
    var key by mutableStateOf<Int?>(null)
    var offset by mutableFloatStateOf(0f)
    var base = 0                       // lazy-list index of first upcoming item
    var currentIndex = 0               // queue index of playing song
    var onMove: (Int, Int) -> Unit = { _, _ -> }

    fun start(k: Int) {
        key = k
        offset = 0f
    }

    fun drag(dy: Float) {
        val k = key ?: return
        offset += dy
        val info = listState.layoutInfo
        val cur = info.visibleItemsInfo.firstOrNull { it.key == k } ?: return
        val pos = list.indexOfFirst { it.index == k }
        if (pos < 0 || cur.index != base + pos) return // layout not caught up yet

        val center = cur.offset + cur.size / 2f + offset
        val target = info.visibleItemsInfo.firstOrNull {
            it.key != k && it.index >= base && it.index < base + list.size &&
                    center >= it.offset && center <= it.offset + it.size
        }
        if (target != null) {
            val item = list.removeAt(pos)
            list.add(target.index - base, item)
            offset += (cur.offset - target.offset).toFloat() // keep item under finger
            return
        }

        // auto scroll near edges
        val top = cur.offset + offset
        val bottom = top + cur.size
        val d = when {
            top < info.viewportStartOffset + 64f -> -14f
            bottom > info.viewportEndOffset - 64f -> 14f
            else -> 0f
        }
        if (d != 0f) offset += listState.dispatchRawDelta(d)
    }

    fun end() {
        val k = key ?: return
        val pos = list.indexOfFirst { it.index == k }
        key = null
        offset = 0f
        if (pos >= 0) {
            val to = currentIndex + 1 + pos
            if (to != k) onMove(k, to)
        }
    }
}

@Composable
fun QueueTab(
    title: String,
    artist: String,
    thumbnailUrl: String?,
    rows: List<QueueRow>,
    currentIndex: Int,
    onPlay: (Int) -> Unit,
    extras: PlayerExtras,
    modifier: Modifier = Modifier,
) {
    val current = rows.getOrNull(currentIndex)
    val listState = rememberLazyListState()
    val upcoming = remember {
        mutableStateListOf<IndexedValue<QueueRow>>().apply {
            addAll(rows.withIndex().filter { it.index > currentIndex })
        }
    }
    val drag = remember { QueueDragState(upcoming, listState) }
    drag.currentIndex = currentIndex
    drag.onMove = extras.onMoveQueueItem
    // Songs already played stay in the list (above Now playing), so a click never wipes them.
    val played = remember(rows, currentIndex) {
        if (currentIndex > 0) rows.withIndex().filter { it.index < currentIndex } else emptyList()
    }
    val playedOffset = if (played.isEmpty()) 0 else played.size + 1 // rows + header
    drag.base = playedOffset + 2 + if (current != null) 1 else 0

    // Keep Now playing at the top. Played songs wait above, scroll up to see them.
    LaunchedEffect(currentIndex, playedOffset) {
        if (drag.key == null) {
            if (listState.firstVisibleItemIndex <= playedOffset + 1) listState.animateScrollToItem(playedOffset)
        }
    }

    LaunchedEffect(rows, currentIndex) {
        if (drag.key == null) {
            upcoming.clear()
            upcoming.addAll(rows.withIndex().filter { it.index > currentIndex })
        }
    }

    Column(modifier.fillMaxSize()) {
        PanelHeader(
            title,
            artist,
            thumbnailUrl,
            extras,
            Icons.Rounded.MoreHoriz,
            "More",
            extras.onMore,
        )

        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "Queue",
                    color = Color.White,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-0.5).sp,
                )
                Text(
                    "${rows.size} ${if (rows.size == 1) "track" else "tracks"}",
                    color = Dim,
                    fontSize = 12.sp,
                )
            }
            if (extras.shuffle) {
                Text(
                    "SHUFFLE ON",
                    color = Color.White.copy(alpha = 0.78f),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(Color.White.copy(alpha = 0.10f))
                        .padding(horizontal = 10.dp, vertical = 7.dp),
                )
            }
        }

        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            state = listState,
            contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 4.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            if (played.isNotEmpty()) {
                item(key = "hdr_played") {
                    Text(
                        "PLAYED",
                        color = Color.White.copy(alpha = 0.42f),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.4.sp,
                        modifier = Modifier.padding(start = 6.dp, top = 8.dp, bottom = 7.dp),
                    )
                }
                items(played, key = { "played_${it.index}" }) { (index, row) ->
                    QueueItem(
                        row = row,
                        isCurrent = false,
                        modifier = Modifier.animateItem().graphicsLayer { alpha = 0.6f },
                        onClick = { onPlay(index) },
                        onRemove = { extras.onRemoveQueueItem(index) },
                    )
                }
            }

            item(key = "hdr_now") {
                Text(
                    "NOW PLAYING",
                    color = Color.White.copy(alpha = 0.42f),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.4.sp,
                    modifier = Modifier.padding(start = 6.dp, top = 8.dp, bottom = 7.dp),
                )
            }

            current?.let { row ->
                item(key = "current") {
                    QueueItem(
                        row = row,
                        isCurrent = true,
                        modifier = Modifier.animateItem(),
                        onClick = {},
                        onRemove = { extras.onRemoveQueueItem(currentIndex) },
                    )
                }
            }

            item(key = "hdr_next") {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 6.dp, top = 18.dp, bottom = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        if (extras.autoplay) "AUTOPLAY" else "UP NEXT",
                        color = Color.White.copy(alpha = 0.42f),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.4.sp,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "${upcoming.size}",
                        color = Color.White.copy(alpha = 0.34f),
                        fontSize = 10.sp,
                    )
                }
            }

            if (upcoming.isEmpty()) {
                item(key = "empty_up_next") {
                    Text(
                        if (extras.autoplay) {
                            "More similar music will be added automatically."
                        } else {
                            "There are no more songs in the queue."
                        },
                        color = Dim,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 12.dp),
                    )
                }
            }

            items(upcoming, key = { it.index }) { (index, row) ->
                QueueItem(
                    row = row,
                    isCurrent = false,
                    modifier = Modifier.animateItem(),
                    dragging = drag.key == index,
                    dragOffset = { drag.offset },
                    onClick = { onPlay(index) },
                    onRemove = { extras.onRemoveQueueItem(index) },
                    onDragStart = { drag.start(index) },
                    onDrag = { drag.drag(it) },
                    onDragEnd = { drag.end() },
                )
            }
        }
    }
}

@Composable
private fun QueueItem(
    row: QueueRow,
    isCurrent: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onRemove: () -> Unit,
    dragging: Boolean = false,
    dragOffset: () -> Float = { 0f },
    onDragStart: () -> Unit = {},
    onDrag: (Float) -> Unit = {},
    onDragEnd: () -> Unit = {},
) {
    Row(
        modifier
            .fillMaxWidth()
            .zIndex(if (dragging) 1f else 0f)
            .graphicsLayer {
                translationY = if (dragging) dragOffset() else 0f
                scaleX = if (dragging) 1.015f else 1f
                scaleY = if (dragging) 1.015f else 1f
                alpha = if (dragging) 0.96f else 1f
            }
            .clip(RoundedCornerShape(15.dp))
            .background(
                when {
                    dragging -> Color(0xFF303030)
                    isCurrent -> Color.White.copy(alpha = 0.095f)
                    else -> Color.White.copy(alpha = 0.025f)
                },
            )
            .clickable(enabled = !isCurrent && !dragging, onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (!isCurrent) {
            Box(
                Modifier
                    .size(30.dp)
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDragStart = { onDragStart() },
                            onDragEnd = { onDragEnd() },
                            onDragCancel = { onDragEnd() },
                            onDrag = { change, amount ->
                                change.consume()
                                onDrag(amount.y)
                            },
                        )
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Rounded.DragHandle,
                    "Reorder",
                    tint = Color.White.copy(alpha = 0.38f),
                    modifier = Modifier.size(19.dp),
                )
            }
        } else {
            Box(
                Modifier.size(30.dp),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Rounded.GraphicEq,
                    null,
                    tint = Color.White,
                    modifier = Modifier.size(18.dp),
                )
            }
        }

        AsyncImage(
            model = row.thumbnailUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .padding(start = 5.dp)
                .size(48.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Chip),
        )
        Spacer(Modifier.width(11.dp))
        Column(Modifier.weight(1f)) {
            Text(
                row.title,
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                row.subtitle.ifBlank { "Unknown artist" },
                color = Dim,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (isCurrent) {
            Text(
                "PLAYING",
                color = Color.White.copy(alpha = 0.74f),
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.8.sp,
                modifier = Modifier.padding(horizontal = 7.dp),
            )
        }
        Icon(
            Icons.Rounded.Close,
            "Remove",
            tint = Color.White.copy(alpha = 0.42f),
            modifier = Modifier
                .size(34.dp)
                .clip(CircleShape)
                .clickable(onClick = onRemove)
                .padding(9.dp),
        )
    }
}

/** Bottom bar shown on the queue tab: shuffle | repeat | autoplay pill, and the queue toggle. */
@Composable
fun QueueModeBar(extras: PlayerExtras, onCloseQueue: () -> Unit) {
    @Composable
    fun Segment(icon: ImageVector, active: Boolean, desc: String, onClick: () -> Unit) {
        Box(
            Modifier.width(54.dp).height(38.dp)
                .background(if (active) Color.White.copy(alpha = 0.14f) else Color.Transparent)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, desc, tint = Color.White.copy(alpha = if (active) 1f else 0.6f), modifier = Modifier.size(22.dp)) }
    }
    @Composable
    fun Divider() = Box(Modifier.width(1.dp).height(38.dp).background(Color.White.copy(alpha = 0.18f)))

    Row(
        Modifier.fillMaxWidth().height(50.dp).padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(Modifier.clip(RoundedCornerShape(24.dp)).background(Chip)) {
            Segment(Icons.Rounded.Shuffle, extras.shuffle, "Shuffle", extras.onToggleShuffle)
            Divider()
            Segment(
                if (extras.repeat == RepeatMode.One) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
                extras.repeat != RepeatMode.Off, "Repeat", extras.onCycleRepeat,
            )
            Divider()
            Segment(Icons.Rounded.AllInclusive, extras.autoplay, "AutoPlay", extras.onToggleAutoplay)
        }
        Box(
            Modifier.size(38.dp).clip(CircleShape).background(Chip).clickable(onClick = onCloseQueue),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Rounded.FormatListBulleted, "Close queue", tint = Color.White, modifier = Modifier.size(20.dp)) }
    }
}