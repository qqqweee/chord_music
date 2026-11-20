@file:OptIn(ExperimentalMaterial3Api::class)

package com.example.chord.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.chord.data.model.Song
import com.example.chord.ui.components.CoverImage
import com.example.chord.ui.haptics.Haptic
import com.example.chord.ui.haptics.hapticClickable
import com.example.chord.ui.haptics.rememberHaptics
import com.example.chord.ui.theme.ChordTheme

/**
 * Provide once at the app root. Any row or button calls it to open the add sheet:
 *   val addToPlaylist = LocalAddToPlaylist.current
 *   addToPlaylist?.invoke(song)
 */
val LocalAddToPlaylist = androidx.compose.runtime.staticCompositionLocalOf<((Song) -> Unit)?> { null }

/** Bottom sheet: tick playlists for [song]. Each tap saves at once. */
@Composable
fun AddToPlaylistSheet(
    song: Song,
    onDismiss: () -> Unit,
    viewModel: LibraryViewModel = viewModel(),
) {
    val playlists by viewModel.playlists.collectAsState(initial = emptyList())
    val member by remember(song.videoId) { viewModel.membership(song.videoId) }.collectAsState(initial = emptySet())
    val haptics = rememberHaptics()
    var creating by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = ChordTheme.sheet,
        dragHandle = null,
    ) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 12.dp)) {
            Row(Modifier.padding(horizontal = 20.dp, vertical = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                CoverImage(song.thumbnailUrl ?: "https://i.ytimg.com/vi/${song.videoId}/hqdefault.jpg", 48.dp, RoundedCornerShape(8.dp))
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text("Add to playlist", color = ChordTheme.textDim, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                    Text(song.title, color = ChordTheme.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            SheetRow(
                title = "New playlist", subtitle = null, leading = {
                    Box(Modifier.size(46.dp).clip(RoundedCornerShape(8.dp)).background(ChordTheme.surfaceHigh), contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.Add, null, tint = ChordTheme.text)
                    }
                }, trailing = null, onClick = { creating = true },
            )
            LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false)) {
                items(playlists, key = { it.id }) { p ->
                    val on = p.id in member
                    SheetRow(
                        title = p.name,
                        subtitle = "${p.count} songs",
                        leading = { PlaylistCover(p, 46.dp, RoundedCornerShape(8.dp)) },
                        trailing = {
                            Icon(
                                if (on) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked, null,
                                tint = if (on) ChordTheme.accent else ChordTheme.textDim, modifier = Modifier.size(26.dp),
                            )
                        },
                        onClick = {
                            haptics.perform(if (on) Haptic.ToggleOff else Haptic.ToggleOn)
                            viewModel.toggle(p.id, song)
                        },
                    )
                }
            }
        }
    }

    if (creating) {
        NamePlaylistDialog(
            title = "New playlist", initial = "", confirm = "Create",
            onDismiss = { creating = false },
            onConfirm = { name ->
                creating = false
                viewModel.create(name) { id -> viewModel.toggle(id, song) { haptics.perform(Haptic.Confirm) } }
            },
        )
    }
}

@Composable
private fun SheetRow(
    title: String,
    subtitle: String?,
    leading: @Composable () -> Unit,
    trailing: (@Composable () -> Unit)?,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().hapticClickable(Haptic.Tap, onClick = onClick).padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading()
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = ChordTheme.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) Text(subtitle, color = ChordTheme.textDim, fontSize = 13.sp)
        }
        trailing?.invoke()
    }
}

/** Keep for reuse: Favourites star badge. */
@Composable
fun FavouriteBadge(size: androidx.compose.ui.unit.Dp, modifier: Modifier = Modifier) {
    Box(
        modifier.size(size).background(
            androidx.compose.ui.graphics.Brush.linearGradient(listOf(Color(0xFFFF8A5B), Color(0xFFB4378B)))
        ),
        contentAlignment = Alignment.Center,
    ) { Icon(Icons.Rounded.Star, null, tint = Color.White, modifier = Modifier.size(size / 2)) }
}
