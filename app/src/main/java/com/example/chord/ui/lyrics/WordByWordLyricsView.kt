package com.example.chord.ui.lyrics

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun WordByWordLyricsView(
    rawLyrics: String,
    currentPositionMs: Long,
    onSeekRequested: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val lines = remember(rawLyrics) {
        WordLyricsParser.parse(rawLyrics)
    }

    val listState = rememberLazyListState()

    val activeLineIndex = remember(lines, currentPositionMs) {
        lines.indexOfLast {
            it.lineStartMs <= currentPositionMs
        }.coerceAtLeast(0)
    }

    LaunchedEffect(activeLineIndex) {
        if (lines.isNotEmpty()) {
            listState.animateScrollToItem(activeLineIndex)
        }
    }

    BoxWithConstraints(
        modifier = modifier.fillMaxSize()
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                top = maxHeight / 2 - 30.dp,
                bottom = maxHeight / 2
            ),
            verticalArrangement = Arrangement.spacedBy(28.dp)
        ) {
            itemsIndexed(lines) { index, line ->
                val activeLine = index == activeLineIndex

                val scale by animateFloatAsState(
                    targetValue = if (activeLine) 1f else 0.94f,
                    animationSpec = tween(220),
                    label = "lineScale"
                )

                FlowRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            onSeekRequested(line.lineStartMs)
                        }
                        .padding(horizontal = 24.dp)
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            transformOrigin = TransformOrigin(0f, 0.5f)
                        },
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    line.words.forEach { word ->
                        val activeWord = activeLine &&
                                currentPositionMs >= word.startMs &&
                                currentPositionMs < word.endMs

                        val passedWord = activeLine &&
                                currentPositionMs >= word.endMs

                        val color by animateColorAsState(
                            targetValue = when {
                                activeWord -> Color.White
                                passedWord -> Color.White.copy(alpha = 0.9f)
                                activeLine -> Color.White.copy(alpha = 0.55f)
                                else -> Color.White.copy(alpha = 0.3f)
                            },
                            animationSpec = tween(120),
                            label = "wordColor"
                        )

                        Text(
                            text = word.word,
                            color = color,
                            fontSize = 29.sp,
                            lineHeight = 39.sp,
                            fontWeight = if (activeWord || passedWord) {
                                FontWeight.ExtraBold
                            } else {
                                FontWeight.SemiBold
                            },
                            modifier = Modifier.graphicsLayer {
                                val wordScale = if (activeWord) 1.08f else 1f
                                scaleX = wordScale
                                scaleY = wordScale
                            }
                        )
                    }
                }
            }
        }
    }
}