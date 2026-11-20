package com.example.chord.ui.components

import com.example.chord.ui.haptics.Haptic
import com.example.chord.ui.haptics.rememberHaptics
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.chord.ui.player.highResolutionArtworkUrl
import com.kyant.backdrop.backdrops.LayerBackdrop
import dev.chrisbanes.haze.HazeState

private val MiniPlayerShape = RoundedCornerShape(30.dp)

@Composable
fun MiniPlayer(
    title: String,
    details: String,
    thumbnailUrl: String?,
    isPlaying: Boolean,
    isLoading: Boolean,
    onTogglePlayPause: () -> Unit,
    onOpenPlayer: () -> Unit,
    modifier: Modifier = Modifier,
    onNext: (() -> Unit)? = null,
    onPrevious: (() -> Unit)? = null,
    progress: () -> Float = { 0f },
    hazeState: HazeState? = null,
    /** Needed only for true liquid glass. null = hybrid glass. */
    backdrop: LayerBackdrop? = null,
    onVerticalDragStart: () -> Unit = {},
    onVerticalDrag: (Float) -> Unit = {},
    /** Gets the fling speed in px/s. Negative = up. */
    onVerticalDragEnd: (Float) -> Unit = {},
    /** Cover position in the root. The player sheet flies its cover from / to here. */
    onCoverBounds: (androidx.compose.ui.geometry.Rect) -> Unit = {},
    /** false while the big player owns the cover (it is drawn there instead). */
    coverVisible: Boolean = true,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()

    var dragX by remember { mutableFloatStateOf(0f) }
    var dragY by remember { mutableFloatStateOf(0f) }
    var verticalDragStarted by remember { mutableStateOf(false) }

    // Keep pointerInput stable when the parent recomposes. The old callback keys
    // restarted the gesture detector during sheet-state updates.
    val currentOnVerticalDragStart by rememberUpdatedState(onVerticalDragStart)
    val currentOnVerticalDrag by rememberUpdatedState(onVerticalDrag)
    val currentOnVerticalDragEnd by rememberUpdatedState(onVerticalDragEnd)
    val currentOnNext by rememberUpdatedState(onNext)
    val currentOnPrevious by rememberUpdatedState(onPrevious)

    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.975f else 1f,
        animationSpec = tween(140),
        label = "miniScale",
    )

    val highResUrl = remember(thumbnailUrl) {
        com.example.chord.ui.player.thumbArtworkUrl(thumbnailUrl, 400)
    }

    val haptics = rememberHaptics()

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(72.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = dragX * 0.35f
                // Do not reset the mini-player's Y translation at the instant
                // the sheet takes over; that caused a visible one-frame snap.
                translationY = 0f
            }
            .pointerInput(Unit) {
                val tracker = VelocityTracker()
                detectDragGestures(
                    onDragStart = {
                        tracker.resetTracking()
                        dragX = 0f
                        dragY = 0f
                        verticalDragStarted = false
                    },
                    onDragEnd = {
                        if (verticalDragStarted) {
                            currentOnVerticalDragEnd(tracker.calculateVelocity().y)
                        } else if (kotlin.math.abs(dragX) > 120f &&
                            kotlin.math.abs(dragX) > kotlin.math.abs(dragY)
                        ) {
                            if (dragX < 0f) {
                                haptics.perform(Haptic.Confirm)
                                currentOnNext?.invoke()
                            } else {
                                haptics.perform(Haptic.Confirm)
                                currentOnPrevious?.invoke()
                            }
                        }
                        dragX = 0f
                        dragY = 0f
                        verticalDragStarted = false
                    },
                    onDragCancel = {
                        if (verticalDragStarted) currentOnVerticalDragEnd(0f)
                        dragX = 0f
                        dragY = 0f
                        verticalDragStarted = false
                    },
                    onDrag = { change, amount ->
                        change.consume()
                        dragX += amount.x
                        dragY += amount.y
                        tracker.addPosition(change.uptimeMillis, Offset(dragX, dragY))

                        if (verticalDragStarted) {
                            // Sheet owns the gesture now. Follow the finger up and down.
                            currentOnVerticalDrag(amount.y)
                        } else if (kotlin.math.abs(dragY) > kotlin.math.abs(dragX) && dragY < 0f) {
                            verticalDragStarted = true
                            haptics.perform(Haptic.Heavy)
                            currentOnVerticalDragStart()
                            // Send what was dragged so far, so the sheet is exactly under the finger.
                            currentOnVerticalDrag(dragY)
                        }
                    },
                )
            }
            .chordGlass(hazeState, MiniPlayerShape, backdrop = backdrop)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = {
                    haptics.perform(Haptic.Heavy)
                    onOpenPlayer()
                },
            ),
    ) {
        Row(
            Modifier
                .fillMaxSize()
                .padding(start = 10.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AsyncImage(
                model = highResUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(48.dp)
                    .onGloballyPositioned { c ->
                        // Only a clean, untouched position counts.
                        if (scale == 1f && dragX == 0f && dragY == 0f) onCoverBounds(c.boundsInRoot())
                    }
                    .graphicsLayer { alpha = if (coverVisible) 1f else 0f }
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.White.copy(alpha = 0.12f)),
            )

            Spacer(Modifier.width(12.dp))

            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = (-0.2).sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )

                if (details.isNotBlank()) {
                    Text(
                        details,
                        color = Color.White.copy(alpha = 0.65f),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Box(
                Modifier
                    .size(50.dp)
                    .clip(CircleShape)
                    .clickable {
                        haptics.perform(Haptic.Tap)
                        onTogglePlayPause()
                    },
                contentAlignment = Alignment.Center,
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        Modifier.size(24.dp),
                        color = Color.White,
                        strokeWidth = 2.dp,
                    )
                } else {
                    Icon(
                        imageVector = if (isPlaying) {
                            Icons.Rounded.Pause
                        } else {
                            Icons.Rounded.PlayArrow
                        },
                        contentDescription = if (isPlaying) "Pause" else "Play",
                        tint = Color.White,
                        modifier = Modifier.size(34.dp),
                    )
                }
            }

            if (onNext != null) {
                Box(
                    Modifier
                        .size(50.dp)
                        .clip(CircleShape)
                        .clickable {
                            haptics.perform(Haptic.Tap)
                            onNext()
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Rounded.SkipNext,
                        contentDescription = "Next",
                        tint = Color.White.copy(alpha = 0.9f),
                        modifier = Modifier.size(34.dp),
                    )
                }
            }
        }

        // Progress hairline
        Box(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .height(2.dp)
                .drawBehind {
                    drawRect(
                        Color.White.copy(alpha = 0.88f),
                        size = androidx.compose.ui.geometry.Size(
                            size.width * progress().coerceIn(0f, 1f),
                            size.height,
                        ),
                    )
                },
        )
    }
}