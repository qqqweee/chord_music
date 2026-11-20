
package com.example.chord.ui.player

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.FormatListBulleted
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material.icons.rounded.VolumeDown
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.chord.R
import com.example.chord.ui.haptics.Haptic
import com.example.chord.ui.haptics.hapticClickable
import com.example.chord.ui.haptics.rememberHaptics

const val TAB_PLAYER = 0
const val TAB_LYRICS = 1
const val TAB_QUEUE = 2

// Updated whenever ArtworkMeshBackdrop loads a new song's artwork.
var PlayerArtworkAccent by mutableStateOf(Color.White)
    private set

internal fun updatePlayerArtworkAccent(color: Color) {
    PlayerArtworkAccent = color
}

/** Keeps artwork colour present without making every control look painted. */
private fun artworkTint(amount: Float): Color {
    val t = amount.coerceIn(0f, 1f)
    val base = Color.White
    val artwork = PlayerArtworkAccent

    return Color(
        red = base.red + (artwork.red - base.red) * t,
        green = base.green + (artwork.green - base.green) * t,
        blue = base.blue + (artwork.blue - base.blue) * t,
        alpha = 1f
    )
}

@Composable
fun AppleSlider(
    fraction: Float,
    onChange: (Float) -> Unit,
    onFinished: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    restHeight: Dp = 6.dp,
    pressedHeight: Dp = 12.dp,
    activeColor: Color = PlayerArtworkAccent,
    trackColor: Color = Color.White.copy(alpha = 0.28f),
    onPressChange: (Boolean) -> Unit = {},
) {
    val haptics = rememberHaptics()
    var pressed by remember { mutableStateOf(false) }

    val barHeight by animateDpAsState(
        if (pressed) pressedHeight else restHeight,
        spring(
            dampingRatio = 0.7f,
            stiffness = Spring.StiffnessMedium
        ),
        label = "sliderHeight"
    )

    val update by rememberUpdatedState(onChange)
    val finish by rememberUpdatedState(onFinished)
    val press by rememberUpdatedState(onPressChange)

    Box(
        modifier
            .height(maxOf(32.dp, pressedHeight + 8.dp))
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput

                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    haptics.perform(Haptic.Tick)
                    pressed = true
                    press(true)

                    update(
                        (down.position.x / size.width.coerceAtLeast(1))
                            .coerceIn(0f, 1f)
                    )

                    drag(down.id) { change ->
                        change.consume()
                        update(
                            (change.position.x / size.width.coerceAtLeast(1))
                                .coerceIn(0f, 1f)
                        )
                    }

                    pressed = false
                    press(false)
                    haptics.perform(Haptic.Tick)
                    finish()
                }
            }
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val h = barHeight.toPx()
            val top = (size.height - h) / 2f
            val radius = CornerRadius(h / 2f, h / 2f)

            drawRoundRect(
                trackColor,
                Offset(0f, top),
                Size(size.width, h),
                radius
            )

            val f = fraction.coerceIn(0f, 1f)

            if (f > 0f) {
                drawRoundRect(
                    activeColor,
                    Offset(0f, top),
                    Size((size.width * f).coerceAtLeast(h), h),
                    radius
                )
            }
        }
    }
}

