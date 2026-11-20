@file:OptIn(ExperimentalLayoutApi::class)

package com.example.chord.ui.lyrics

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

private val LyricsEase = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)

private val LyricStyle = TextStyle(
    fontSize = 32.sp,
    lineHeight = 42.sp,
    fontWeight = FontWeight.Bold,
    letterSpacing = (-0.4).sp,
)
private val BrightStyle = LyricStyle.copy(
    shadow = Shadow(Color.White.copy(alpha = 0.55f), Offset.Zero, 22f)
)

private const val DIM_INACTIVE = 0.30f
private const val DIM_ACTIVE = 0.42f
private const val MAX_EXTRAPOLATE_MS = 1000L

@Composable
fun WordByWordLyricsView(
    rawLyrics: String,
    currentPositionMs: Long,
    onSeekRequested: (Long) -> Unit,
    modifier: Modifier = Modifier,
    isPlaying: Boolean = true,
    onUserScrollChange: (Boolean) -> Unit = {},
    textSize: TextUnit = 32.sp,
    anchorFraction: Float = 0.20f,
    userScrollEnabled: Boolean = true,
    blurInactive: Boolean = true,
    onTap: (() -> Unit)? = null,
) {
    val baseStyle = remember(textSize) {
        // Devanagari glyphs have tall matras, so give them a little more line height.
        LyricStyle.copy(fontSize = textSize, lineHeight = textSize * 1.4f, letterSpacing = 0.sp)
    }
    val brightStyle = remember(baseStyle) { baseStyle.copy(shadow = BrightStyle.shadow) }
    val vPad = (textSize.value * 0.3f).dp
    val lines = remember(rawLyrics) { WordLyricsParser.parse(rawLyrics) }

    if (lines.isEmpty()) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                "No synced lyrics available",
                color = Color.White.copy(alpha = 0.7f),
                fontSize = 16.sp,
            )
        }
        return
    }

    val smoothPos = rememberSmoothPosition(currentPositionMs, isPlaying)
    val activeIndex by remember(lines) {
        derivedStateOf { lines.indexOfLast { it.lineStartMs <= smoothPos.value } }
    }
    val listState = rememberLazyListState()

    var userActive by remember { mutableStateOf(false) }
    val reportScroll by rememberUpdatedState(onUserScrollChange)
    LaunchedEffect(listState) {
        var resume: Job? = null
        listState.interactionSource.interactions.collect { i ->
            when (i) {
                is DragInteraction.Start -> { resume?.cancel(); userActive = true; reportScroll(true) }
                is DragInteraction.Stop, is DragInteraction.Cancel -> {
                    resume = launch { delay(3500); userActive = false; reportScroll(false) }
                }
            }
        }
    }

    BoxWithConstraints(
        modifier
            .fillMaxSize()
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                drawRect(
                    brush = Brush.verticalGradient(
                        0f to Color.Transparent,
                        0.07f to Color.Black,
                        0.86f to Color.Black,
                        1f to Color.Transparent,
                    ),
                    blendMode = BlendMode.DstIn,
                )
            }
    ) {
        val anchor = maxHeight * anchorFraction
        val anchorPx = with(LocalDensity.current) { anchor.toPx() }

        LaunchedEffect(activeIndex, lines, userActive) {
            if (userActive) return@LaunchedEffect
            val target = activeIndex.coerceAtLeast(0)
            val info = listState.layoutInfo
            val item = info.visibleItemsInfo.firstOrNull { it.index == target }
            if (item != null) {
                val delta = item.offset - info.viewportStartOffset - anchorPx
                if (abs(delta) > 1f) {
                    listState.animateScrollBy(delta, tween(750, easing = LyricsEase))
                }
            } else {
                listState.animateScrollToItem(target)
            }
        }

        LazyColumn(
            state = listState,
            userScrollEnabled = userScrollEnabled,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = anchor, bottom = maxHeight * 0.7f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            itemsIndexed(lines, key = { i, l -> "$i-${l.lineStartMs}" }) { index, line ->
                LyricLine(
                    line = line,
                    index = index,
                    activeIndex = activeIndex,
                    pos = smoothPos,
                    style = baseStyle,
                    bright = brightStyle,
                    vPad = vPad,
                    blurEnabled = blurInactive,
                    onClick = onTap ?: { onSeekRequested(line.lineStartMs); userActive = false; reportScroll(false) },
                )
            }
        }
    }
}

