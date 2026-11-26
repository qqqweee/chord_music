package com.example.chord.ui.library

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DownloadForOffline
import androidx.compose.material.icons.rounded.BarChart
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.chord.data.home.HomeItem
import com.example.chord.data.library.PlaylistSummary
import com.example.chord.data.model.Song
import com.example.chord.data.stats.RecapPeriod
import com.example.chord.ui.stats.RecapShelf
import com.example.chord.ui.components.ChordDialog
import com.example.chord.ui.components.ChordMenu
import com.example.chord.ui.components.ChordMenuItem
import com.example.chord.ui.components.CoverImage
import com.example.chord.ui.components.MediaRow
import com.example.chord.ui.components.pressScale
import com.example.chord.ui.haptics.Haptic
import com.example.chord.ui.haptics.hapticClickable
import com.example.chord.ui.haptics.hapticCombinedClickable
import com.example.chord.ui.theme.ChordTheme
import com.example.chord.ui.theme.ChordType

private enum class Sub(val title: String) {
    Recent("Recently played"), Most("Most played"), Device("Local music"), Downloads("Downloads"), Playlists("Playlists"),
}

private val Gutter = 20.dp
private val CardSize = 144.dp
private val CardShape = RoundedCornerShape(22.dp)

/**
 * Library, one calm scroll:
 *   quick rows (Recent, Most played, Local music)
 *   Playlists row (New, Import, Favourites, yours)
 *   Artists row
 * Rows open a sub page with a back arrow.
 */