@Composable
fun SeekSection(
    progress: Float,
    shownPosition: Long,
    duration: Long,
    enabled: Boolean,
    onScrub: (Float) -> Unit,
    onScrubEnd: () -> Unit,
    modifier: Modifier = Modifier,
    badge: String? = null,
    upgrading: Boolean = false,
    /** Time / badge text size multiplier. */
    textScale: Float = 1f,
    /** Bar thickness multiplier. */
    thickness: Float = 1f,
) {
    var pressed by remember { mutableStateOf(false) }

    val labelAlpha by animateFloatAsState(
        if (pressed) 0.95f else 0.55f,
        tween(150),
        label = "labelAlpha"
    )

    val label = Color.White.copy(alpha = labelAlpha)

    val accent by animateColorAsState(
        artworkTint(0.58f),
        tween(600),
        label = "seekAccent"
    )

    Column(modifier = modifier.fillMaxWidth()) {
        AppleSlider(
            fraction = progress,
            onChange = onScrub,
            onFinished = onScrubEnd,
            enabled = enabled,
            activeColor = accent,
            restHeight = 6.dp * thickness,
            pressedHeight = 12.dp * thickness,
            onPressChange = { pressed = it }
        )

        Box(Modifier.fillMaxWidth().padding(top = 0.dp)) {
            Text(
                text = formatPlayerTime(shownPosition),
                color = label,
                fontSize = (10f * textScale).sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.align(Alignment.CenterStart)
            )

            if (upgrading) {
                val a by androidx.compose.animation.core
                    .rememberInfiniteTransition(label = "up")
                    .animateFloat(
                        0.3f,
                        1f,
                        infiniteRepeatable(
                            tween(800),
                            RepeatMode.Reverse
                        ),
                        label = "upA"
                    )

                Row(
                    Modifier.align(Alignment.Center).graphicsLayer {
                        alpha = a
                    },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Rounded.GraphicEq,
                        null,
                        tint = accent.copy(alpha = 0.75f),
                        modifier = Modifier.size((16f * textScale).dp)
                    )

                    Text(
                        " Upgrading quality",
                        color = Color.White.copy(alpha = 0.55f),
                        fontSize = (12f * textScale).sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            } else {
                badge?.let {
                    Text(
                        text = it,
                        color = accent.copy(alpha = 0.9f),
                        fontSize = (10f * textScale).sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
            }

            Text(
                text = "-" + formatPlayerTime(
                    (duration - shownPosition).coerceAtLeast(0L)
                ),
                color = label,
                fontSize = (10f * textScale).sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.align(Alignment.CenterEnd)
            )
        }
    }
}

@Composable
fun TransportControls(
    isPlaying: Boolean,
    buffering: Boolean,
    onPrevious: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
    /** Each button is its own editable block: pass its editableBlock modifier + size. */
    prevModifier: Modifier = Modifier,
    playModifier: Modifier = Modifier,
    nextModifier: Modifier = Modifier,
    prevScale: Float = 1f,
    playScale: Float = 1f,
    nextScale: Float = 1f,
) {
    val sideTint = artworkTint(0.20f)

    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(
                45.dp,
                Alignment.CenterHorizontally
            ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // BitChord previous icon
            Icon(
                painter = painterResource(R.drawable.ic_player_previous),
                contentDescription = "Previous",
                tint = sideTint.copy(alpha = 0.98f),
                modifier = prevModifier
                    .size(50.dp * prevScale)
                    .hapticClickable(Haptic.Heavy, onClick = onPrevious)
            )

            Box(
                modifier = playModifier
                    .size(78.dp * playScale)
                    .clip(CircleShape)
                    .hapticClickable(
                        Haptic.Heavy,
                        onClick = onTogglePlayPause
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (buffering) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(45.dp * playScale),
                        color = Color.White,
                        strokeWidth = 2.5.dp
                    )
                } else {
                    Icon(
                        painter = painterResource(
                            if (isPlaying) {
                                R.drawable.ic_player_pause
                            } else {
                                R.drawable.ic_player_play
                            }
                        ),
                        contentDescription = if (isPlaying) "Pause" else "Play",
                        tint = sideTint.copy(alpha = .98f),
                        modifier = Modifier.size(70.dp * playScale)
                    )
                }
            }

            // BitChord next icon
            Icon(
                painter = painterResource(R.drawable.ic_player_next),
                contentDescription = "Next",
                tint = sideTint.copy(alpha = .98f),
                modifier = nextModifier
                    .size(50.dp * nextScale)
                    .hapticClickable(Haptic.Heavy, onClick = onNext)
            )
        }
    }
}

@Composable
fun VolumeRow(
    fraction: Float,
    onChange: (Float) -> Unit,
    onFinished: () -> Unit,
    onMute: () -> Unit,
    onMax: () -> Unit,
    modifier: Modifier = Modifier,
    /** Icon size multiplier. */
    iconScale: Float = 1f,
) {
    val accent by animateColorAsState(
        artworkTint(0.30f),
        tween(600),
        label = "volumeAccent"
    )

    Spacer(modifier = Modifier.height(10.dp))

    Row(
        modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Rounded.VolumeDown,
            "Quieter",
            tint = accent.copy(alpha = 0.78f),
            modifier = Modifier
                .size(28.dp * iconScale)
                .clip(CircleShape)
                .hapticClickable(Haptic.Select, onClick = onMute)
                .padding(2.dp)
        )

        AppleSlider(
            fraction,
            onChange,
            onFinished,
            Modifier.weight(1f).padding(horizontal = 12.dp),
            restHeight = 5.dp,
            pressedHeight = 10.dp,
            activeColor = accent,
            trackColor = Color.White.copy(alpha = 0.23f)
        )

        Icon(
            Icons.Rounded.VolumeUp,
            "Louder",
            tint = accent.copy(alpha = 0.92f),
            modifier = Modifier
                .size(28.dp * iconScale)
                .clip(CircleShape)
                .hapticClickable(Haptic.Select, onClick = onMax)
                .padding(2.dp)
        )
    }
}

