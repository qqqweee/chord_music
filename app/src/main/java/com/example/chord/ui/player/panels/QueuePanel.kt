package com.example.chord.ui.player.panels

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.chord.playback.PlaybackState

@Composable
fun QueuePanel(
    playbackState: PlaybackState,
    onPlayQueueItem: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier.padding(horizontal = 22.dp, vertical = 12.dp)) {
        Text(text = "Up next", color = Color.White, fontSize = 27.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))

        LazyColumn(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            itemsIndexed(playbackState.queue) { index, song ->
                val current = index == playbackState.currentQueueIndex
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (current) Color.White.copy(alpha = 0.14f) else Color.Transparent)
                        .clickable(enabled = !current) { onPlayQueueItem(index) }
                        .padding(horizontal = 12.dp, vertical = 13.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            song.title, color = Color.White,
                            fontWeight = if (current) FontWeight.Bold else FontWeight.Medium,
                            maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            song.details, color = Color.White.copy(alpha = 0.64f),
                            fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                    }

                    if (current) {
                        Box(
                            modifier = Modifier
                                .background(Color.White.copy(alpha = 0.14f), RoundedCornerShape(12.dp))
                                .border(1.dp, Color.White.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
                                .padding(horizontal = 10.dp, vertical = 5.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(text = "♫", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold)
                        }
                    }
                }
            }
        }
    }
}