package com.example.chord.ui.mixes

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.LibraryAdd
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.chord.data.home.HomeItem
import com.example.chord.data.home.HomeItemType
import com.example.chord.data.library.LibraryStore
import com.example.chord.data.mixes.MixEngine
import com.example.chord.data.mixes.MixGroup
import com.example.chord.data.model.Song
import com.example.chord.ui.components.CoverImage
import com.example.chord.ui.components.MediaRow
import com.example.chord.ui.components.pressScale
import com.example.chord.ui.theme.ChordTheme
import com.example.chord.ui.theme.ChordType
import kotlinx.coroutines.launch

private val OpenEase = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)

/** Slide-in page for a mix. Own function so AnimatedVisibility has no Box/Column scope around it. */
@Composable
fun MixOverlay(
    visible: Boolean,
    id: String?,
    nowPlayingId: String?,
    isPlaying: Boolean,
    onBack: () -> Unit,
    onPlayQueue: (List<Song>, Int) -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
        enter = slideInHorizontally(tween(320, easing = OpenEase)) { it / 3 } + fadeIn(tween(220)),
        exit = slideOutHorizontally(tween(260)) { it / 3 } + fadeOut(tween(200)),
    ) {
        id?.let { MixScreen(it, nowPlayingId, isPlaying, onBack, onPlayQueue) }
    }
}

@Composable
private fun MixScreen(
    id: String,
    nowPlayingId: String?,
    isPlaying: Boolean,
    onBack: () -> Unit,
    onPlayQueue: (List<Song>, Int) -> Unit,
) {
    val all by MixEngine.mixes.collectAsState()
    val busy by MixEngine.lastFmBusy.collectAsState()
    val mix = all.firstOrNull { it.id == id }
    val songs = mix?.songs.orEmpty()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    BackHandler(onBack = onBack)

    Column(Modifier.fillMaxSize().background(ChordTheme.bg)) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 220.dp)) {
            item(key = "head") {
                Column(Modifier.fillMaxWidth().statusBarsPadding(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(Modifier.fillMaxWidth().padding(start = 8.dp, top = 8.dp)) {
                        Box(
                            Modifier.size(44.dp).clip(CircleShape).pressScale(pressedScale = 0.9f, onClick = onBack),
                            contentAlignment = Alignment.Center,
                        ) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = ChordTheme.text) }
                    }
                    // 2x2 collage of covers.
                    val covers = songs.mapNotNull { it.thumbnailUrl }.distinct().take(4)
                    Column(Modifier.padding(top = 6.dp).clip(RoundedCornerShape(20.dp))) {
                        if (covers.size >= 4) {
                            Row { CoverImage(covers[0], 100.dp, RoundedCornerShape(0.dp)); CoverImage(covers[1], 100.dp, RoundedCornerShape(0.dp)) }
                            Row { CoverImage(covers[2], 100.dp, RoundedCornerShape(0.dp)); CoverImage(covers[3], 100.dp, RoundedCornerShape(0.dp)) }
                        } else CoverImage(covers.firstOrNull(), 200.dp, RoundedCornerShape(0.dp))
                    }
                    Text(
                        mix?.title ?: "Mix", style = ChordType.Display, color = ChordTheme.text,
                        maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(top = 18.dp, start = 24.dp, end = 24.dp),
                    )
                    Text(
                        mix?.subtitle.orEmpty(), style = ChordType.CardSub, color = ChordTheme.textDim,
                        maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp, start = 24.dp, end = 24.dp),
                    )
                    val tag = when (mix?.group) {
                        MixGroup.LastFm -> if (busy) "Last.fm · updating…" else "Powered by Last.fm"
                        MixGroup.Daily -> "Made on this phone · changes daily"
                        MixGroup.Replay -> "Made on this phone"
                        MixGroup.Mood -> "Made on this phone · Last.fm tags"
                        null -> ""
                    }
                    Text("$tag · ${songs.size} songs", style = ChordType.CardSub, color = ChordTheme.textDim, textAlign = androidx.compose.ui.text.style.TextAlign.Center, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(top = 2.dp))
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
                        Pill(Icons.Rounded.PlayArrow, "Play", true, songs.isNotEmpty()) { onPlayQueue(songs, 0) }
                        Pill(Icons.Rounded.Shuffle, "Shuffle", false, songs.isNotEmpty()) { onPlayQueue(songs.shuffled(), 0) }
                        Pill(Icons.Rounded.LibraryAdd, "Save", false, songs.isNotEmpty()) {
                            scope.launch {
                                val store = LibraryStore.get(ctx)
                                val pid = store.create(mix?.title ?: "Mix")
                                store.addAll(pid, songs)
                                android.widget.Toast.makeText(ctx, "Saved to Library", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                }
            }
            itemsIndexed(songs, key = { i, s -> s.videoId + i }) { i, s ->
                MediaRow(
                    item = HomeItem(s.title, s.details, s.thumbnailUrl, s.videoId, null, HomeItemType.Song),
                    active = s.videoId == nowPlayingId,
                    playing = isPlaying,
                    onClick = { onPlayQueue(songs, i) },
                )
            }
        }
    }
}

@Composable
private fun Pill(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, filled: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(50)).then(Modifier)
            .background(if (filled) Color.White else Color.White.copy(alpha = 0.12f))
            .pressScale(pressedScale = 0.94f, onClick = { if (enabled) onClick() })
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = if (filled) Color.Black else Color.White, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, color = if (filled) Color.Black else Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}
