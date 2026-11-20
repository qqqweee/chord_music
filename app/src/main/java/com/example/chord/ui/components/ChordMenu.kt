package com.example.chord.ui.components

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Sensors
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.material.icons.rounded.HeartBroken
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import android.widget.Toast
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import com.example.chord.playback.StreamInfoStore
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.chord.data.library.LibraryStore
import com.example.chord.data.local.LocalMusicRepository
import com.example.chord.playback.DownloadStore
import com.example.chord.data.settings.AppSettings
import com.example.chord.data.model.Song
import com.example.chord.ui.haptics.Haptic
import com.example.chord.ui.haptics.hapticClickable
import com.example.chord.ui.theme.ChordTheme
import kotlinx.coroutines.launch

// CHANGED: smaller corner (was 28dp)
private val MenuShape = RoundedCornerShape(18.dp)
// Haze: dark glass, a little more black than the page behind.
private val MenuGlass = Color(0xD90B0B0D)
private val Danger = Color(0xFFFF6B6B)

/**
 * The one popup menu for the whole app. Fill it with [ChordMenuQuickRow], [ChordMenuItem]
 * and [ChordMenuDivider].
 *
 * With a [LocalMenuHost] and [LocalMenuHaze] (both set in MainActivity) the menu is drawn by
 * [ChordMenuHost] as Haze glass over the page. Without them it falls back to a plain
 * DropdownMenu, so previews and old call sites still work.
 *
 * Call it inside the Box that holds the button. [anchorSize] is the size of that button.
 */
@Composable
fun ChordMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    anchorSize: androidx.compose.ui.unit.Dp = 40.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val host = LocalMenuHost.current
    val haze = LocalMenuHaze.current
    if (host == null || haze == null) {
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = onDismiss,
            shape = MenuShape,
            containerColor = MenuGlass,
            tonalElevation = 0.dp,
            shadowElevation = 24.dp,
            border = BorderStroke(0.5.dp, androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.20f), Color.White.copy(alpha = 0.05f)))),
            modifier = Modifier.widthIn(min = 190.dp, max = 230.dp),
            content = { Column(content = content) },
        )
        return
    }

    var origin by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    // A 1dp marker at the top-left of the caller's Box. The button rect is built from it.
    Spacer(Modifier.size(1.dp).onGloballyPositioned { origin = it.positionInRoot() })

    if (expanded) {
        val id = remember { Any() }
        val latest by rememberUpdatedState(content)
        val dismiss by rememberUpdatedState(onDismiss)
        val sizePx = with(androidx.compose.ui.platform.LocalDensity.current) { anchorSize.toPx() }
        val rect = androidx.compose.ui.geometry.Rect(origin.x, origin.y, origin.x + sizePx, origin.y + sizePx)
        SideEffect {
            host.show(MenuRequest(id, rect, { dismiss() }) { latest(this) })
        }
        DisposableEffect(id) { onDispose { host.hide(id) } }
    }
}