@Composable
fun LibraryScreen(
    nowPlayingId: String?,
    isPlaying: Boolean,
    onPlaySong: (HomeItem) -> Unit,
    onPlayQueue: (List<Song>, Int) -> Unit,
    onSearchArtist: (String) -> Unit,
    onLogin: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenBrowse: (HomeItem) -> Unit = {},
    onOpenRecap: (RecapPeriod) -> Unit = {},
    viewModel: LibraryViewModel = viewModel(),
) {
    var sub by remember { mutableStateOf<Sub?>(null) }
    var openId by remember { mutableStateOf<Long?>(null) }
    var creating by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<PlaylistSummary?>(null) }
    var deleting by remember { mutableStateOf<PlaylistSummary?>(null) }

    // YouTube Music playlists: cached list at once, synced again when stale / on sign-in.
    val ctx = androidx.compose.ui.platform.LocalContext.current
    remember { com.example.chord.data.library.YtLibrary.init(ctx) }
    val signedIn by com.example.chord.data.auth.AuthStore.signedIn.collectAsState()
    androidx.compose.runtime.LaunchedEffect(signedIn) { com.example.chord.data.library.YtLibrary.refresh() }
    val ytLists by com.example.chord.data.library.YtLibrary.playlists.collectAsState()

    val playlists by viewModel.playlists.collectAsState(initial = emptyList())
    val recent by viewModel.recent.collectAsState(initial = emptyList())
    val most by viewModel.mostPlayed.collectAsState(initial = emptyList())
    val artists by viewModel.artists.collectAsState(initial = emptyList())

    BackHandler(enabled = openId != null || sub != null) { if (openId != null) openId = null else sub = null }

    // A playlist that was deleted while open closes itself.
    val open = openId
    if (open != null && playlists.isNotEmpty() && playlists.none { it.id == open }) openId = null

    if (open != null) {
        PlaylistScreen(
            playlistId = open, nowPlayingId = nowPlayingId, isPlaying = isPlaying,
            onBack = { openId = null }, onPlayQueue = onPlayQueue, onSearchArtist = onSearchArtist, modifier = modifier,
        )
        return
    }

    val page = sub
    if (page != null) {
        SubPage(page.title, onBack = { sub = null }, modifier) {
            when (page) {
                Sub.Playlists -> PlaylistsHub(
                    local = playlists,
                    onOpenLocal = { openId = it },
                    onOpenYt = onOpenBrowse,
                    onLogin = onLogin,
                    onCreate = { creating = true },
                )
                Sub.Device -> OnDeviceTab(nowPlayingId, isPlaying, onPlayQueue)
                Sub.Downloads -> DownloadsTab(nowPlayingId, isPlaying, onPlayQueue)
                Sub.Recent, Sub.Most -> SongList(
                    list = if (page == Sub.Recent) recent else most,
                    nowPlayingId = nowPlayingId, isPlaying = isPlaying,
                    onPlaySong = onPlaySong, onSearchArtist = onSearchArtist,
                )
            }
        }
        return
    }

    LazyColumn(
        modifier.fillMaxSize().background(ChordTheme.bg),
        contentPadding = PaddingValues(bottom = 220.dp),
    ) {
        item(key = "title") {
            Text(
                "Library", style = ChordType.Display, color = ChordTheme.text,
                modifier = Modifier.statusBarsPadding().padding(start = Gutter, end = Gutter, top = 14.dp, bottom = 18.dp),
            )
        }
        item(key = "listening-stats") {
            Column(Modifier.padding(top = 8.dp)) {
                Header("Your recaps")
                RecapShelf(onOpen = onOpenRecap, modifier = Modifier.fillMaxWidth())
            }
        }
        item(key = "rows") {
            Column {
                NavRow(Icons.Rounded.LibraryMusic, "Playlists") { sub = Sub.Playlists }
                RowDivider()
                NavRow(Icons.Rounded.History, "Recently played") { sub = Sub.Recent }
                RowDivider()
                NavRow(Icons.Rounded.BarChart, "Most played") { sub = Sub.Most }
                RowDivider()
                NavRow(Icons.Rounded.FolderOpen, "Local music") { sub = Sub.Device }
                RowDivider()
                NavRow(Icons.Rounded.DownloadForOffline, "Downloads") { sub = Sub.Downloads }
            }
        }
        item(key = "playlists") {
            Column(Modifier.padding(top = 36.dp)) {
                Header("Playlists")
                LazyRow(
                    contentPadding = PaddingValues(horizontal = Gutter),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    item(key = "new") {
                        ActionCard(Icons.Rounded.Add, "New playlist", "Saved on this phone") { creating = true }
                    }
                    item(key = "import") {
                        ActionCard(Icons.Rounded.Download, "Import", "From Spotify") { importing = true }
                    }
                    items(playlists, key = { it.id }) { p ->
                        PlaylistCard(p, onOpen = { openId = p.id }, onRename = { renaming = p }, onDelete = { deleting = p })
                    }
                    items(ytLists.take(15), key = { "yt" + it.browseId }) { p ->
                        YtCard(p) { onOpenBrowse(HomeItem(p.title, p.subtitle, p.thumbnailUrl, null, p.browseId, com.example.chord.data.home.HomeItemType.Playlist)) }
                    }
                    item(key = "all") { ActionCard(Icons.Rounded.LibraryMusic, "See all", "Synced + phone") { sub = Sub.Playlists } }
                }
            }
        }
        if (artists.isNotEmpty()) item(key = "artists") {
            Column(Modifier.padding(top = 36.dp)) {
                Header("Artists")
                LazyRow(
                    contentPadding = PaddingValues(horizontal = Gutter),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    items(artists, key = { it.title }) { a ->
                        Column(Modifier.width(CardSize).pressScale(pressedScale = 0.96f, onClick = { onSearchArtist(a.title) })) {
                            CoverImage(a.thumbnailUrl, CardSize, CardShape, Modifier.border(0.5.dp, ChordTheme.hairline, CardShape))
                            Text(
                                a.title, style = ChordType.CardTitle.copy(fontSize = 16.sp), color = ChordTheme.text,
                                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 10.dp),
                            )
                            Text(a.subtitle, style = ChordType.CardSub, color = ChordTheme.textDim, maxLines = 1)
                        }
                    }
                }
            }
        }
    }

    if (creating) NamePlaylistDialog("New playlist", "", "Create", { creating = false }) { name ->
        creating = false; viewModel.create(name)
    }
    renaming?.let { p ->
        NamePlaylistDialog("Rename playlist", p.name, "Save", { renaming = null }) { name ->
            renaming = null; viewModel.rename(p.id, name)
        }
    }
    if (importing) SpotifyImportDialog(
        onDismiss = { importing = false },
        onOpenPlaylist = { importing = false; openId = it },
    )
    deleting?.let { p ->
        ChordDialog(
            title = "Delete \"${p.name}\"?",
            body = "The songs stay in your history.",
            confirm = "Delete", danger = true,
            onDismiss = { deleting = null },
            onConfirm = { deleting = null; viewModel.delete(p.id) },
        )
    }
}

@Composable
private fun Header(text: String) {
    Text(text, style = ChordType.Section, color = ChordTheme.text, modifier = Modifier.padding(horizontal = Gutter).padding(bottom = 14.dp))
}

@Composable
private fun RowDivider() {
    Box(Modifier.padding(start = 64.dp).fillMaxWidth().height(0.5.dp).background(ChordTheme.hairline))
}