@Composable
private fun LyricLine(
    line: EnhancedLyricLine,
    index: Int,
    activeIndex: Int,
    pos: State<Long>,
    style: TextStyle,
    bright: TextStyle,
    vPad: Dp,
    blurEnabled: Boolean,
    onClick: () -> Unit,
) {
    val isActive = index == activeIndex
    val distance = abs(index - activeIndex.coerceAtLeast(0))

    val act = animateFloatAsState(
        targetValue = if (isActive) 1f else 0f,
        animationSpec = tween(450, easing = LyricsEase),
        label = "lineActive",
    )
    val scale by animateFloatAsState(
        targetValue = if (isActive) 1f else 0.93f,
        animationSpec = tween(450, easing = LyricsEase),
        label = "lineScale",
    )
    val blur by animateDpAsState(
        targetValue = if (!blurEnabled || isActive || distance == 0) 0.dp else (distance * 1.2f).coerceAtMost(4.5f).dp,
        animationSpec = tween(450, easing = LyricsEase),
        label = "lineBlur",
    )
    val showBright = abs(index - activeIndex) <= 1

    // FIX: group chunks that have no space between them (e.g. Hindi syllables) so the
    // FlowRow can only wrap BETWEEN real words, never in the middle of one.
    val groups = remember(line) {
        val spaced = buildList<List<WordToken>> {
            var cur = mutableListOf<WordToken>()
            line.words.forEach { t ->
                cur.add(t)
                if (t.trailingSpace) { add(cur); cur = mutableListOf() }
            }
            if (cur.isNotEmpty()) add(cur)
        }
        // Safety net: if the API gave no spacing info at all, fall back to per-token wrapping
        // so a long line can still wrap instead of overflowing the screen.
        if (spaced.size == 1 && line.words.size > 4) line.words.map { listOf(it) } else spaced
    }

    FlowRow(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 0.dp, vertical = vPad)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                transformOrigin = TransformOrigin(0f, 0.5f)
            }
            .then(
                if (blur > 0.1.dp) Modifier.blur(blur, BlurredEdgeTreatment.Unbounded)
                else Modifier
            ),
        horizontalArrangement = Arrangement.Start,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        groups.forEach { group ->
            Row {
                group.forEach { token ->
                    LyricWord(token = token, pos = pos, act = act, showBright = showBright, style = style, bright = bright)
                }
            }
        }
    }
}

@Composable
private fun LyricWord(
    token: WordToken,
    pos: State<Long>,
    act: State<Float>,
    showBright: Boolean,
    style: TextStyle,
    bright: TextStyle,
) {
    Box(
        Modifier
            .padding(end = if (token.trailingSpace) 8.dp else 0.dp)
            .graphicsLayer {
                val p = wordProgress(pos.value, token)
                val pulse = if (p > 0f && p < 1f) sin(PI.toFloat() * p) else 0f
                val a = act.value
                translationY = -2.dp.toPx() * pulse * a
                val s = 1f + 0.03f * pulse * a
                scaleX = s
                scaleY = s
                transformOrigin = TransformOrigin(0.5f, 0.5f)
            }
    ) {
        Text(
            text = token.word,
            style = style,
            color = Color.White,
            softWrap = false, // FIX: a single word never wraps onto a second line
            maxLines = 1,
            modifier = Modifier.graphicsLayer {
                alpha = DIM_INACTIVE + (DIM_ACTIVE - DIM_INACTIVE) * act.value
            },
        )
        if (showBright) {
            Text(
                text = token.word,
                style = bright,
                color = Color.White,
                softWrap = false,
                maxLines = 1,
                modifier = Modifier
                    .graphicsLayer {
                        alpha = act.value
                        compositingStrategy = CompositingStrategy.Offscreen
                    }
                    .drawWithContent {
                        drawContent()
                        val p = wordProgress(pos.value, token)
                        val feather = (size.width * 0.35f).coerceIn(6.dp.toPx(), 18.dp.toPx())
                        val x = (size.width + feather) * p
                        drawRect(
                            brush = Brush.horizontalGradient(
                                colors = listOf(Color.White, Color.Transparent),
                                startX = x - feather,
                                endX = x,
                            ),
                            blendMode = BlendMode.DstIn,
                        )
                    },
            )
        }
    }
}

private fun wordProgress(posMs: Long, token: WordToken): Float {
    val duration = (token.endMs - token.startMs).coerceAtLeast(1L)
    return ((posMs - token.startMs).toFloat() / duration).coerceIn(0f, 1f)
}

@Composable
private fun rememberSmoothPosition(reportedMs: Long, isPlaying: Boolean): State<Long> {
    val smooth = remember { mutableLongStateOf(reportedMs) }
    LaunchedEffect(reportedMs, isPlaying) {
        if (!isPlaying) {
            smooth.longValue = reportedMs
            return@LaunchedEffect
        }
        val base = withFrameNanos { it }
        while (true) {
            withFrameNanos { now ->
                val elapsed = ((now - base) / 1_000_000L).coerceIn(0L, MAX_EXTRAPOLATE_MS)
                val target = reportedMs + elapsed
                val current = smooth.longValue
                if (target >= current || current - target > 400L) smooth.longValue = target
            }
        }
    }
    return smooth
}