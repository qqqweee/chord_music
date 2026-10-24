package com.example.chord.ui.lyrics

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

@Composable
fun SyncedLyricsView(
    syncedLyrics: String,
    currentPositionMs: Long,
    onSeekRequested: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val parsedLyrics = remember(syncedLyrics) {
        LyricsParser.parse(syncedLyrics)
    }

    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()

    val activeIndex = remember(currentPositionMs, parsedLyrics) {
        val index = parsedLyrics.indexOfLast { it.startTimeMs <= currentPositionMs }
        if (index == -1) 0 else index
    }

    LaunchedEffect(activeIndex) {
        if (parsedLyrics.isNotEmpty() && !listState.isScrollInProgress) {
            coroutineScope.launch {
                listState.animateScrollToItem(
                    index = activeIndex,
                    scrollOffset = 0
                )
            }
        }
    }

    if (parsedLyrics.isEmpty()) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = "No synced lyrics available",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val halfScreenHeight = maxHeight / 2

        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = 0.99f }
                .drawWithContent {
                    drawContent()
                    drawRect(
                        // Fixed: Using explicit Pair<Float, Color> for color stops
                        brush = Brush.verticalGradient(
                            0f to Color.Transparent,
                            0.15f to Color.Black,
                            0.85f to Color.Black,
                            1f to Color.Transparent,
                            startY = 0f,
                            endY = size.height
                        ),
                        blendMode = BlendMode.DstIn
                    )
                },
            contentPadding = PaddingValues(
                top = halfScreenHeight - 40.dp,
                bottom = halfScreenHeight
            ),
            verticalArrangement = Arrangement.spacedBy(32.dp)
        ) {
            itemsIndexed(parsedLyrics) { index, line ->
                val isActive = index == activeIndex

                val textColor by animateColorAsState(
                    targetValue = if (isActive) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                    },
                    animationSpec = tween(durationMillis = 400, easing = FastOutSlowInEasing),
                    label = "lyricTextColor"
                )

                val textScale by animateFloatAsState(
                    targetValue = if (isActive) 1.05f else 0.98f,
                    animationSpec = tween(durationMillis = 400, easing = FastOutSlowInEasing),
                    label = "lyricScale"
                )

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSeekRequested(line.startTimeMs) }
                        .padding(horizontal = 24.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    Text(
                        text = line.text.ifBlank { "• • •" },
                        color = textColor,
                        fontSize = 28.sp,
                        fontWeight = if (isActive) FontWeight.Bold else FontWeight.SemiBold,
                        textAlign = TextAlign.Start,
                        lineHeight = 36.sp,
                        modifier = Modifier.graphicsLayer {
                            scaleX = textScale
                            scaleY = textScale
                            transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0.5f)
                        }
                    )
                }
            }
        }
    }
}