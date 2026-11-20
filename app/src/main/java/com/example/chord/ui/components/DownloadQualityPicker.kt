package com.example.chord.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.chord.data.model.Song
import com.example.chord.data.settings.AppSettings
import com.example.chord.data.settings.DownloadQuality
import com.example.chord.playback.DownloadStore
import com.example.chord.ui.theme.ChordTheme
import com.example.chord.ui.theme.ChordType

/**
 * Per song quality picker. Shows the three qualities, the Settings default is marked.
 * Tap one: those songs download at that quality. Tap outside: nothing happens.
 *
 * Use at a download button:
 *   var pending by remember { mutableStateOf<List<Song>?>(null) }
 *   onClick = { if (AppSettings.askDownloadQuality) pending = listOf(song) else DownloadStore.download(song) }
 *   pending?.let { DownloadQualityDialog(it) { pending = null } }
 */
@Composable
fun DownloadQualityDialog(songs: List<Song>, onDismiss: () -> Unit) {
    if (songs.isEmpty()) return
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.clip(RoundedCornerShape(24.dp)).background(ChordTheme.bg).padding(vertical = 18.dp),
        ) {
            Text(
                "Download quality",
                color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
            Text(
                if (songs.size == 1) songs[0].title else "${songs.size} songs",
                style = ChordType.CardSub, color = Color.White.copy(alpha = .62f), maxLines = 1,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 2.dp, bottom = 10.dp),
            )
            DownloadQuality.values().forEach { q ->
                Column(
                    Modifier.fillMaxWidth()
                        .pressScale(pressedScale = 0.97f) {
                            DownloadStore.downloadAll(songs, q)
                            onDismiss()
                        }
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(q.label, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                        if (q == AppSettings.downloadQuality) {
                            Text(
                                "  default", style = ChordType.CardSub,
                                color = Color.White.copy(alpha = .5f),
                            )
                        }
                    }
                    Text(q.hint, style = ChordType.CardSub, color = Color.White.copy(alpha = .62f))
                }
            }
        }
    }
}
