package com.example.chord.ui.player

import android.content.Context
import android.media.AudioManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import coil.compose.AsyncImage
import com.example.chord.playback.PlaybackState
import com.example.chord.ui.lyrics.WordByWordLyricsView

private val PlayerWhite = Color.White
private val PlayerDark = Color(0xFF111114)

private const val TAB_PLAYER = 0
private const val TAB_LYRICS = 1
private const val TAB_QUEUE = 2

/**
 * Now-playing screen. Playback state and actions remain owned by the existing
 * playback layer; this composable only renders the UI and forwards user input.
 */
@Composable
fun PlayerScreen(
    playbackState: PlaybackState,
    thumbnailUrl: String?,
    syncedLyrics: String?,
    isLoadingLyrics: Boolean,
    onTogglePlayPause: () -> Unit,
    onBack: () -> Unit,
    onSeek: (Long) -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onPlayQueueItem: (Int) -> Unit
) {
    var selectedTab by remember { mutableIntStateOf(TAB_PLAYER) }
    var artworkBottomPx by remember { mutableIntStateOf(0) }

    val context = LocalContext.current
    val density = LocalDensity.current
    val audioManager = remember {
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }
    val maxVolume = remember {
        audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
    }
    var volume by remember {
        mutableFloatStateOf(audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat())
    }

    val duration = playbackState.duration.coerceAtLeast(0L)
    val position = playbackState.position.coerceAtLeast(0L)
        .let { if (duration > 0L) it.coerceAtMost(duration) else it }
    val buffering = playbackState.isLoading ||
            playbackState.playbackState == Player.STATE_BUFFERING
    val artworkUrl = remember(thumbnailUrl) { highResolutionArtworkUrl(thumbnailUrl) }
    val seamDp = with(density) { artworkBottomPx.toDp() }

    Box(Modifier.fillMaxSize().background(PlayerDark)) {
        ArtworkMeshBackdrop(
            artworkUrl = artworkUrl,
            seam = seamDp,
            modifier = Modifier.fillMaxSize(),
            blurRadius = 18.dp,
            darken = true
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            PlayerHeader(
                onBack = onBack,
                onMore = { selectedTab = TAB_QUEUE }
            )

            when (selectedTab) {
                TAB_LYRICS -> {
                    LyricsPanel(
                        syncedLyrics = syncedLyrics,
                        isLoadingLyrics = isLoadingLyrics,
                        title = playbackState.title,
                        position = position,
                        onSeek = onSeek,
                        modifier = Modifier.weight(1f).fillMaxWidth()
                    )
                }

                TAB_QUEUE -> {
                    QueuePanel(
                        playbackState = playbackState,
                        onPlayQueueItem = onPlayQueueItem,
                        modifier = Modifier.weight(1f).fillMaxWidth()
                    )
                }

                else -> {
                    PlayerMainContent(
                        playbackState = playbackState,
                        artworkUrl = artworkUrl,
                        buffering = buffering,
                        duration = duration,
                        position = position,
                        volume = volume,
                        maxVolume = maxVolume,
                        onVolumeChange = { newVolume ->
                            volume = newVolume
                            audioManager.setStreamVolume(
                                AudioManager.STREAM_MUSIC,
                                newVolume.toInt().coerceIn(0, maxVolume),
                                0
                            )
                        },
                        onSeek = onSeek,
                        onPrevious = onPrevious,
                        onTogglePlayPause = onTogglePlayPause,
                        onNext = onNext,
                        onArtworkBottomChanged = { artworkBottomPx = it },
                        modifier = Modifier.weight(1f).fillMaxWidth()
                    )
                }
            }

            PlayerBottomBar(
                selectedTab = selectedTab,
                onPlayer = { selectedTab = TAB_PLAYER },
                onLyrics = { selectedTab = if (selectedTab == TAB_LYRICS) TAB_PLAYER else TAB_LYRICS },
                onQueue = { selectedTab = TAB_QUEUE }
            )
        }
    }
}

@Composable
private fun PlayerHeader(
    onBack: () -> Unit,
    onMore: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack, modifier = Modifier.size(44.dp)) {
            PlayerGlyph(PlayerIcon.Back, Modifier.size(23.dp))
        }
        Spacer(Modifier.weight(1f))
        Text(
            text = "NOW PLAYING",
            color = PlayerWhite.copy(alpha = 0.76f),
            fontSize = 11.sp,
            letterSpacing = 2.sp,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.weight(1f))
        IconButton(onClick = onMore, modifier = Modifier.size(44.dp)) {
            PlayerGlyph(PlayerIcon.More, Modifier.size(22.dp))
        }
    }
}