/** One row: icon, label, optional small line under it. */
@Composable
fun ChordMenuItem(
    label: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    sub: String? = null,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    val color = if (danger) Danger else ChordTheme.text
    Row(
        modifier.fillMaxWidth().hapticClickable(Haptic.Tap, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = if (sub != null) 6.dp else 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = color, modifier = Modifier.size(19.dp))
        Spacer(Modifier.size(13.dp))
        Column {
            Text(label, color = color, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (sub != null) Text(
                sub, color = ChordTheme.textDim, fontSize = 11.sp, fontWeight = FontWeight.Medium,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Queue actions. Provided once in MainActivity. Menu hides the rows when null. */
class SongActions(
    val playNext: (Song) -> Unit,
    val addToQueue: (Song) -> Unit,
    /** Radio from this song. */
    val startRadio: ((Song) -> Unit)? = null,
    /** Hook for lossless / quality addons. null = "no addon" toast. */
    val upgradeQuality: ((Song) -> Unit)? = null,
    /** Back to YouTube Opus for this song. Skips Monochrome and addons. */
    val useOpus: ((Song) -> Unit)? = null,
    val sleepTimer: (() -> Unit)? = null,
    val lyricsOffset: (() -> Unit)? = null,
    /** Dislike from the menu. null = a toast only. */
    val dislike: ((Song) -> Unit)? = null,
)
val LocalSongActions = staticCompositionLocalOf<SongActions?> { null }

class QuickAction(val label: String, val icon: ImageVector, val onClick: () -> Unit)

/** Row of icon-over-label buttons at the top of the menu (Like / Playlist / Share). */
@Composable
fun ChordMenuQuickRow(actions: List<QuickAction>, onDone: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        actions.forEach { a ->
            Column(
                Modifier.weight(1f).clip(RoundedCornerShape(14.dp))
                    .hapticClickable(Haptic.Tap) { onDone(); a.onClick() }
                    .padding(vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(a.icon, a.label, tint = ChordTheme.text, modifier = Modifier.size(21.dp))
                Text(a.label, color = ChordTheme.text, fontSize = 11.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 3.dp))
            }
        }
    }
}

@Composable
fun ChordMenuDivider() {
    Box(Modifier.padding(horizontal = 16.dp, vertical = 3.dp).fillMaxWidth().height(0.6.dp).background(Color.White.copy(alpha = 0.14f)))
}

fun shareSong(context: Context, title: String, videoId: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, "$title\nhttps://music.youtube.com/watch?v=$videoId")
    }
    context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

/**
 * Full song menu. Same look everywhere (Like/Playlist/Share, Play, Play next, Queue, Download, Artist).
 *
 *   Like / Playlist / Share
 *   ----
 *   Play, Start radio, Play next, Add to queue, Download
 *   ----
 *   Open album, Open artist, extras
 *
 * NEW params are all optional and default to null. A row only shows when you pass its
 * callback, so every existing call site still compiles and looks the same until you wire it.
 */
@Composable
fun SongMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    song: Song,
    onPlay: () -> Unit,
    onAddToPlaylist: ((Song) -> Unit)?,
    onOpenArtist: ((String) -> Unit)?,
    artist: String,
    extras: List<RowAction> = emptyList(),
    actions: SongActions? = LocalSongActions.current,
    showPlay: Boolean = true,
    album: String? = null,
    onOpenAlbum: ((String) -> Unit)? = null,
) {
    var showInfo by remember { mutableStateOf(false) }
    if (showInfo) AudioPipelineDialog(song) { showInfo = false }
    var askQuality by remember { mutableStateOf(false) }
    if (askQuality) DownloadQualityDialog(listOf(song)) { askQuality = false }
    ChordMenu(expanded, onDismiss) {
        // Only runs while the menu is open.
        val context = LocalContext.current
        val store = remember { LibraryStore.get(context) }
        val favs by remember(store) { store.favouriteIds() }.collectAsState(initial = emptySet())
        val liked = song.videoId in favs
        val scope = rememberCoroutineScope()
        fun soon(what: String) = Toast.makeText(context, what, Toast.LENGTH_SHORT).show()
        val isLocal = LocalMusicRepository.isLocal(song.videoId)

        // Order follows the design: Like / Dislike / Share, Add to playlist, queue + download, open, info.
        val quick = buildList {
            add(QuickAction(if (liked) "Liked" else "Like", if (liked) Icons.Rounded.Star else Icons.Rounded.StarBorder) {
                scope.launch { store.toggleFavourite(song) }
            })
            add(QuickAction("Dislike", Icons.Rounded.HeartBroken) {
                actions?.dislike?.invoke(song) ?: soon("Got it. We will suggest less like this")
            })
            add(QuickAction("Share", Icons.Rounded.Share) { shareSong(context, song.title, song.videoId) })
        }
        ChordMenuQuickRow(quick, onDone = onDismiss)
        ChordMenuDivider()

        if (onAddToPlaylist != null) {
            ChordMenuItem("Add to playlist", Icons.AutoMirrored.Rounded.PlaylistAdd) { onDismiss(); onAddToPlaylist(song) }
            ChordMenuDivider()
        }

        if (showPlay) ChordMenuItem("Play", Icons.Rounded.PlayArrow) { onDismiss(); onPlay() }
        if (actions != null) {
            ChordMenuItem("Start radio", Icons.Rounded.Sensors) { onDismiss(); actions.startRadio?.invoke(song) ?: onPlay() }
            ChordMenuItem("Play next", Icons.AutoMirrored.Rounded.PlaylistPlay) { onDismiss(); actions.playNext(song) }
            ChordMenuItem("Add to queue", Icons.AutoMirrored.Rounded.QueueMusic) { onDismiss(); actions.addToQueue(song) }
        }
        if (!isLocal) {
            // Menu stays open on Download / Remove so you can see the progress.
            val saved by DownloadStore.songs.collectAsState()
            val statuses by DownloadStore.status.collectAsState()
            val line = statuses[song.videoId]
            when {
                // Tap while it runs = cancel. Line says what it is doing, so it never looks stuck at 100%.
                line != null -> ChordMenuItem(line, Icons.Rounded.Download, sub = "Tap to cancel") {
                    DownloadStore.cancel(song.videoId)
                }
                saved.any { it.videoId == song.videoId } -> ChordMenuItem("Remove download", Icons.Rounded.DownloadDone) {
                    DownloadStore.remove(song)
                }
                else -> ChordMenuItem("Download", Icons.Rounded.Download) {
                    if (AppSettings.askDownloadQuality) askQuality = true else DownloadStore.download(song)
                }
            }
        }

        val hasArtist = onOpenArtist != null && artist.isNotBlank()
        val hasAlbum = onOpenAlbum != null && !album.isNullOrBlank()
        if (hasAlbum || hasArtist || extras.isNotEmpty()) ChordMenuDivider()
        if (hasAlbum) ChordMenuItem("Open album", Icons.Rounded.Album, sub = album) { onDismiss(); onOpenAlbum!!(album!!) }
        if (hasArtist) ChordMenuItem("Open artist", Icons.Rounded.Mic, sub = artist) { onDismiss(); onOpenArtist!!(artist) }
        extras.forEach { a ->
            ChordMenuItem(a.label, Icons.Rounded.PlayArrow) { onDismiss(); a.onClick() }
        }

        ChordMenuDivider()
        ChordMenuItem("Song Info", Icons.Rounded.Info) { onDismiss(); showInfo = true }

        // Less used rows, kept below the main block.
        if (!isLocal) {
            ChordMenuItem("Upgrade quality", Icons.Rounded.AutoAwesome) {
                onDismiss(); actions?.upgradeQuality?.invoke(song) ?: soon("No quality addon installed")
            }
            val forcedOpus by StreamInfoStore.forcedYoutube.collectAsState()
            if (song.videoId !in forcedOpus) {
                ChordMenuItem("Back to Opus", Icons.Rounded.Download, sub = "Skip lossless for this song") {
                    onDismiss(); actions?.useOpus?.invoke(song) ?: soon("Not available")
                }
            }
            var cached by remember(song.videoId) { mutableStateOf(isCached(context, song.videoId)) }
            if (cached) {
                ChordMenuItem("Remove from cache", Icons.Rounded.DeleteOutline, danger = true) {
                    removeFromCache(context, song.videoId)
                    cached = false
                    soon("Removed from cache")
                }
            } else {
                ChordMenuItem("Add to cache", Icons.Rounded.Download) {
                    addToCache(context, song)
                    soon("Caching started")
                }
            }
        }
        ChordMenuItem("Sleep timer", Icons.Rounded.Bedtime) { onDismiss(); actions?.sleepTimer?.invoke() ?: soon("Sleep timer coming soon") }
        ChordMenuItem("Lyrics offset", Icons.Rounded.Tune) { onDismiss(); actions?.lyricsOffset?.invoke() ?: soon("Lyrics offset coming soon") }
    }
}

/** Lyrics shift in ms. + = lyrics come later. Read by LyricsPanel. */
object LyricsOffset { var ms by mutableLongStateOf(0L) }

@Composable
fun SleepTimerDialog(onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    ChordDialog(title = "Sleep timer", onDismiss = onDismiss, confirm = "Off", onConfirm = { onPick(0) }) {
        Column(Modifier.padding(top = 8.dp)) {
            listOf(15, 30, 45, 60).forEach { m ->
                Text(
                    "$m minutes", color = ChordTheme.text, fontSize = 16.sp, fontWeight = FontWeight.Medium,
                    modifier = Modifier.fillMaxWidth().hapticClickable(Haptic.Tap) { onPick(m) }.padding(vertical = 10.dp),
                )
            }
        }
    }
}

@Composable
fun LyricsOffsetDialog(onDismiss: () -> Unit) {
    ChordDialog(title = "Lyrics offset", onDismiss = onDismiss, confirm = "Done", onConfirm = onDismiss) {
        Row(
            Modifier.fillMaxWidth().padding(top = 12.dp),
            horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically,
        ) {
            val b = Modifier.clip(RoundedCornerShape(50))
            Text("−", color = ChordTheme.text, fontSize = 26.sp, fontWeight = FontWeight.Medium,
                modifier = b.hapticClickable(Haptic.Tap) { LyricsOffset.ms -= 250 }.padding(horizontal = 20.dp, vertical = 6.dp))
            Text("${if (LyricsOffset.ms > 0) "+" else ""}${LyricsOffset.ms / 1000.0} s", color = ChordTheme.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Text("+", color = ChordTheme.text, fontSize = 26.sp, fontWeight = FontWeight.Medium,
                modifier = b.hapticClickable(Haptic.Tap) { LyricsOffset.ms += 250 }.padding(horizontal = 20.dp, vertical = 6.dp))
        }
    }
}

/** Drops this song's audio from the stream cache. True if anything was removed. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
fun removeFromCache(context: Context, videoId: String): Boolean = try {
    val cache = com.example.chord.playback.AudioCacheManager.getInstance(context)
    val keys = cache.keys.filter { videoId in it }
    keys.forEach { cache.removeResource(it) }
    keys.isNotEmpty()
} catch (e: Exception) { false }

/** True if any cached key belongs to this song. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
fun isCached(context: Context, videoId: String): Boolean = try {
    com.example.chord.playback.AudioCacheManager.getInstance(context).keys.any { videoId in it }
} catch (e: Exception) { false }

/**
 * Downloads the whole stream into the stream cache in the background.
 * Uses the same chord:// uri as playback, so the cache key matches and playback hits it.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
fun addToCache(context: Context, song: Song) {
    val app = context.applicationContext
    kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
        try {
            val cache = com.example.chord.playback.AudioCacheManager.getInstance(app)
            val resolver = com.example.chord.playback.StreamResolver(
                com.example.chord.playback.AudioStreamExtractor(app)
            )
            val upstream = androidx.media3.datasource.ResolvingDataSource.Factory(
                androidx.media3.datasource.DefaultHttpDataSource.Factory(), resolver
            )
            val source = androidx.media3.datasource.cache.CacheDataSource.Factory()
                .setCache(cache)
                .setUpstreamDataSourceFactory(upstream)
                .createDataSource()
            val spec = androidx.media3.datasource.DataSpec(
                android.net.Uri.parse(
                    com.example.chord.playback.StreamResolver.createDummyUri(song)
                )
            )
            androidx.media3.datasource.cache.CacheWriter(source, spec, null, null).cache()
        } catch (e: Exception) { /* partial data stays usable */ }
    }
}

