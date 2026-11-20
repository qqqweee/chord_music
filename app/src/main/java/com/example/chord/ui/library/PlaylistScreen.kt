
package com.example.chord.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.chord.data.home.HomeItem
import com.example.chord.data.home.HomeItemType
import com.example.chord.data.model.Song
import com.example.chord.playback.DownloadStore
import com.example.chord.data.settings.AppSettings
import com.example.chord.ui.components.DownloadQualityDialog
import com.example.chord.ui.components.ChordDialog
import com.example.chord.ui.components.MediaRow
import com.example.chord.ui.components.RowAction
import com.example.chord.ui.haptics.Haptic
import com.example.chord.ui.haptics.hapticClickable
import com.example.chord.ui.theme.ChordTheme

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun PlaylistScreen(
    playlistId: Long,
    nowPlayingId: String?,
    isPlaying: Boolean,
    onBack: () -> Unit,
    onPlayQueue: (List<Song>, Int) -> Unit,
    onSearchArtist: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: LibraryViewModel = viewModel(),
) {
    val playlist by remember(playlistId) {
        viewModel.playlist(playlistId)
    }.collectAsState(initial = null)

    val songs by remember(playlistId) {
        viewModel.songs(playlistId)
    }.collectAsState(initial = emptyList())

    val p = playlist
    var confirmDelete by remember { mutableStateOf(false) }

    val saved by DownloadStore.songs.collectAsState()
    val savedIds = remember(saved) {
        saved.mapTo(HashSet()) { it.videoId }
    }
    val allSaved = songs.isNotEmpty() && songs.all { it.videoId in savedIds }
    var askQuality by remember { mutableStateOf(false) }
    if (askQuality) DownloadQualityDialog(songs.filter { it.videoId !in savedIds }) { askQuality = false }

    val sync by viewModel.sync.collectAsState()
    val syncing = sync is SyncUi.Working
    val svc = if (p?.source?.startsWith("sp:") == true) {
        "Spotify"
    } else {
        "YouTube Music"
    }

    androidx.compose.runtime.DisposableEffect(playlistId) {
        onDispose { viewModel.dismissSync() }
    }

    when (val s = sync) {
        is SyncUi.AskRemove -> ChordDialog(
            title = "Removed from $svc",
            body = "${s.gone.size} song${if (s.gone.size == 1) "" else "s"} no longer in the $svc playlist:\n" +
                    names(s.gone.map { it.title }) +
                    "\n\nRemove from this app too?",
            confirm = "Remove",
            danger = true,
            onDismiss = { viewModel.keepGone(s) },
            onConfirm = { viewModel.removeGone(s) },
        )

        is SyncUi.Done -> ChordDialog(
            title = "Synced",
            body = buildString {
                append(
                    if (s.added == 0 && s.removed == 0) {
                        "Already up to date."
                    } else {
                        "Added ${s.added}, removed ${s.removed}."
                    }
                )
                if (s.missed.isNotEmpty()) {
                    append("\n\n${s.missed.size} not found on YouTube Music:\n")
                    append(names(s.missed))
                }
            },
            confirm = "OK",
            onDismiss = viewModel::dismissSync,
            onConfirm = viewModel::dismissSync,
        )

        is SyncUi.Failed -> ChordDialog(
            title = "Sync failed",
            body = s.message,
            confirm = "OK",
            onDismiss = viewModel::dismissSync,
            onConfirm = viewModel::dismissSync,
        )

        else -> Unit
    }

    if (confirmDelete && p != null) {
        ChordDialog(
            title = "Delete \"${p.name}\"?",
            body = "The songs stay in your history.",
            confirm = "Delete",
            danger = true,
            onDismiss = { confirmDelete = false },
            onConfirm = {
                confirmDelete = false
                viewModel.delete(playlistId)
                onBack()
            },
        )
    }

    Column(
        modifier
            .fillMaxSize()
            .background(ChordTheme.bg)
            .statusBarsPadding()
    ) {
        Box(
            Modifier
                .padding(start = 8.dp, top = 6.dp)
                .size(44.dp)
                .clip(CircleShape)
                .hapticClickable(Haptic.Tap, onClick = onBack),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.AutoMirrored.Rounded.ArrowBack,
                "Back",
                tint = ChordTheme.text,
            )
        }

        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 200.dp),
        ) {
            item {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    if (p != null) {
                        PlaylistCover(
                            p,
                            180.dp,
                            RoundedCornerShape(16.dp),
                        )
                    }

                    Spacer(Modifier.height(14.dp))

                    Text(
                        p?.name.orEmpty(),
                        color = ChordTheme.text,
                        fontSize = 26.sp,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )

                    Text(
                        "${songs.size} songs",
                        color = ChordTheme.textDim,
                        fontSize = 14.sp,
                    )

                    Spacer(Modifier.height(16.dp))

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        PillButton(
                            "Play",
                            Icons.Rounded.PlayArrow,
                            filled = true,
                            enabled = songs.isNotEmpty(),
                        ) {
                            onPlayQueue(songs, 0)
                        }

                        PillButton(
                            "Shuffle",
                            Icons.Rounded.Shuffle,
                            filled = false,
                            enabled = songs.isNotEmpty(),
                        ) {
                            onPlayQueue(songs.shuffled(), 0)
                        }
                    }

                    Spacer(Modifier.height(12.dp))

                    // Responsive actions: wrap onto another line on narrow devices.
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        maxItemsInEachRow = 3,
                    ) {
                        TextAction(
                            if (allSaved) "Saved offline" else "Download all",
                            if (allSaved) Icons.Rounded.DownloadDone
                            else Icons.Rounded.Download,
                            ChordTheme.textDim,
                            enabled = songs.isNotEmpty() && !allSaved,
                        ) {
                            if (AppSettings.askDownloadQuality) askQuality = true else DownloadStore.downloadAll(songs)
                        }

                        if (p?.source != null) {
                            TextAction(
                                if (syncing) "Syncing…" else "Sync with $svc",
                                Icons.Rounded.Sync,
                                ChordTheme.textDim,
                                enabled = !syncing,
                            ) {
                                viewModel.sync(playlistId, p.source)
                            }
                        }

                        if (p != null && !p.isFavourites) {
                            TextAction(
                                "Delete playlist",
                                Icons.Rounded.Delete,
                                Color(0xFFFF6B6B),
                                enabled = true,
                            ) {
                                confirmDelete = true
                            }
                        }
                    }

                    Spacer(Modifier.height(12.dp))
                }
            }

            if (songs.isEmpty()) {
                item {
                    Text(
                        "Nothing here yet. Tap ⋮ on any song, then Add to playlist.",
                        color = ChordTheme.textDim,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(24.dp),
                    )
                }
            }

            items(songs, key = { it.videoId }) { song ->
                MediaRow(
                    item = HomeItem(
                        song.title,
                        song.details,
                        song.thumbnailUrl,
                        song.videoId,
                        null,
                        HomeItemType.Song,
                    ),
                    active = song.videoId == nowPlayingId,
                    playing = isPlaying,
                    onClick = {
                        onPlayQueue(
                            songs,
                            songs.indexOf(song).coerceAtLeast(0),
                        )
                    },
                    modifier = Modifier.padding(horizontal = 20.dp),
                    onMoreFromArtist = onSearchArtist,
                    extraActions = listOf(
                        RowAction("Remove from playlist") {
                            viewModel.remove(playlistId, song.videoId)
                        }
                    ),
                )
            }
        }
    }
}

private fun names(list: List<String>, max: Int = 8): String =
    list.take(max).joinToString("\n") { "• $it" } +
            if (list.size > max) "\nand ${list.size - max} more" else ""

@Composable
private fun TextAction(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: Color,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .hapticClickable(
                Haptic.Tap,
                enabled = enabled,
                onClick = onClick,
            )
            .padding(horizontal = 8.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            null,
            tint = if (enabled) tint else tint.copy(alpha = 0.4f),
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            label,
            color = if (enabled) tint else tint.copy(alpha = 0.4f),
            fontWeight = FontWeight.Medium,
            fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun PillButton(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    filled: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(
                if (filled) ChordTheme.text else ChordTheme.surfaceHigh
            )
            .hapticClickable(
                Haptic.Confirm,
                enabled = enabled,
                onClick = onClick,
            )
            .padding(horizontal = 22.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            null,
            tint = if (filled) ChordTheme.bg else ChordTheme.text,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.size(6.dp))
        Text(
            label,
            color = if (filled) ChordTheme.bg else ChordTheme.text,
            fontWeight = FontWeight.SemiBold,
            fontSize = 15.sp,
        )
    }
}
