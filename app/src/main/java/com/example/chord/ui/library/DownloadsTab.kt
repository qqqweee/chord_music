package com.example.chord.ui.library

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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.chord.data.model.Song
import com.example.chord.playback.DownloadStore
import com.example.chord.ui.components.CoverImage
import com.example.chord.ui.components.NowPlayingOverlay
import com.example.chord.ui.haptics.Haptic
import com.example.chord.ui.haptics.hapticClickable
import com.example.chord.ui.theme.ChordDims
import com.example.chord.ui.theme.ChordTheme

private val Gutter = 20.dp

/**
 * Library > Downloads. Songs saved for offline.
 * Edit: pick the songs you do not want saved, then Remove.
 * To save more: Download in any song menu, or Download all inside a playlist.
 */
@Composable
fun DownloadsTab(
    nowPlayingId: String?,
    isPlaying: Boolean,
    onPlayQueue: (List<Song>, Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val songs by DownloadStore.songs.collectAsState()
    val progress by DownloadStore.progress.collectAsState()
    val statuses by DownloadStore.status.collectAsState()
    val running by DownloadStore.active.collectAsState()
    var editing by remember { mutableStateOf(false) }
    var picked by remember { mutableStateOf(setOf<String>()) }
    val active = progress.size

    Column(modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = Gutter, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (active > 0) "Saving $active…" else "${songs.size} saved",
                color = ChordTheme.textDim, fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f),
            )
            if (editing && songs.isNotEmpty()) {
                Pill(if (picked.size == songs.size) "None" else "All", enabled = true) {
                    picked = if (picked.size == songs.size) emptySet() else songs.map { it.videoId }.toSet()
                }
                Spacer(Modifier.width(8.dp))
                Pill("Remove ${picked.size}", enabled = picked.isNotEmpty(), danger = true) {
                    DownloadStore.removeAll(picked); picked = emptySet(); editing = false
                }
                Spacer(Modifier.width(8.dp))
            }
            if (songs.isNotEmpty()) Pill(if (editing) "Done" else "Edit", enabled = true) {
                editing = !editing; picked = emptySet()
            }
        }

        if (songs.isEmpty() && running.isEmpty()) {
            Text(
                "Nothing saved yet. Tap ⋮ on a song, then Download.",
                color = ChordTheme.textDim, fontSize = 14.sp, modifier = Modifier.padding(Gutter),
            )
            return@Column
        }

        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 220.dp)) {
            // Songs being saved right now. Each row says what it is doing, so nothing sits at "100%" for no reason.
            items(running, key = { "dl-" + it.videoId }, contentType = { "running" }) { song ->
                val shape = RoundedCornerShape(6.dp)
                val pct = progress[song.videoId] ?: 0f
                val line = statuses[song.videoId] ?: "Waiting…"
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = Gutter).padding(vertical = ChordDims.rowGapV),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CoverImage(song.thumbnailUrl, ChordDims.rowArt, shape)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            song.title, color = Color.White, fontSize = ChordDims.rowTitle, fontWeight = FontWeight.SemiBold,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, lineHeight = 20.sp,
                        )
                        Text(
                            line, color = ChordTheme.textDim, fontSize = ChordDims.rowSub, fontWeight = FontWeight.Medium,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        // Bar only while bytes move. Waiting / finishing show text only, so full bar never looks stuck.
                        if (line.startsWith("Downloading") && pct > 0f) {
                            Box(
                                Modifier.fillMaxWidth().padding(top = 6.dp).height(3.dp)
                                    .clip(RoundedCornerShape(50)).background(ChordTheme.surfaceHigh),
                            ) {
                                Box(
                                    Modifier.fillMaxWidth(pct.coerceIn(0f, 1f)).height(3.dp)
                                        .clip(RoundedCornerShape(50)).background(ChordTheme.text),
                                )
                            }
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    Icon(
                        Icons.Rounded.Close, "Cancel", tint = ChordTheme.textDim,
                        modifier = Modifier.size(32.dp).clip(RoundedCornerShape(50))
                            .hapticClickable(Haptic.Tap) { DownloadStore.cancel(song.videoId) }
                            .padding(6.dp),
                    )
                }
            }
            itemsIndexed(songs, key = { _, s -> s.videoId }, contentType = { _, _ -> "song" }) { i, song ->
                val on = song.videoId in picked
                val shape = RoundedCornerShape(6.dp)
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = Gutter).clip(RoundedCornerShape(8.dp))
                        .hapticClickable(Haptic.Tap) {
                            if (editing) picked = if (on) picked - song.videoId else picked + song.videoId
                            else onPlayQueue(songs, i)
                        }
                        .padding(vertical = ChordDims.rowGapV),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box {
                        CoverImage(song.thumbnailUrl, ChordDims.rowArt, shape)
                        if (!editing && song.videoId == nowPlayingId) NowPlayingOverlay(isPlaying, Modifier.size(ChordDims.rowArt).clip(shape))
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            song.title, color = Color.White, fontSize = ChordDims.rowTitle, fontWeight = FontWeight.SemiBold,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, lineHeight = 20.sp,
                        )
                        Text(
                            song.details, color = ChordTheme.textDim, fontSize = ChordDims.rowSub, fontWeight = FontWeight.Medium,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (editing) Icon(
                        if (on) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked, null,
                        tint = if (on) ChordTheme.text else ChordTheme.textDim, modifier = Modifier.size(24.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun Pill(label: String, enabled: Boolean, danger: Boolean = false, onClick: () -> Unit) {
    val bg = when {
        danger && enabled -> Color(0xFFFF6B6B)
        else -> ChordTheme.surfaceHigh
    }
    Text(
        label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
        color = if (danger && enabled) ChordTheme.onAccent else if (enabled) ChordTheme.text else ChordTheme.textDim,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(bg)
            .hapticClickable(Haptic.Tap, enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
    )
}