@Composable
private fun NavCircle(
    icon: Painter,
    desc: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    scale: Float = 1f,
) {
    val accent by animateColorAsState(
        artworkTint(0.42f),
        tween(500),
        label = "navAccent"
    )

    val bg by animateColorAsState(
        Color.White.copy(alpha = if (selected) 0.13f else 0.07f),
        tween(200),
        label = "navBg"
    )

    Box(
        modifier
            .size(40.dp * scale)
            .clip(CircleShape)
            .background(bg)
            .hapticClickable(Haptic.Select, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            painter = icon,
            contentDescription = desc,
            tint = if (selected) {
                accent
            } else {
                Color.White.copy(alpha = 0.58f)
            },
            modifier = Modifier.size(22.dp * scale)
        )
    }
}

@Composable
fun PlayerBottomBar(
    selectedTab: Int,
    onLyrics: () -> Unit,
    onQueue: () -> Unit,
    onOutput: () -> Unit,
    onProfile: () -> Unit = {},
    modifier: Modifier = Modifier,
    /** Each part is its own editable block: pass its editableBlock modifier + size. */
    lyricsModifier: Modifier = Modifier,
    pillModifier: Modifier = Modifier,
    queueModifier: Modifier = Modifier,
    lyricsScale: Float = 1f,
    pillScale: Float = 1f,
    queueScale: Float = 1f,
) {
    val accent by animateColorAsState(
        artworkTint(0.24f),
        tween(550),
        label = "bottomBarArtworkAccent"
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 70.dp)
            .padding(horizontal = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(
            40.dp,
            Alignment.CenterHorizontally
        ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // BitChord lyrics bubble
        NavCircle(
            painterResource(R.drawable.ic_lyrics_bubble),
            "Lyrics",
            selectedTab == TAB_LYRICS,
            onLyrics,
            modifier = lyricsModifier,
            scale = lyricsScale,
        )

        Row(
            pillModifier
                .clip(RoundedCornerShape(30.dp * pillScale))
                .background(Color.White.copy(alpha = 0.09f)),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .width(60.dp * pillScale)
                    .height(40.dp * pillScale)
                    .hapticClickable(Haptic.Tap, onClick = onOutput),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Rounded.Smartphone,
                    "Audio output",
                    tint = accent.copy(alpha = 0.88f),
                    modifier = Modifier.size(20.dp * pillScale)
                )
            }

            Box(
                Modifier
                    .width(1.dp)
                    .height(28.dp * pillScale)
                    .background(Color.White.copy(alpha = 0.16f))
            )

            Box(
                Modifier
                    .width(60.dp * pillScale)
                    .height(40.dp * pillScale)
                    .hapticClickable(Haptic.Tap, onClick = onProfile),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Rounded.Person,
                    "Profile",
                    tint = accent.copy(alpha = 0.88f),
                    modifier = Modifier.size(20.dp * pillScale)
                )
            }
        }

        NavCircle(
            rememberVectorPainter(Icons.Rounded.FormatListBulleted),
            "Queue",
            selectedTab == TAB_QUEUE,
            onQueue,
            modifier = queueModifier,
            scale = queueScale,
        )
    }
}

@Composable
fun DeviceName(
    name: String,
    modifier: Modifier = Modifier
) {
    Text(
        name,
        color = Color.White.copy(alpha = 0.5f),
        fontSize = 12.sp,
        fontWeight = FontWeight.Normal,
        textAlign = TextAlign.Center,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier.fillMaxWidth().padding(
            top = 0.dp,
            bottom = 1.dp
        )
    )
}
