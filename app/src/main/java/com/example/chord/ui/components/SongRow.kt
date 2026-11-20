package com.example.chord.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.chord.data.home.HomeItem
import com.example.chord.data.home.HomeItemType
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.unit.em

/** Provide once near the app root. Rows then show a 3-dot menu with "More from artist". */
val LocalOnSearchArtist = androidx.compose.runtime.staticCompositionLocalOf<((String) -> Unit)?> { null }

private val Dim = Color.White.copy(alpha = 0.60f)

/** Extra item in a row's 3-dot menu. */
class RowAction(val label: String, val onClick: () -> Unit)

/** "Song • Artist • Album" or "Artist • Album" -> "Artist". */
fun artistOf(subtitle: String): String {
    val kinds = setOf("song", "video", "single", "album", "ep")
    val parts = subtitle.split(" • ", "•").map { it.trim() }.filter { it.isNotEmpty() }
    return (if (parts.size > 1 && parts.first().lowercase() in kinds) parts[1] else parts.firstOrNull()).orEmpty()
}

/**
 * One row for a song, album, playlist or artist. 3-dot menu shows only when
 * [onMoreFromArtist] is given.
 */
@Composable
fun MediaRow(
    item: HomeItem,
    active: Boolean,
    playing: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    art: Dp = com.example.chord.ui.theme.ChordDims.rowArt,
    onMoreFromArtist: ((String) -> Unit)? = LocalOnSearchArtist.current,
    extraActions: List<RowAction> = emptyList(),
) {
    val addToPlaylist = com.example.chord.ui.library.LocalAddToPlaylist.current
    val round = item.type == HomeItemType.Artist
    val shape = if (round) CircleShape else RoundedCornerShape(6.dp)
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
            .pressScale(pressedScale = 0.98f, onClick = onClick)
            .padding(vertical = com.example.chord.ui.theme.ChordDims.rowGapV),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            CoverImage(item.thumbnailUrl, art, shape)
            if (active) NowPlayingOverlay(playing, Modifier.size(art).clip(shape))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ExplicitMark(com.example.chord.data.explicit.ExplicitStore.has(item.videoId))

                Text(
                    item.title,
                    fontSize = com.example.chord.ui.theme.ChordDims.rowTitle,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    lineHeight = 20.sp,
                    fontWeight = FontWeight.Medium,

                )
            }
            if (item.subtitle.isNotBlank()) Row(verticalAlignment = Alignment.CenterVertically) {
//                ExplicitMark(com.example.chord.data.explicit.ExplicitStore.has(item.videoId))
                Text(
                    item.subtitle, color = Dim, fontSize = com.example.chord.ui.theme.ChordDims.rowSub, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
        if (onMoreFromArtist != null && item.videoId != null) {
            var open by remember { mutableStateOf(false) }
            Box {
                Box(
                    Modifier.size(44.dp).clip(CircleShape).clickable { open = true },
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Rounded.MoreVert, "More", tint = Dim, modifier = Modifier.size(22.dp)) }
                if (open) SongMenu(
                    expanded = true,
                    onDismiss = { open = false },
                    song = com.example.chord.data.model.Song(
                        title = item.title,
                        details = com.example.chord.data.history.HistoryStore.cleanArtist(item.subtitle),
                        videoId = item.videoId,
                        thumbnailUrl = item.thumbnailUrl,
                    ),
                    onPlay = onClick,
                    onAddToPlaylist = addToPlaylist,
                    onOpenArtist = onMoreFromArtist,
                    artist = artistOf(item.subtitle),
                    extras = extraActions,
                )
            }
        }
    }
}
