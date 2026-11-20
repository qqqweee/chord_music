package com.example.chord.ui.player

import androidx.activity.compose.BackHandler

import android.content.Context
import android.media.AudioManager
import android.os.Build
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.MutableLongState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.media3.common.Player
import coil.compose.AsyncImage
import coil.imageLoader
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import coil.request.ImageRequest
import coil.size.Precision
import coil.size.Size
import androidx.compose.ui.graphics.FilterQuality
import com.example.chord.playback.PlaybackState
import com.example.chord.ui.haptics.Haptic
import com.example.chord.ui.haptics.hapticClickable
import com.example.chord.ui.lyrics.EnhancedLyricLine
import com.example.chord.ui.theme.ChordTheme
import com.example.chord.ui.lyrics.WordLyricsParser
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/* ════════════════════════════════════════════════════════════════════════════
 *  TUNING — every number you might want to change lives here.
 * ════════════════════════════════════════════════════════════════════════════ */

// Layout
private const val HERO_HEIGHT_FRACTION = 0.50f   // artwork height as a share of the screen
private val HERO_MIN_HEIGHT = 240.dp
private val HERO_MAX_HEIGHT = 500.dp
private val CONTROLS_RESERVED = 340.dp           // room kept free for seek + buttons + bottom bar
private val SIDE_PADDING = 26.dp

// Title + artist
private val TITLE_SIZE = 21.sp
private val ARTIST_SIZE = 18.sp
private const val ARTIST_ALPHA = 0.65f

// Marquee (title / artist scroll when too long)
private const val MARQUEE_DP_PER_SEC = 28f       // scroll speed
private val MARQUEE_GAP = 48.dp                  // empty space between the two copies
private const val MARQUEE_REST_MS = 3_000L       // pause at the start between passes
private const val MARQUEE_START_DELAY_MS = 1_200L // wait before the first pass
private const val ARTIST_STAGGER_MS = 1_500L     // artist waits this much when the title also scrolls

// One-line lyric strip above the seek bar
private val LYRIC_SIZE = 14.sp
private val LYRIC_STRIP_HEIGHT = 25.dp
private const val LYRIC_LEAD_MS = -5L             // + makes lyrics earlier, - later. 0 = exact
private const val LYRIC_FADE_MS = 200            // cross-fade between lines
private const val LYRIC_DIM_ALPHA = 0.38f        // text not yet sung
private const val LYRIC_FEATHER_ALPHA = 0.60f    // soft edge just ahead of the sweep
private val LYRIC_FEATHER_WIDTH = 16.dp

// Swipe on the artwork to change track
private val SWIPE_THRESHOLD = 60.dp              // how far to drag before it counts
private const val SWIPE_DAMPING = 0.45f          // 1 = artwork sticks to the finger, lower = heavier
private const val SWIPE_FADE = 0.45f             // how much the artwork fades at full drag (FIXED: was 055f = 55)
private const val ARTWORK_CROSSFADE_MS = 220