@Composable
private fun PlayerMainContent(
    playbackState: PlaybackState,
    artworkUrl: String?,
    buffering: Boolean,
    duration: Long,
    position: Long,
    volume: Float,
    maxVolume: Int,
    onVolumeChange: (Float) -> Unit,
    onSeek: (Long) -> Unit,
    onPrevious: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onNext: () -> Unit,
    onArtworkBottomChanged: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.padding(horizontal = 25.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(8.dp))

        // Artwork occupies the hero area; its bottom coordinate anchors the mesh seam.
        BoxWithConstraints(
            modifier = Modifier
                .weight(1f, fill = true)
                .fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            val artworkSize = minOf(maxWidth * 0.92f, maxHeight * 0.92f, 390.dp)
            if (artworkUrl != null) {
                AsyncImage(
                    model = artworkUrl,
                    contentDescription = "Album artwork",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .width(artworkSize)
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .onGloballyPositioned { coordinates ->
                            val positionInRoot = coordinates.positionInRoot()
                            onArtworkBottomChanged(
                                (positionInRoot.y + coordinates.size.height).toInt()
                            )
                        }
                )
            } else {
                Box(
                    Modifier
                        .width(artworkSize)
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.White.copy(alpha = 0.08f))
                        .onGloballyPositioned { coordinates ->
                            val positionInRoot = coordinates.positionInRoot()
                            onArtworkBottomChanged(
                                (positionInRoot.y + coordinates.size.height).toInt()
                            )
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text("♪", color = PlayerWhite.copy(alpha = 0.7f), fontSize = 64.sp)
                }
            }
        }

        Spacer(Modifier.height(18.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = playbackState.title.ifBlank { "Nothing playing" },
                    color = PlayerWhite,
                    fontSize = 23.sp,
                    lineHeight = 28.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = playbackState.queue
                        .getOrNull(playbackState.currentQueueIndex)
                        ?.details
                        .orEmpty()
                        .ifBlank { if (buffering) "Loading audio…" else " " },
                    color = PlayerWhite.copy(alpha = 0.68f),
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(onClick = {}, modifier = Modifier.size(46.dp)) {
                PlayerGlyph(PlayerIcon.Heart, Modifier.size(23.dp))
            }
        }

        Spacer(Modifier.height(10.dp))

        Column(Modifier.fillMaxWidth()) {
            Slider(
                value = position.toFloat(),
                onValueChange = { onSeek(it.toLong()) },
                valueRange = 0f..duration.coerceAtLeast(1L).toFloat(),
                enabled = duration > 0L && playbackState.canSeek,
                modifier = Modifier.fillMaxWidth().height(28.dp),
                colors = SliderDefaults.colors(
                    thumbColor = PlayerWhite,
                    activeTrackColor = PlayerWhite,
                    inactiveTrackColor = PlayerWhite.copy(alpha = 0.28f)
                )
            )
            Row(Modifier.fillMaxWidth()) {
                Text(formatPlayerTime(position), color = PlayerWhite.copy(alpha = 0.68f), fontSize = 11.sp)
                Spacer(Modifier.weight(1f))
                Text(
                    "-${formatPlayerTime((duration - position).coerceAtLeast(0L))}",
                    color = PlayerWhite.copy(alpha = 0.68f),
                    fontSize = 11.sp
                )
            }
        }

        Spacer(Modifier.height(13.dp))

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onPrevious, modifier = Modifier.size(54.dp)) {
                PlayerGlyph(PlayerIcon.Previous, Modifier.size(31.dp))
            }
            IconButton(onClick = onTogglePlayPause, modifier = Modifier.size(68.dp)) {
                if (buffering) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(31.dp),
                        color = PlayerWhite,
                        strokeWidth = 2.dp
                    )
                } else {
                    PlayerGlyph(
                        if (playbackState.isPlaying) PlayerIcon.Pause else PlayerIcon.Play,
                        Modifier.size(42.dp)
                    )
                }
            }
            IconButton(onClick = onNext, modifier = Modifier.size(54.dp)) {
                PlayerGlyph(PlayerIcon.Next, Modifier.size(31.dp))
            }
        }

        Spacer(Modifier.height(6.dp))

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = {
                    onVolumeChange(0f)
                },
                modifier = Modifier.size(34.dp)
            ) {
                PlayerGlyph(PlayerIcon.VolumeLow, Modifier.size(20.dp), alpha = 0.82f)
            }
            Slider(
                value = volume.coerceIn(0f, maxVolume.toFloat()),
                onValueChange = onVolumeChange,
                valueRange = 0f..maxVolume.toFloat(),
                modifier = Modifier.weight(1f).height(28.dp).padding(horizontal = 6.dp),
                colors = SliderDefaults.colors(
                    thumbColor = PlayerWhite,
                    activeTrackColor = PlayerWhite.copy(alpha = 0.78f),
                    inactiveTrackColor = PlayerWhite.copy(alpha = 0.25f)
                )
            )
            IconButton(
                onClick = { onVolumeChange(maxVolume.toFloat()) },
                modifier = Modifier.size(34.dp)
            ) {
                PlayerGlyph(PlayerIcon.Volume, Modifier.size(21.dp), alpha = 0.9f)
            }
        }

        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun LyricsPanel(
    syncedLyrics: String?,
    isLoadingLyrics: Boolean,
    title: String,
    position: Long,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    Box(modifier.padding(horizontal = 18.dp, vertical = 8.dp)) {
        when {
            isLoadingLyrics -> CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center),
                color = PlayerWhite
            )
            syncedLyrics.isNullOrBlank() -> Text(
                text = "No synced lyrics available",
                color = PlayerWhite.copy(alpha = 0.8f),
                modifier = Modifier.align(Alignment.Center)
            )
            else -> key(title, syncedLyrics) {
                WordByWordLyricsView(
                    rawLyrics = syncedLyrics,
                    currentPositionMs = position,
                    onSeekRequested = onSeek,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}

@Composable
private fun QueuePanel(
    playbackState: PlaybackState,
    onPlayQueueItem: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier.padding(horizontal = 22.dp, vertical = 12.dp)) {
        Text(
            text = "Up next",
            color = PlayerWhite,
            fontSize = 27.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(12.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            itemsIndexed(playbackState.queue) { index, song ->
                val current = index == playbackState.currentQueueIndex
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (current) PlayerWhite.copy(alpha = 0.12f) else Color.Transparent)
                        .clickable(enabled = !current) { onPlayQueueItem(index) }
                        .padding(horizontal = 12.dp, vertical = 13.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            song.title,
                            color = PlayerWhite,
                            fontWeight = if (current) FontWeight.Bold else FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            song.details,
                            color = PlayerWhite.copy(alpha = 0.64f),
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (current) Text("♫", color = PlayerWhite, modifier = Modifier.padding(start = 10.dp))
                }
            }
        }
    }
}

@Composable
private fun PlayerBottomBar(
    selectedTab: Int,
    onPlayer: () -> Unit,
    onLyrics: () -> Unit,
    onQueue: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(62.dp)
            .padding(horizontal = 22.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onLyrics, modifier = Modifier.size(46.dp)) {
            PlayerGlyph(PlayerIcon.Lyrics, Modifier.size(25.dp), alpha = if (selectedTab == TAB_LYRICS) 1f else 0.76f)
        }

        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(32.dp))
                .background(PlayerWhite.copy(alpha = 0.12f))
                .padding(horizontal = 17.dp, vertical = 7.dp),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            PlayerGlyph(
                PlayerIcon.Headphones,
                Modifier.size(25.dp).clickable(onClick = onPlayer),
                alpha = if (selectedTab == TAB_PLAYER) 1f else 0.75f
            )
            Box(Modifier.width(1.dp).height(28.dp).background(PlayerWhite.copy(alpha = 0.2f)))
            PlayerGlyph(
                PlayerIcon.Queue,
                Modifier.size(25.dp).clickable(onClick = onQueue),
                alpha = if (selectedTab == TAB_QUEUE) 1f else 0.75f
            )
        }

        IconButton(onClick = onQueue, modifier = Modifier.size(46.dp)) {
            PlayerGlyph(PlayerIcon.Queue, Modifier.size(25.dp), alpha = if (selectedTab == TAB_QUEUE) 1f else 0.78f)
        }
    }
}
