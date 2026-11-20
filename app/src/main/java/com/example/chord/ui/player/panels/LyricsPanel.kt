package com.example.chord.ui.player.panels

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.chord.data.repository.LyricsResult
import com.example.chord.ui.lyrics.WordByWordLyricsView

@Composable
fun LyricsPanel(
    availableLyrics: List<LyricsResult>,
    isLoadingLyrics: Boolean,
    title: String,
    position: Long,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    var selectedIndex by remember { mutableIntStateOf(0) }

    val currentLyrics = availableLyrics.getOrNull(selectedIndex)?.let { result ->
        result.syncedLyrics ?: result.plainLyrics
    }

    Column(modifier = modifier.fillMaxSize()) {
        if (availableLyrics.size > 1) {
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                itemsIndexed(availableLyrics) { index, result ->
                    val isSelected = index == selectedIndex
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .background(if (isSelected) Color.White.copy(alpha = 0.2f) else Color.Transparent)
                            .clickable { selectedIndex = index }
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = result.providerName,
                            color = if (isSelected) Color.White else Color.White.copy(alpha = 0.5f),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            when {
                isLoadingLyrics -> CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center),
                    color = Color.White
                )
                currentLyrics.isNullOrBlank() -> Text(
                    text = "No lyrics found",
                    color = Color.White.copy(alpha = 0.8f),
                    modifier = Modifier.align(Alignment.Center)
                )
                else -> key(title, currentLyrics) {
                    WordByWordLyricsView(
                        rawLyrics = currentLyrics,
                        currentPositionMs = position + com.example.chord.ui.components.LyricsOffset.ms,
                        onSeekRequested = onSeek,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
    }
}