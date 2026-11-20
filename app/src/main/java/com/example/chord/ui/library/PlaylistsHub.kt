package com.example.chord.ui.library

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.chord.data.auth.AuthStore
import com.example.chord.data.home.HomeItem
import com.example.chord.data.home.HomeItemType
import com.example.chord.data.library.PlaylistSummary
import com.example.chord.data.library.YtLibrary
import com.example.chord.data.library.YtPlaylist
import com.example.chord.ui.components.CoverImage
import com.example.chord.ui.components.pressScale
import com.example.chord.ui.theme.ChordTheme
import com.example.chord.ui.theme.ChordType

private enum class Filter(val label: String) { All("All"), Yt("YouTube Music"), Phone("On this phone") }

private val Thumb = 40.dp
private val ThumbShape = RoundedCornerShape(10.dp)

/**
 * One compact page for every playlist: YouTube Music ones (synced) and the ones on this phone.
 * Pull down or tap refresh to sync again. List is cached, so it opens at once.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistsHub(
    local: List<PlaylistSummary>,
    onOpenLocal: (Long) -> Unit,
    onOpenYt: (HomeItem) -> Unit,
    onLogin: () -> Unit,
    onCreate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val signedIn by AuthStore.signedIn.collectAsState()
    val yt by YtLibrary.playlists.collectAsState()
    val syncing by YtLibrary.syncing.collectAsState()
    val updatedAt by YtLibrary.updatedAt.collectAsState()
    val error by YtLibrary.error.collectAsState()
    var filter by remember { mutableStateOf(Filter.All) }

    val showYt = filter != Filter.Phone
    val showLocal = filter != Filter.Yt

    Column(modifier.fillMaxSize()) {
        // Filter chips + sync status.
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Filter.values().forEach { f ->
                val on = f == filter
                Text(
                    f.label, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                    color = if (on) Color.Black else ChordTheme.text,
                    modifier = Modifier.clip(RoundedCornerShape(50))
                        .background(if (on) Color.White else ChordTheme.surface)
                        .pressScale(pressedScale = 0.95f, onClick = { filter = f })
                        .padding(horizontal = 14.dp, vertical = 7.dp),

                )
            }
            Spacer(Modifier.weight(1f))
            val spin = rememberInfiniteTransition(label = "sync").animateFloat(
                0f, 360f, infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart), label = "spin",
            )
            Box(
                Modifier.size(38.dp).clip(CircleShape).background(ChordTheme.surface)
                    .pressScale(pressedScale = 0.9f, onClick = { YtLibrary.refresh(force = true) }),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Rounded.Refresh, "Sync YouTube Music", tint = ChordTheme.text,
                    modifier = Modifier.size(20.dp).rotate(if (syncing) spin.value else 0f),
                )
            }
        }
        val status = when {
            !signedIn -> "Sign in to sync your YouTube Music playlists"
            syncing -> "Syncing…"
            error != null -> "Couldn't sync. Showing saved list"
            updatedAt > 0 -> "Synced ${ago(updatedAt)}"
            else -> ""
        }
        Crossfade(status, animationSpec = tween(200), label = "status") { t ->
            Text(
                t, style = ChordType.CardSub, color = if (error != null && signedIn) Color(0xFFFF8A80) else ChordTheme.textDim,
                modifier = Modifier.padding(horizontal = 18.dp).padding(bottom = 6.dp),
            )
        }

        PullToRefreshBox(isRefreshing = syncing, onRefresh = { YtLibrary.refresh(force = true) }, modifier = Modifier.fillMaxSize()) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 220.dp)) {
                if (showLocal) item(key = "new") {
                    PlainRow(Icons.Rounded.Add, "New playlist", "Saved on this phone", onCreate)
                }
                if (showYt && !signedIn) item(key = "signin") {
                    PlainRow(Icons.Rounded.Favorite, "Sign in to YouTube Music", "Liked songs and playlists stay in sync", onLogin)
                }
                if (showYt) items(yt, key = { "yt" + it.browseId }) { p ->
                    Row(
                        Modifier.fillMaxWidth().pressScale(pressedScale = 0.985f, onClick = {
                            onOpenYt(HomeItem(p.title, p.subtitle, p.thumbnailUrl, null, p.browseId, HomeItemType.Playlist))
                        }).padding(horizontal = 16.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (p.isLiked) LikedCover() else CoverImage(p.thumbnailUrl, Thumb, ThumbShape)
                        RowText(p.title, p.subtitle.ifBlank { "YouTube Music" })
                    }
                }
                if (showLocal) items(local, key = { "l" + it.id }) { p ->
                    Row(
                        Modifier.fillMaxWidth().pressScale(pressedScale = 0.985f, onClick = { onOpenLocal(p.id) })
                            .padding(horizontal = 16.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        PlaylistCover(p, Thumb, ThumbShape)
                        RowText(p.name, if (p.isFavourites) "Auto playlist · On this phone" else "${p.count} songs · On this phone")
                    }
                }
            }
        }
    }
}

@Composable
private fun RowText(title: String, sub: String) {
    Column(Modifier.padding(start = 14.dp)) {
        Text(
            title, color = ChordTheme.text, fontSize = 16.sp, fontWeight = FontWeight.Medium,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        Text(sub, style = ChordType.CardSub, color = ChordTheme.textDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun LikedCover() {
    Box(
        Modifier.size(Thumb).clip(ThumbShape)
            .background(Brush.linearGradient(listOf(Color(0xFFFF5F6D), Color(0xFF8E2DE2)))),
        contentAlignment = Alignment.Center,
    ) { Icon(Icons.Rounded.Favorite, null, tint = Color.White, modifier = Modifier.size(24.dp)) }
}

@Composable
private fun PlainRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, sub: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().pressScale(pressedScale = 0.985f, onClick = onClick).padding(horizontal = 16.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(Thumb).clip(ThumbShape).background(ChordTheme.surface).border(0.5.dp, ChordTheme.hairline, ThumbShape),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, null, tint = ChordTheme.text, modifier = Modifier.size(24.dp)) }
        RowText(title, sub)
    }
}

private fun ago(t: Long): String {
    val m = (System.currentTimeMillis() - t) / 60_000
    return when {
        m < 1 -> "just now"
        m < 60 -> "$m min ago"
        m < 1440 -> "${m / 60} h ago"
        else -> "${m / 1440} d ago"
    }
}