/**
 * Audio pipeline card. Shows what is playing: source, codec, rate, bitrate.
 * Data comes from StreamInfoStore ("Opus · 160 kbps · 48 kHz"). A lossless addon only
 * has to call StreamInfoStore.put(videoId, "FLAC · 24-bit · 96 kHz") to show here.
 */
@Composable
fun AudioPipelineDialog(song: Song, onDone: () -> Unit) {
    val labels by StreamInfoStore.labels.collectAsState()
    val saved by DownloadStore.songs.collectAsState()
    val parts = StreamInfoStore.cleanParts(labels[song.videoId])
    val source = when {
        LocalMusicRepository.isLocal(song.videoId) -> "This phone"
        saved.any { it.videoId == song.videoId } -> "Saved file"
        else -> com.example.chord.playback.addon.AddonStore.addons.value
            .firstOrNull { it.name.isNotBlank() && parts.contains(it.name) }?.name ?: "YouTube Music"
    }
    AlertDialog(
        onDismissRequest = onDone,
        shape = RoundedCornerShape(24.dp),
        containerColor = MenuGlass,
        tonalElevation = 0.dp,
        title = {
            Column {
                Text("Audio pipeline", color = ChordTheme.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Text("Live signal path", color = ChordTheme.textDim, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            }
        },
        text = {
            Column {
                PipeRow("Source", source)
                PipeRow("Format", parts.getOrNull(0) ?: "—")
                parts.firstOrNull { it.endsWith("-bit") }?.let { PipeRow("Bit depth", it) }
                PipeRow("Bitrate", parts.firstOrNull { "kbps" in it } ?: if (StreamInfoStore.isLosslessLabel(parts.firstOrNull())) "Lossless" else "—")
                PipeRow("Sample rate", parts.firstOrNull { "kHz" in it } ?: "—")
                PipeRow("Resampler", "None")
            }
        },
        confirmButton = {
            Text(
                "Done", color = ChordTheme.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clip(RoundedCornerShape(50)).hapticClickable(Haptic.Tap, onClick = onDone)
                    .padding(horizontal = 18.dp, vertical = 8.dp),
            )
        },
    )
}

@Composable
private fun PipeRow(k: String, v: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(k, color = ChordTheme.textDim, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        Text(v, color = ChordTheme.text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}

// ---- Dialogs --------------------------------------------------------------------
// Unchanged below this line.

/**
 * App dialog. Round dark card. Confirm is a white pill, cancel is quiet text.
 * Pass [danger] for delete: confirm turns red.
 */
@Composable
fun ChordDialog(
    title: String,
    onDismiss: () -> Unit,
    confirm: String,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
    confirmEnabled: Boolean = true,
    danger: Boolean = false,
    body: String? = null,
    content: (@Composable () -> Unit)? = null,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(28.dp),
        containerColor = ChordTheme.sheet,
        tonalElevation = 0.dp,
        modifier = modifier,
        title = { Text(title, color = ChordTheme.text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold) },
        text = if (body != null || content != null) ({
            Column {
                if (body != null) Text(body, color = ChordTheme.textDim, fontSize = 15.sp)
                if (content != null) content()
            }
        }) else null,
        confirmButton = {
            val bg = if (danger) Danger else ChordTheme.text
            Text(
                confirm, color = ChordTheme.onAccent, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clip(RoundedCornerShape(50))
                    .background(if (confirmEnabled) bg else bg.copy(alpha = 0.3f))
                    .hapticClickable(Haptic.Confirm) { if (confirmEnabled) onConfirm() }
                    .padding(horizontal = 22.dp, vertical = 10.dp),
            )
        },
        dismissButton = {
            Text(
                "Cancel", color = ChordTheme.textDim, fontSize = 15.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.clip(RoundedCornerShape(50)).hapticClickable(Haptic.Tap, onClick = onDismiss)
                    .padding(horizontal = 18.dp, vertical = 10.dp),
            )
        },
    )
}

/** Round filled text box, no underline. For dialogs. */
@Composable
fun ChordTextField(value: String, onChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier) {
    TextField(
        value = value, onValueChange = onChange, singleLine = true,
        placeholder = { Text(placeholder, color = ChordTheme.textDim) },
        shape = RoundedCornerShape(16.dp),
        modifier = modifier.fillMaxWidth().padding(top = 12.dp),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = ChordTheme.surface,
            unfocusedContainerColor = ChordTheme.surface,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            cursorColor = ChordTheme.text,
            focusedTextColor = ChordTheme.text,
            unfocusedTextColor = ChordTheme.text,
        ),
    )
}