// Tab motion
private val EmphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
private val EmphasizedAccelerate = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)

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
    onPlayQueueItem: (Int) -> Unit,
    sourceLabel: String = "Playing",
    audioInfo: String? = null,
    isLossless: Boolean = false,
    upgrading: Boolean = false,
    deviceName: String = Build.MODEL,
    extras: PlayerExtras = PlayerExtras(),
    onSheetDrag: (Float) -> Unit = {},
    onSheetDragEnd: (Float) -> Unit = { v -> if (v > 0f) onBack() },
    /** NEW: plain text lyrics, shown in the lyrics tab when no synced lyrics exist. */
    plainLyrics: String? = null,
    /** NEW: sheet state. Drives the open / close animation. null = always fully open. */
    sheetState: PlayerSheetState? = null,
) {
    var selectedTab by remember { mutableIntStateOf(TAB_PLAYER) }
    var immersive by remember { mutableStateOf(false) }
    LaunchedEffect(selectedTab) { if (selectedTab != TAB_LYRICS) immersive = false }
    // Layout editing: long-press empty space on the player to start.
    val layout = rememberPlayerLayout()
    // Blurred source for the edit panel glass.
    val editHaze = remember { HazeState() }
    var editMode by remember { mutableStateOf(false) }
    LaunchedEffect(selectedTab) { if (selectedTab != TAB_PLAYER) editMode = false }
    // Hidden parts become faint ghosts while editing so they can be turned back on.
    SideEffect {
        layout.editing = editMode && selectedTab == TAB_PLAYER
        if (!editMode) layout.selected = null
    }
    BackHandler(enabled = editMode) {
        layout.save()
        editMode = false
    }

    val context = LocalContext.current
    val audioManager = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val maxVolume = remember { audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1) }
    var volume by remember { mutableFloatStateOf(audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat()) }
    var volumeDragging by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        while (true) {
            if (!volumeDragging) volume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat()
            delay(400)
        }
    }
    fun applyVolume(value: Float) {
        volume = value
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, value.roundToInt().coerceIn(0, maxVolume), 0)
    }

    val duration = playbackState.duration.coerceAtLeast(0L)
    val position = playbackState.position.coerceAtLeast(0L).let { if (duration > 0L) it.coerceAtMost(duration) else it }
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    val progress = dragFraction ?: if (duration > 0L) position.toFloat() / duration else 0f
    val shownPosition = dragFraction?.let { (it * duration).toLong() } ?: position

    val buffering = playbackState.isLoading || playbackState.playbackState == Player.STATE_BUFFERING
    val artworkCandidates = remember(thumbnailUrl) { artworkUrlCandidates(thumbnailUrl) }
    val artworkUrl = artworkCandidates.firstOrNull()
    val artist = playbackState.queue.getOrNull(playbackState.currentQueueIndex)?.details.orEmpty()
    // Queue rows use small art so they load fast. Rebuilt only when the queue changes.
    val queueRows = remember(playbackState.queue, extras.queueRows) {
        playbackState.queue.map { song ->
            QueueRow(
                title = song.title,
                subtitle = song.details,
                thumbnailUrl = thumbArtworkUrl(
                    song.thumbnailUrl?.takeIf { it.isNotBlank() }
                        ?: "https://i.ytimg.com/vi/${song.videoId}/hqdefault.jpg",
                    300,
                ),
            )
        }.ifEmpty {
            extras.queueRows.map { row -> row.copy(thumbnailUrl = thumbArtworkUrl(row.thumbnailUrl, 300)) }
        }
    }
    // Warm the cache: queue thumbs, and full art for current + next songs.
    LaunchedEffect(playbackState.queue, playbackState.currentQueueIndex) {
        val loader = context.imageLoader
        queueRows.forEach { r ->
            r.thumbnailUrl?.let { loader.enqueue(ImageRequest.Builder(context).data(it).build()) }
        }
        val from = playbackState.currentQueueIndex.coerceAtLeast(0)
        playbackState.queue.drop(from).take(4).forEach { song ->
            artworkUrlCandidates(song.thumbnailUrl).firstOrNull()?.let {
                loader.enqueue(
                    ImageRequest.Builder(context).data(it)
                        .size(Size.ORIGINAL).precision(Precision.EXACT).build()
                )
            }
        }
    }
    // The sheet only follows the finger on the player page. Lyrics and queue use the swipe to step back.
    val sheetDrag = rememberDraggableState { if (selectedTab == TAB_PLAYER && !editMode) onSheetDrag(it) }
    // Swipe down: fullscreen lyrics first goes back to normal lyrics, then to the player.
    val closeFullscreen by rememberUpdatedState<() -> Unit>({
        if (immersive) { immersive = false } else { selectedTab = TAB_PLAYER }
    })
    val closeThresholdPx = with(LocalDensity.current) { 64.dp.toPx() }
    val closeConnection = remember(closeThresholdPx) {
        object : NestedScrollConnection {
            // Finger drag left over after the list hit its top edge.
            private var pulled = 0f

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput) pulled = (pulled + available.y).coerceAtLeast(0f)
                return Offset.Zero
            }

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                val close = pulled > closeThresholdPx || (pulled > closeThresholdPx / 5f && available.y > 1_200f)
                pulled = 0f
                if (close) closeFullscreen()
                return Velocity.Zero
            }
        }
    }

    // ── Artwork swipe (previous / next) ──────────────────────────────────────
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    // Track the finger with a plain float (no coroutine per pointer event), then
    // use one spring only after release. The old implementation launched a
    // snapTo coroutine for every drag delta and could build up work on slower devices.
    var swipeDragOffset by remember { mutableFloatStateOf(0f) }
    var isSwipingArtwork by remember { mutableStateOf(false) }
    val swipeSettle = remember { Animatable(0f) }
    val swipeThresholdPx = with(density) { SWIPE_THRESHOLD.toPx() }
    val currentNext by rememberUpdatedState(onNext)
    val currentPrevious by rememberUpdatedState(onPrevious)

    fun settleArtworkSwipe() {
        val from = swipeDragOffset
        isSwipingArtwork = false
        scope.launch {
            swipeSettle.snapTo(from)
            swipeSettle.animateTo(
                0f,
                spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium),
            )
        }
    }

    val skipSwipe = Modifier.pointerInput(swipeThresholdPx) {
        var total = 0f
        var crossed = false
        detectHorizontalDragGestures(
            onDragStart = {
                total = 0f
                crossed = false
                swipeDragOffset = 0f
                isSwipingArtwork = true
            },
            onDragCancel = { settleArtworkSwipe() },
            onDragEnd = {
                when {
                    total <= -swipeThresholdPx -> {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        currentNext()
                    }
                    total >= swipeThresholdPx -> {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        currentPrevious()
                    }
                }
                settleArtworkSwipe()
            },
            onHorizontalDrag = { _, delta ->
                total += delta
                val nowCrossed = abs(total) >= swipeThresholdPx
                if (nowCrossed && !crossed) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                crossed = nowCrossed
                swipeDragOffset = total * SWIPE_DAMPING
            },
        )
    }

    // Long-press on empty space opens edit mode.
    val enterEdit = Modifier.pointerInput(Unit) {
        detectTapGestures(onLongPress = {
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            editMode = true
        })
    }

    BoxWithConstraints(
        Modifier.fillMaxSize()
            .onGloballyPositioned { coordinates ->
                layout.origin = coordinates.positionInRoot()
                layout.setArea(Rect(Offset.Zero, coordinates.size.toSize()))
            }
            .sheetBackdrop(sheetState, Color(0xFF121212))
            .draggable(
                sheetDrag, Orientation.Vertical,
                onDragStopped = { v -> if (selectedTab == TAB_PLAYER) { if (!editMode) onSheetDragEnd(v) } else if (v > 700f) closeFullscreen() },
            )
    ) {
        val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
        // Hero height: the user's own value wins. 0 = automatic (the old rule).
        val heroHeight = if (layout.heroHeight > 0f) {
            (maxHeight * layout.heroHeight).coerceIn(120.dp, maxHeight * 0.9f)
        } else {
            minOf(maxHeight * HERO_HEIGHT_FRACTION, maxHeight - CONTROLS_RESERVED)
                .coerceIn(HERO_MIN_HEIGHT, HERO_MAX_HEIGHT)
        }
        val artBlock = layout.state(PlayerBlock.Artwork)
        val heroCornerDp = layout.heroCorner.dp
        val bottomFade = layout.heroBottomFade
        val sideFeather = layout.heroSideFeather
        val sourceBlock = layout.state(PlayerBlock.SourceLabel)
        val audioBlock = layout.state(PlayerBlock.AudioInfo)
        val screenWidthPx = with(density) { maxWidth.toPx() }
        val seam by animateDpAsState(
            targetValue = if (selectedTab == TAB_PLAYER) heroHeight else 0.dp,
            animationSpec = tween(240, easing = EmphasizedDecelerate),
            label = "seam",
        )

        val meshFill = androidx.compose.animation.core.animateFloatAsState(
            targetValue = if (selectedTab == TAB_PLAYER) 0f else 1f,
            animationSpec = tween(280, easing = EmphasizedDecelerate),
            label = "meshFill",
        )
        ArtworkMeshBackdrop(
            fill = { meshFill.value },
            artworkUrl = artworkUrl,
            fallbackUrls = artworkCandidates.drop(1),
            modifier = Modifier.fillMaxSize().zIndex(0f).sheetStage(sheetState, 0f, 0.45f).hazeSource(editHaze),
            seam = seam,
            blurRadius = 58.dp,
            onAccentColor = { color ->
                updatePlayerArtworkAccent(color)
            },
        )

        // ── Artwork + top glass ──────────────────────────────────────────────
        AnimatedVisibility(
            visible = selectedTab == TAB_PLAYER,
            modifier = Modifier.fillMaxWidth().height(heroHeight).align(Alignment.TopCenter).zIndex(1f).hazeSource(editHaze),
            enter = fadeIn(tween(170)),
            exit = fadeOut(tween(120)),
        ) {
            Box(Modifier.fillMaxSize()) {
                // Only the picture follows the finger; the header stays put.
                // Outer layer: the cover flies between the mini cover and the hero.
                if (artBlock.visible) Box(
                    Modifier.fillMaxSize().coverFlight(sheetState, with(density) { 12.dp.toPx() })
                ) {
                    Box(
                        Modifier
                            .align(Alignment.TopCenter)
                            .fillMaxHeight()
                            .fillMaxWidth(layout.heroWidth.coerceIn(0.4f, 1f))
                            .editableBlock(layout, PlayerBlock.Artwork)
                            .graphicsLayer {
                                // Read drag/settle state in the layer block so pointer
                                // movement invalidates only this layer, not PlayerScreen.
                                val offsetX = if (isSwipingArtwork) swipeDragOffset else swipeSettle.value
                                translationX = offsetX
                                alpha = 1f - SWIPE_FADE *
                                        (abs(offsetX) / (screenWidthPx * 0.5f)).coerceIn(0f, 1f)
                            }
                    ) {
                        Crossfade(
                            targetState = thumbnailUrl,
                            animationSpec = tween(ARTWORK_CROSSFADE_MS),
                            label = "artwork",
                        ) { raw ->
                            if (!raw.isNullOrBlank()) {
                                PremiumArtwork(
                                    candidates = artworkUrlCandidates(raw),
                                    contentDescription = "Album artwork",
                                    modifier = Modifier.fillMaxSize()
                                        .clip(RoundedCornerShape(heroCornerDp))
                                        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                                        .drawWithContent {
                                            drawContent()

                                            // Preserve the existing lower dissolve into the mesh.
                                            drawRect(
                                                Brush.verticalGradient(
                                                    0f to Color.White,
                                                    0.42f to Color.White,
                                                    0.55f to fadeWhite(0.95f, bottomFade),
                                                    0.67f to fadeWhite(0.75f, bottomFade),
                                                    0.79f to fadeWhite(0.42f, bottomFade),
                                                    0.91f to fadeWhite(0.12f, bottomFade),
                                                    1f to fadeWhite(0f, bottomFade)
                                                ),
                                                blendMode = BlendMode.DstIn,
                                            )

                                            // Feather both vertical edges so the cover melts
                                            // into the blurred artwork backdrop instead of
                                            // ending in a hard, rectangular boundary.
                                            drawRect(
                                                Brush.horizontalGradient(
                                                    colorStops = arrayOf(
                                                        0f to fadeWhite(0f, sideFeather),
                                                        0.045f to fadeWhite(0.28f, sideFeather),
                                                        0.11f to fadeWhite(0.82f, sideFeather),
                                                        0.18f to Color.White,
                                                        0.82f to Color.White,
                                                        0.89f to fadeWhite(0.82f, sideFeather),
                                                        0.955f to fadeWhite(0.28f, sideFeather),
                                                        1f to fadeWhite(0f, sideFeather),
                                                    )
                                                ),
                                                blendMode = BlendMode.DstIn,
                                            )
                                        }
                                )
                            } else {
                                Box(Modifier.fillMaxSize().background(Color(0xFF302126)), contentAlignment = Alignment.Center) {
                                    Text("♪", color = Color.White.copy(alpha = 0.75f), fontSize = 68.sp)
                                }
                            }
                        }
                    }
                } // end coverFlight
                // Header glass, handle, source label and audio info: fade in late, fade out early.
                Box(Modifier.fillMaxSize().sheetStage(sheetState, 0.55f, 1f)) {
                    Box(
                        Modifier.fillMaxWidth().height(statusTop + 90.dp).align(Alignment.TopCenter).background(
                            Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.5f), 0.55f to Color.Black.copy(alpha = 0.2f), 1f to Color.Transparent)
                        )
                    )
                    Column(
                        Modifier.fillMaxWidth().align(Alignment.TopCenter).padding(top = statusTop + 10.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box(Modifier.width(38.dp).height(5.dp).clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.55f)))
                        Spacer(Modifier.height(8.dp))
                        Text(
                            sourceLabel, color = Color.White.copy(alpha = 0.92f),
                            fontSize = (12f * sourceBlock.textScale).sp, fontWeight = FontWeight.SemiBold,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.editableBlock(layout, PlayerBlock.SourceLabel),
                        )
                    }
                    if (!editMode) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(top = statusTop + 10.dp, end = 16.dp)
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(Color.White.copy(alpha = 0.12f))
                                .clickable {
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    editMode = true
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Edit,
                                contentDescription = "Edit player layout",
                                tint = Color.White.copy(alpha = 0.92f),
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                    if (!audioInfo.isNullOrBlank()) {
                        // Codec / audio-quality label is independently editable.
                        // Keep the outer Box responsible for its original bottom
                        // anchoring; the inner wrap-content Box is the actual block
                        // registered with the editor, so its frame hugs the text.
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(bottom = 18.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Box(
                                modifier = Modifier
                                    .editableBlock(layout, PlayerBlock.AudioInfo)
                                    .clip(RoundedCornerShape(50)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = audioInfo,
                                    color = Color.White.copy(alpha = 0.88f),
                                    fontSize = (10f * audioBlock.textScale).sp,
                                    fontWeight = FontWeight.Medium,
                                    letterSpacing = 0.2.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                } // end chrome
            }
        }

        // ── Fullscreen lyrics: song + thumbnail stay visible at the top ──────
        AnimatedVisibility(
            visible = selectedTab == TAB_LYRICS && immersive,
            modifier = Modifier.align(Alignment.TopCenter).zIndex(4f),
            enter = fadeIn(tween(140)),
            exit = fadeOut(tween(110)),
        ) {
            Row(
                Modifier.fillMaxWidth()
                    .background(Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.6f), 1f to Color.Transparent))
                    .clickable { immersive = false }
                    .padding(top = statusTop + 8.dp, start = SIDE_PADDING, end = SIDE_PADDING, bottom = 22.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PremiumArtwork(
                    candidates = artworkCandidates, contentDescription = null,
                    modifier = Modifier.size(46.dp).clip(RoundedCornerShape(8.dp)).background(Color.White.copy(alpha = 0.12f)),
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        playbackState.title.ifBlank { "Nothing playing" },
                        color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    if (artist.isNotBlank()) {
                        Text(
                            artist, color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp, fontWeight = FontWeight.Medium,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }

        // ── Edit mode bar ────────────────────────────────────────────────────
        AnimatedVisibility(
            visible = editMode && selectedTab == TAB_PLAYER,
            modifier = Modifier.align(Alignment.TopEnd).zIndex(6f),
            enter = fadeIn(tween(140)),
            exit = fadeOut(tween(110)),
        ) {
            LayoutEditBar(layout = layout, statusTop = statusTop, onDone = { layout.save(); editMode = false })
        }

        // ── Inspector: pick any part, then size / text / icon / hide / hero shape ──
        if (editMode && selectedTab == TAB_PLAYER) {
            PlayerInspector(
                layout = layout,
                statusTop = statusTop,
                modifier = Modifier.fillMaxSize().zIndex(7f),
                haze = editHaze,
            )
        }

        // ── Main UI stack ────────────────────────────────────────────────────
        // Title, lyric line and controls rise up and fade in while the sheet opens.
        Column(
            Modifier.fillMaxSize().zIndex(3f)
                .sheetStage(sheetState, 0.2f, 1f, risePx = with(density) { 56.dp.toPx() })
                .hazeSource(editHaze)
                .navigationBarsPadding()
        ) {

            Box(
                Modifier.weight(1f).fillMaxWidth()
                    .then(if (selectedTab != TAB_PLAYER) Modifier.nestedScroll(closeConnection) else Modifier)
            ) {
                AnimatedContent(
                    targetState = selectedTab,
                    modifier = Modifier.fillMaxSize(),
                    label = "tab",
                    transitionSpec = {
                        // Keep tab changes short and avoid animating layout size.
                        // A small directional movement gives feedback without making
                        // the lyrics/queue content feel like it is floating.
                        val entering = fadeIn(tween(150)) +
                                slideInVertically(tween(210, easing = EmphasizedDecelerate)) { (it * 0.035f).toInt() }
                        val leaving = fadeOut(tween(105)) +
                                slideOutVertically(tween(150, easing = EmphasizedAccelerate)) { (it * 0.02f).toInt() }
                        entering togetherWith leaving
                    },
                ) { tab ->
                    when (tab) {
                        TAB_PLAYER -> Column(Modifier.fillMaxSize()) {

                            // Empty space over the artwork. It is the swipe surface for track changes.
                            Box(
                                Modifier.weight(1f).fillMaxWidth()
                                    .then(if (editMode) Modifier else skipSwipe.then(enterEdit))
                            )

                            // Title, artist and the two chips. Each one is its own block:
                            // move, resize, resize text, hide - all separately.
                            val titleB = layout.state(PlayerBlock.Title)
                            val artistB = layout.state(PlayerBlock.Artist)
                            val favB = layout.state(PlayerBlock.Favorite)
                            val moreB = layout.state(PlayerBlock.More)
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = SIDE_PADDING),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    var titleScrolls by remember { mutableStateOf(false) }
                                    Crossfade(
                                        targetState = playbackState.title to artist,
                                        animationSpec = tween(300),
                                        label = "credits",
                                    ) { (title, art) ->
                                        Column {
                                            Row(
                                                Modifier.editableBlock(layout, PlayerBlock.Title),
                                                verticalAlignment = Alignment.CenterVertically,
                                            ) {
                                                com.example.chord.ui.components.ExplicitMark(
                                                    com.example.chord.data.explicit.ExplicitStore.has(
                                                        playbackState.queue.getOrNull(playbackState.currentQueueIndex)?.videoId
                                                    ),
                                                    gap = 6.dp,
                                                )
                                                MarqueeText(
                                                    text = title.ifBlank { "Nothing playing" },
                                                    style = TextStyle(
                                                        fontSize = (21f * titleB.textScale).sp,
                                                        fontWeight = FontWeight.SemiBold,
                                                        letterSpacing = (-0.4).sp
                                                    ),
                                                    color = Color.White,
                                                    onScrollingChange = { titleScrolls = it },
                                                )
                                            }
                                            Row(
                                                Modifier.editableBlock(layout, PlayerBlock.Artist),
                                                verticalAlignment = Alignment.CenterVertically,
                                            ) {
                                                MarqueeText(
                                                    text = art.ifBlank { if (buffering) "Loading audio…" else " " },
                                                    style = TextStyle(fontSize = (18f * artistB.textScale).sp, fontWeight = FontWeight.Normal),
                                                    color = Color.White.copy(alpha = ARTIST_ALPHA),
                                                    modifier = Modifier.weight(1f),
                                                    startDelayMs = MARQUEE_START_DELAY_MS + if (titleScrolls) ARTIST_STAGGER_MS else 0L,
                                                )
                                            }
                                        }
                                    }
                                }
                                Spacer(Modifier.width(12.dp))
                                ActionChip(
                                    icon = if (extras.isFavorite) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                                    desc = "Favourite",
                                    tint = if (extras.isFavorite) ChordTheme.accent else Color.White,
                                    haptic = if (extras.isFavorite) Haptic.ToggleOff else Haptic.ToggleOn,
                                    onClick = extras.onToggleFavorite,
                                    scale = favB.iconScale,
                                    modifier = Modifier.editableBlock(layout, PlayerBlock.Favorite),
                                )
                                Spacer(Modifier.width(8.dp))
                                var songMenu by remember { mutableStateOf(false) }
                                Box {
                                    ActionChip(
                                        Icons.Rounded.MoreVert, "More",
                                        onClick = { songMenu = true },
                                        scale = moreB.iconScale,
                                        modifier = Modifier.editableBlock(layout, PlayerBlock.More),
                                    )
                                    playbackState.queue.getOrNull(playbackState.currentQueueIndex)
                                        ?.takeIf { it.videoId.isNotBlank() }
                                        ?.let { cur ->
                                            com.example.chord.ui.components.SongMenu(
                                                expanded = songMenu,
                                                onDismiss = { songMenu = false },
                                                song = cur,
                                                onPlay = {},
                                                showPlay = false,
                                                onAddToPlaylist = { extras.onMore() },
                                                onOpenArtist = null,
                                                artist = cur.details,
                                            )
                                        }
                                }
                            }

                            val lyricsB = layout.state(PlayerBlock.Lyrics)
                            if (lyricsB.visible || editMode) {
                                Spacer(Modifier.height(14.dp))

                                // The current lyric: one line, right above the seek bar.
                                LyricStrip(
                                    rawLyrics = syncedLyrics,
                                    loading = isLoadingLyrics,
                                    positionMs = position,
                                    isPlaying = playbackState.isPlaying,
                                    onOpen = { selectedTab = TAB_LYRICS },
                                    textScale = lyricsB.textScale,
                                    modifier = Modifier.padding(horizontal = SIDE_PADDING)
                                        .editableBlock(layout, PlayerBlock.Lyrics),
                                )
                            }
                        }

                        TAB_LYRICS -> LyricsTab(
                            title = playbackState.title,
                            artist = artist,
                            thumbnailUrl = artworkUrl,
                            lyrics = syncedLyrics,
                            isLoading = isLoadingLyrics,
                            position = position,
                            isPlaying = playbackState.isPlaying,
                            onSeek = onSeek,
                            extras = extras,
                            modifier = Modifier.fillMaxSize(),
                            immersive = immersive,
                            onImmersiveChange = { immersive = it },
                            plainLyrics = plainLyrics,
                            song = playbackState.queue.getOrNull(playbackState.currentQueueIndex),
                        )

                        else -> QueueTab(
                            playbackState.title,
                            artist,
                            thumbArtworkUrl(thumbnailUrl, 300),
                            queueRows,
                            playbackState.currentQueueIndex,
                            onPlayQueueItem,
                            extras,
                            Modifier.fillMaxSize(),
                        )
                    }
                }
            }

            // ── Bottom area (playback controls) ──────────────────────────────
            AnimatedVisibility(
                visible = !immersive,
                enter = fadeIn(tween(150)),
                exit = fadeOut(tween(110)),
            ) {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 15.dp),
                    contentAlignment = Alignment.BottomCenter
                ) {
                    Column(Modifier.fillMaxWidth()) {
                        val seekB = layout.state(PlayerBlock.Seek)
                        Column(
                            Modifier.padding(horizontal = SIDE_PADDING)
                                .editableBlock(layout, PlayerBlock.Seek)
                        ) {
                            SeekSection(
                                progress = progress, shownPosition = shownPosition, duration = duration,
                                enabled = duration > 0L && playbackState.canSeek,
                                onScrub = { dragFraction = it },
                                onScrubEnd = { dragFraction?.let { onSeek((it * duration).toLong()) }; dragFraction = null },
                                badge = if (isLossless) "≋ Lossless" else null,
                                upgrading = upgrading,
                                textScale = seekB.textScale,
                                thickness = seekB.iconScale,
                            )
                        }
                        // Previous / play / next are three blocks, not one.
                        // Hidden buttons keep their room (collapse = false) so the
                        // other two do not jump sideways.
                        val prevB = layout.state(PlayerBlock.Previous)
                        val playB = layout.state(PlayerBlock.PlayPause)
                        val nextB = layout.state(PlayerBlock.Next)
                        TransportControls(
                            isPlaying = playbackState.isPlaying, buffering = buffering,
                            onPrevious = onPrevious, onTogglePlayPause = onTogglePlayPause, onNext = onNext,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                            prevModifier = Modifier.editableBlock(layout, PlayerBlock.Previous, collapse = false),
                            playModifier = Modifier.editableBlock(layout, PlayerBlock.PlayPause, collapse = false),
                            nextModifier = Modifier.editableBlock(layout, PlayerBlock.Next, collapse = false),
                            prevScale = prevB.iconScale,
                            playScale = playB.iconScale,
                            nextScale = nextB.iconScale,
                        )
                        // Volume is a sibling block: the whole row (icons + slider) moves together.
                        val volB = layout.state(PlayerBlock.Volume)
                        Column(
                            Modifier.fillMaxWidth()
                                .padding(horizontal = SIDE_PADDING)
                                .editableBlock(layout, PlayerBlock.Volume)
                        ) {
                            VolumeRow(
                                fraction = volume / maxVolume,
                                onChange = { volumeDragging = true; applyVolume(it * maxVolume) },
                                onFinished = { volumeDragging = false },
                                onMute = { applyVolume(0f) }, onMax = { applyVolume(maxVolume.toFloat()) },
                                iconScale = volB.iconScale,
                            )
                        }
                        Spacer(Modifier.height(1.dp))
                        val lyrBtnB = layout.state(PlayerBlock.LyricsBtn)
                        val pillB = layout.state(PlayerBlock.OutputPill)
                        val queueBtnB = layout.state(PlayerBlock.QueueBtn)
                        Crossfade(
                            targetState = selectedTab == TAB_QUEUE,
                            animationSpec = tween(240),
                            modifier = Modifier
                                .fillMaxWidth()
                                .animateContentSize(tween(240)),
                            label = "bottomBar",
                        ) { isQueue ->
                            if (isQueue) QueueModeBar(extras) { selectedTab = TAB_PLAYER }
                            else PlayerBottomBar(
                                selectedTab = selectedTab,
                                onLyrics = { selectedTab = if (selectedTab == TAB_LYRICS) TAB_PLAYER else TAB_LYRICS },
                                onQueue = { selectedTab = if (selectedTab == TAB_QUEUE) TAB_PLAYER else TAB_QUEUE },
                                onOutput = { openAudioOutput(context) },
                                lyricsModifier = Modifier.editableBlock(layout, PlayerBlock.LyricsBtn, collapse = false),
                                pillModifier = Modifier.editableBlock(layout, PlayerBlock.OutputPill, collapse = false),
                                queueModifier = Modifier.editableBlock(layout, PlayerBlock.QueueBtn, collapse = false),
                                lyricsScale = lyrBtnB.iconScale,
                                pillScale = pillB.iconScale,
                                queueScale = queueBtnB.iconScale,
                            )
                        }
                        // Center the label in the available width, but attach the
                        // editable transform to a wrap-content box around the text.
                        // This gives PlayerEditOverlay a real, tight hit-test rectangle.
                        Box(
                            modifier = Modifier.fillMaxWidth(),
                            contentAlignment = Alignment.Center,
                        ) {
                            Box(
                                modifier = Modifier
                                    .editableBlock(layout, PlayerBlock.DeviceName)
                                    .padding(bottom = 1.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = deviceName,
                                    color = Color.White.copy(alpha = 0.5f),
                                    fontSize = (12f * layout.state(PlayerBlock.DeviceName).textScale).sp,
                                    fontWeight = FontWeight.Normal,
                                    textAlign = TextAlign.Center,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        }

        // The edit overlay is the single gesture owner while editing. It is
        // drawn above every player component so drag/resize/swap hit-testing
        // always uses the same root-coordinate space as the registered blocks.
        if (editMode && selectedTab == TAB_PLAYER) {
            PlayerEditOverlay(
                layout = layout,
                statusTop = statusTop,
                modifier = Modifier.fillMaxSize().zIndex(5f),
            )
        }
    }
}

/** White with its see-through part scaled by [strength]. 0 = solid white, 1 = [alpha]. */
private fun fadeWhite(alpha: Float, strength: Float): Color =
    Color.White.copy(alpha = 1f - strength.coerceIn(0f, 1f) * (1f - alpha))

/* ════════════════════════════════════════════════════════════════════════════
 *  Chips
 * ════════════════════════════════════════════════════════════════════════════ */

@Composable
private fun ActionChip(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    desc: String,
    tint: Color = Color.White,
    haptic: Haptic = Haptic.Tap,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    scale: Float = 1f,
) {
    Box(
        modifier.size(34.dp * scale).clip(CircleShape).background(Color.White.copy(alpha = 0.16f)).hapticClickable(haptic, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, desc, tint = tint, modifier = Modifier.size(19.dp * scale)) }
}

/* ════════════════════════════════════════════════════════════════════════════
 *  Marquee — a single line that loops only when it does not fit.
 *
 *  The text is drawn twice with a gap and the pair slides left by exactly one
 *  copy + gap, so the second copy lands where the first started and the reset
 *  is invisible. Text that fits never animates.
 * ════════════════════════════════════════════════════════════════════════════ */

@Composable
private fun MarqueeText(
    text: String,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    startDelayMs: Long = MARQUEE_START_DELAY_MS,
    onScrollingChange: (Boolean) -> Unit = {},
) {
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    // Merge with the theme style so measuring and drawing use the same font.
    val resolved = LocalTextStyle.current.merge(style)

    BoxWithConstraints(modifier.fillMaxWidth().clipToBounds()) {
        val maxWidthPx = constraints.maxWidth
        val textWidthPx = remember(text, resolved) {
            measurer.measure(text, resolved, maxLines = 1, softWrap = false).size.width
        }
        val overflowing = textWidthPx > maxWidthPx
        LaunchedEffect(overflowing) { onScrollingChange(overflowing) }

        val travelPx = if (overflowing) textWidthPx + with(density) { MARQUEE_GAP.roundToPx() } else 0
        val offsetX = remember { Animatable(0f) }

        LaunchedEffect(text, travelPx, startDelayMs) {
            offsetX.snapTo(0f)
            if (travelPx <= 0) return@LaunchedEffect
            val pxPerMs = with(density) { MARQUEE_DP_PER_SEC.dp.toPx() } / 1000f
            val passMs = (travelPx / pxPerMs).roundToInt().coerceAtLeast(400)
            delay(startDelayMs)
            while (true) {
                offsetX.animateTo(-travelPx.toFloat(), tween(passMs, easing = LinearEasing))
                offsetX.snapTo(0f)
                delay(MARQUEE_REST_MS)
            }
        }

        Row(
            Modifier
                .wrapContentWidth(align = Alignment.Start, unbounded = true)
                .offset { IntOffset(offsetX.value.roundToInt(), 0) },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text, style = resolved, color = color, maxLines = 1, softWrap = false, overflow = TextOverflow.Clip)
            if (overflowing) {
                Spacer(Modifier.width(MARQUEE_GAP))
                Text(text, style = resolved, color = color, maxLines = 1, softWrap = false, overflow = TextOverflow.Clip)
            }
        }
    }
}

/* ════════════════════════════════════════════════════════════════════════════
 *  Lyric strip — the one line above the seek bar.
 *
 *  • Always exactly one line, left aligned, swept word by word.
 *  • Runs on its own per-frame clock so it never waits for the next
 *    position update from the player (that is what caused the delay).
 *  • A line too long for the screen scrolls sideways to keep the sweep in view.
 *  • Tap opens the full lyrics tab.
 * ════════════════════════════════════════════════════════════════════════════ */

@Composable
private fun LyricStrip(
    rawLyrics: String?,
    loading: Boolean,
    positionMs: Long,
    isPlaying: Boolean,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    textScale: Float = 1f,
) {
    val stripHeight = LYRIC_STRIP_HEIGHT * textScale
    val lines = remember(rawLyrics) {
        if (rawLyrics.isNullOrBlank()) emptyList() else WordLyricsParser.parse(rawLyrics)
    }

    if (lines.isEmpty()) {
        Row(
            modifier.fillMaxWidth().height(stripHeight).clip(RoundedCornerShape(8.dp)).clickable(onClick = onOpen),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("♫", color = Color.White.copy(alpha = 0.86f), fontSize = (15f * textScale).sp)
            Spacer(Modifier.width(8.dp))
            Text(
                if (loading) "Loading lyrics…" else "Lyrics",
                color = Color.White.copy(alpha = 0.66f), fontSize = (15f * textScale).sp, fontWeight = FontWeight.Medium,
            )
            Text("›", color = Color.White.copy(alpha = 0.6f), fontSize = (22f * textScale).sp, modifier = Modifier.padding(start = 8.dp))
        }
        return
    }

    val clock = rememberLyricClock(rawLyrics, positionMs, isPlaying)
    val index by remember(lines) {
        derivedStateOf { lines.indexOfLast { it.lineStartMs <= clock.longValue + LYRIC_LEAD_MS } }
    }

    Row(
        modifier.fillMaxWidth().height(stripHeight).clip(RoundedCornerShape(8.dp)).clickable(onClick = onOpen),
        verticalAlignment = Alignment.CenterVertically,
    ) {
//        Text("♫", color = Color.White.copy(alpha = 1f), fontSize = (20f * textScale).sp, textAlign = TextAlign.Center)
//        Spacer(Modifier.width(8.dp))

// Use fill = false so the Box only takes as much width as the lyric text needs
        Box(Modifier.weight(1f, fill = false), contentAlignment = Alignment.CenterStart) {
            Crossfade(targetState = index, animationSpec = tween(LYRIC_FADE_MS), label = "lyricLine") { i ->

                // Wrap purely for UI alignment so the arrow hugs the end of SweepLine
                Row(verticalAlignment = Alignment.CenterVertically) {

                    val line = lines.getOrNull(i)

                    // Your exact logic, untouched
                    if(line != null){
                        SweepLine(line = line, clock = clock, textScale = textScale, modifier = Modifier.weight(1f, fill = false))
                    }

                    // The arrow is now inline, immediately following the SweepLine
                    Text("›", color = Color.White.copy(alpha = 1f), fontSize = (20f * textScale).sp, modifier = Modifier.padding(start = 8.dp))
                }
            }
        }
    }
}

/**
 * A playback clock that ticks every frame. The player only reports its position
 * a couple of times a second; between reports this extrapolates, and each new
 * report re-anchors it. A seek (big jump) snaps it straight there.
 */
@Composable
private fun rememberLyricClock(key: Any?, positionMs: Long, isPlaying: Boolean): MutableLongState {
    val clock = remember(key) { mutableLongStateOf(positionMs) }
    val anchor = remember(key) { longArrayOf(positionMs, System.nanoTime()) }

    LaunchedEffect(key, positionMs) {
        anchor[0] = positionMs
        anchor[1] = System.nanoTime()
        val jumped = abs(positionMs - clock.longValue) > 1_200L
        if (!isPlaying || jumped) clock.longValue = positionMs
    }

    LaunchedEffect(key, isPlaying) {
        if (!isPlaying) return@LaunchedEffect
        anchor[0] = positionMs
        anchor[1] = System.nanoTime()
        while (true) {
            androidx.compose.runtime.withFrameNanos {
                val estimate = (anchor[0] + (System.nanoTime() - anchor[1]) / 1_000_000L)
                    .coerceAtMost(anchor[0] + 1_500L)
                // Never run backwards by a few ms; only a real seek may.
                if (estimate >= clock.longValue || clock.longValue - estimate > 400L) {
                    clock.longValue = estimate
                }
            }
        }
    }
    return clock
}

private class WordSpan(val startMs: Long, val endMs: Long, val left: Float, val right: Float)

/** One lyric line, one row, word-by-word white sweep. A line too long is cut with "…". */
@Composable
private fun SweepLine(line: EnhancedLyricLine, clock: MutableLongState, modifier: Modifier = Modifier, textScale: Float = 1f) {
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val style = LocalTextStyle.current.merge(
        TextStyle(fontSize = LYRIC_SIZE * textScale, fontWeight = FontWeight.Bold, letterSpacing = (-0.2).sp)
    )
    val text = remember(line) { line.words.joinToString(" ") { it.word } }
    if (text.isBlank()) return

    BoxWithConstraints(modifier) {
        val maxPx = constraints.maxWidth
        val layout = remember(text, style, maxPx) {
            measurer.measure(
                text, style,
                overflow = TextOverflow.Ellipsis, softWrap = false, maxLines = 1,
                constraints = Constraints(maxWidth = maxPx),
            )
        }
        // Right edge of what is really drawn, "…" included. Words cut off sit here.
        val visibleRight = layout.getLineRight(0)

        // Where each word sits horizontally inside the laid-out text.
        val spans = remember(line, layout) {
            val last = (text.length - 1).coerceAtLeast(0)
            var cursor = 0
            line.words.map { w ->
                val start = cursor
                val end = cursor + w.word.length
                cursor = end + 1
                val a = layout.getBoundingBox(start.coerceIn(0, last))
                val b = layout.getBoundingBox((end - 1).coerceIn(0, last))
                WordSpan(
                    w.startMs, w.endMs,
                    minOf(a.left, b.left).coerceAtMost(visibleRight),
                    maxOf(a.right, b.right).coerceAtMost(visibleRight),
                )
            }
        }

        val featherPx = with(density) { LYRIC_FEATHER_WIDTH.toPx() }
        val heightDp = with(density) { layout.size.height.toDp() }

        Canvas(Modifier.width(with(density) { layout.size.width.toDp() }).height(heightDp)) {
            val t = clock.longValue + LYRIC_LEAD_MS

            // Sweep position: finished words are full, the current word is partial.
            var sweep = spans.firstOrNull()?.left ?: 0f
            for (s in spans) {
                if (t >= s.endMs) {
                    sweep = s.right
                } else if (t >= s.startMs) {
                    val p = (t - s.startMs).toFloat() / (s.endMs - s.startMs).coerceAtLeast(1L)
                    sweep = s.left + (s.right - s.left) * p
                    break
                } else {
                    break
                }
            }

            drawText(layout, color = Color.White.copy(alpha = LYRIC_DIM_ALPHA), topLeft = Offset.Zero)
            clipRect(left = sweep, right = sweep + featherPx) {
                drawText(layout, color = Color.White.copy(alpha = LYRIC_FEATHER_ALPHA), topLeft = Offset.Zero)
            }
            clipRect(left = 0f, right = sweep) {
                drawText(layout, color = Color.White, topLeft = Offset.Zero)
            }
        }
    }
}

/**
 * Full quality artwork. Loads at original size (no downscale). If a URL fails,
 * the next candidate is tried, so the cover never goes blank.
 */
@Composable
private fun PremiumArtwork(
    candidates: List<String>,
    contentDescription: String?,
    modifier: Modifier = Modifier,
) {
    var index by remember(candidates) { mutableIntStateOf(0) }
    val url = candidates.getOrNull(index) ?: return
    val context = LocalContext.current
    val request = remember(url) {
        ImageRequest.Builder(context)
            .data(url)
            .size(Size.ORIGINAL)
            .precision(Precision.EXACT)
            .crossfade(true)
            .build()
    }
    AsyncImage(
        model = request,
        contentDescription = contentDescription,
        contentScale = ContentScale.Crop,
        filterQuality = FilterQuality.High,
        onError = { if (index < candidates.lastIndex) index++ },
        modifier = modifier,
    )
}