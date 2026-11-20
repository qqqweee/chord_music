package com.example.chord.ui.stats

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.chord.data.home.HomeItem
import com.example.chord.data.home.HomeItemType
import com.example.chord.data.stats.ListenStats
import com.example.chord.data.stats.ListenStatsStore
import com.example.chord.data.stats.formatListenTime
import com.example.chord.ui.components.CoverImage
import com.example.chord.ui.components.pressScale
import com.example.chord.ui.haptics.Haptic
import com.example.chord.ui.haptics.hapticClickable
import com.example.chord.ui.theme.ChordTheme
import com.example.chord.ui.theme.ChordType
import kotlinx.coroutines.launch

private val Gutter = 20.dp
private val Green = Color(0xFF1ED760)

/**
 * Library > Your listening. Spotify style: one big number on a soft colour wash,
 * three small stat tiles, then Top artist (round photo) and Top song (cover row).
 */
@Composable
fun ListeningScreen(
    onPlaySong: (HomeItem) -> Unit,
    onSearchArtist: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val stats by remember { ListenStatsStore.get(context).stats() }.collectAsState(initial = ListenStats())
    val scope = rememberCoroutineScope()

    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 220.dp)) {
        item(key = "hero") {
            Box(
                Modifier.fillMaxWidth().background(
                    Brush.verticalGradient(listOf(Green.copy(alpha = 0.32f), Color.Transparent))
                ),
            ) {
                Column(Modifier.padding(horizontal = Gutter).padding(top = 20.dp, bottom = 28.dp)) {
                    Text("ALL TIME", style = ChordType.Eyebrow, color = ChordTheme.textDim)
                    Text(
                        formatListenTime(stats.totalMs), color = ChordTheme.text, fontSize = 48.sp,
                        fontWeight = FontWeight.SemiBold, letterSpacing = (-1.6).sp, modifier = Modifier.padding(top = 6.dp),
                    )
                    Text("of music with Chord", style = ChordType.CardSub, color = ChordTheme.textDim)
                    Row(
                        Modifier.padding(top = 18.dp).clip(RoundedCornerShape(50)).background(ChordTheme.text)
                            .hapticClickable(Haptic.Confirm) { scope.launch { runCatching { StatsShare.share(context, stats) } } }
                            .padding(horizontal = 18.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Rounded.Share, null, tint = ChordTheme.onAccent, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Share", color = ChordTheme.onAccent, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }

        item(key = "tiles") {
            Row(Modifier.padding(horizontal = Gutter), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Tile("Today", formatListenTime(stats.todayMs), Modifier.weight(1f))
                Tile("7 days", formatListenTime(stats.weekMs), Modifier.weight(1f))
                Tile("Streak", "${stats.streakDays} d", Modifier.weight(1f))
            }
        }

        stats.topArtist?.let { a ->
            item(key = "artist") {
                Column(Modifier.padding(top = 32.dp)) {
                    Section("Top artist")
                    Row(
                        Modifier.fillMaxWidth().pressScale(pressedScale = 0.985f, onClick = { onSearchArtist(a.name) })
                            .padding(horizontal = Gutter, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CoverImage(a.thumb, 88.dp, CircleShape)
                        Column(Modifier.padding(start = 16.dp)) {
                            Text(a.name, color = ChordTheme.text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("Artist · ${formatListenTime(a.ms)}", style = ChordType.CardSub, color = ChordTheme.textDim)
                        }
                    }
                }
            }
        }

        stats.topSong?.let { s ->
            item(key = "song") {
                Column(Modifier.padding(top = 28.dp)) {
                    Section("Top song")
                    Row(
                        Modifier.fillMaxWidth().pressScale(pressedScale = 0.985f, onClick = {
                            onPlaySong(HomeItem(s.title, s.artist, s.thumb, s.videoId, null, HomeItemType.Song))
                        }).padding(horizontal = Gutter, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CoverImage(s.thumb, 88.dp, RoundedCornerShape(8.dp))
                        Column(Modifier.padding(start = 16.dp)) {
                            Text(s.title, color = ChordTheme.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text("${s.artist.ifBlank { "Unknown" }} · ${formatListenTime(s.ms)}", style = ChordType.CardSub, color = ChordTheme.textDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }

        if (stats.totalMs == 0L) item(key = "empty") {
            Text(
                "Play some songs. Your listening shows up here.", color = ChordTheme.textDim, fontSize = 15.sp,
                modifier = Modifier.padding(Gutter).padding(top = 20.dp),
            )
        }
    }
}

@Composable
private fun Section(text: String) {
    Text(text, style = ChordType.Section, color = ChordTheme.text, modifier = Modifier.padding(horizontal = Gutter).padding(bottom = 12.dp))
}

@Composable
private fun Tile(label: String, value: String, modifier: Modifier) {
    Column(
        modifier.clip(RoundedCornerShape(14.dp)).background(ChordTheme.surface).padding(horizontal = 14.dp, vertical = 14.dp),
    ) {
        Text(value, color = ChordTheme.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        Spacer(Modifier.height(2.dp))
        Text(label, style = ChordType.CardSub, color = ChordTheme.textDim)
    }
}