@Composable
private fun NavRow(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().hapticClickable(Haptic.Tap, onClick = onClick).padding(horizontal = Gutter, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = ChordTheme.text, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(20.dp))
        Text(label, color = ChordTheme.text, fontSize = 17.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = ChordTheme.textDim, modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun ActionCard(icon: ImageVector, title: String, sub: String, onClick: () -> Unit) {
    Column(Modifier.width(CardSize).pressScale(pressedScale = 0.96f, onClick = onClick)) {
        Box(
            Modifier.size(CardSize).clip(CardShape).background(ChordTheme.surface).border(0.5.dp, ChordTheme.hairline, CardShape),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, null, tint = ChordTheme.text, modifier = Modifier.size(38.dp)) }
        Text(
            title, style = ChordType.CardTitle.copy(fontSize = 16.sp), color = ChordTheme.text,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 10.dp),
        )
        Text(sub, style = ChordType.CardSub, color = ChordTheme.textDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun YtCard(p: com.example.chord.data.library.YtPlaylist, onOpen: () -> Unit) {
    Column(Modifier.width(CardSize).pressScale(pressedScale = 0.96f, onClick = onOpen)) {
        if (p.isLiked) Box(
            Modifier.size(CardSize).clip(CardShape)
                .background(androidx.compose.ui.graphics.Brush.linearGradient(listOf(Color(0xFFFF5F6D), Color(0xFF8E2DE2)))),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Rounded.Favorite, null, tint = Color.White, modifier = Modifier.size(44.dp)) }
        else CoverImage(p.thumbnailUrl, CardSize, CardShape, Modifier.border(0.5.dp, ChordTheme.hairline, CardShape))
        Text(
            p.title, style = ChordType.CardTitle.copy(fontSize = 16.sp), color = ChordTheme.text,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 10.dp),
        )
        Text("YouTube Music", style = ChordType.CardSub, color = ChordTheme.textDim, maxLines = 1)
    }
}

@Composable
private fun PlaylistCard(p: PlaylistSummary, onOpen: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Box {
        Column(
            Modifier.width(CardSize).clip(CardShape).hapticCombinedClickable(
                onLongClick = if (p.isFavourites) null else ({ menu = true }),
                onClick = onOpen,
            ),
        ) {
            PlaylistCover(p, CardSize, CardShape, Modifier.border(0.5.dp, ChordTheme.hairline, CardShape))
            Text(
                p.name, style = ChordType.CardTitle.copy(fontSize = 16.sp), color = ChordTheme.text,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 10.dp),
            )
            Text(
                if (p.isFavourites) "Auto playlist" else "${p.count} songs",
                style = ChordType.CardSub, color = ChordTheme.textDim, maxLines = 1,
            )
        }
        ChordMenu(expanded = menu, onDismiss = { menu = false }) {
            ChordMenuItem("Rename", Icons.Rounded.Edit) { menu = false; onRename() }
            ChordMenuItem("Delete", Icons.Rounded.Delete, danger = true) { menu = false; onDelete() }
        }
    }
}

@Composable
private fun SubPage(title: String, onBack: () -> Unit, modifier: Modifier, content: @Composable () -> Unit) {
    Column(modifier.fillMaxSize().background(ChordTheme.bg).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = Gutter, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(44.dp).clip(CircleShape).hapticClickable(Haptic.Tap, onClick = onBack),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = ChordTheme.text) }
            Text(title, style = ChordType.Section, color = ChordTheme.text, modifier = Modifier.padding(start = 6.dp))
        }
        content()
    }
}

@Composable
private fun SongList(
    list: List<HomeItem>,
    nowPlayingId: String?,
    isPlaying: Boolean,
    onPlaySong: (HomeItem) -> Unit,
    onSearchArtist: (String) -> Unit,
) {
    if (list.isEmpty()) {
        Text(
            "Play a song. It shows up here.", color = ChordTheme.textDim, fontSize = 15.sp,
            modifier = Modifier.fillMaxWidth().padding(32.dp),
        )
        return
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 220.dp)) {
        itemsIndexed(list, key = { i, it -> "$i:${it.videoId ?: it.title}" }, contentType = { _, _ -> "song" }) { _, item ->
            MediaRow(
                item = item,
                active = item.videoId != null && item.videoId == nowPlayingId,
                playing = isPlaying,
                onClick = { if (item.videoId != null) onPlaySong(item) else onSearchArtist(item.title) },
                modifier = Modifier.padding(horizontal = Gutter),
                onMoreFromArtist = onSearchArtist,
            )
        }
    }
}
