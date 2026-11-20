package com.example.chord.ui.search

import com.example.chord.ui.theme.ChordTheme
import android.content.Context
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.NorthWest
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.chord.data.home.HomeRepository
import com.example.chord.data.home.HomeItem
import com.example.chord.data.home.HomeItemType
import com.example.chord.data.home.HomeShelf
import com.example.chord.ui.home.Shelf
import androidx.compose.ui.graphics.Brush
import com.example.chord.data.home.SearchSuggestion
import com.example.chord.ui.components.CoverImage
import com.example.chord.ui.components.NowPlayingOverlay
import com.example.chord.ui.components.MediaRow
import androidx.compose.foundation.lazy.LazyRow
import com.example.chord.data.model.Song
import com.example.chord.ui.player.highResolutionArtworkUrl
import com.example.chord.ui.theme.ChordFont
import kotlinx.coroutines.delay
import org.json.JSONArray

private val Bg: Color get() = ChordTheme.bg
private val Dim = Color.White.copy(alpha = 0.58f)
private val Fill = Color.White.copy(alpha = 0.10f)
private val Accent = Color(0xFFFF4D6D)
private val KINDS = setOf("song", "video", "single", "album", "ep")

class SearchHistoryStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("search_history", Context.MODE_PRIVATE)

    fun load(): List<String> = runCatching {
        val a = JSONArray(prefs.getString("q", "[]"))
        List(a.length()) { a.getString(it) }
    }.getOrDefault(emptyList())

    fun add(query: String) {
        val t = query.trim().ifBlank { return }
        save((listOf(t) + load().filterNot { it.equals(t, ignoreCase = true) }).take(12))
    }

    fun remove(query: String) = save(load().filterNot { it == query })
    fun clear() = save(emptyList())
    private fun save(list: List<String>) = prefs.edit().putString("q", JSONArray(list).toString()).apply()
}

/** Rule: text = search. ID = play. Song rows keep their own videoId. */
private fun SearchSuggestion.toSong(): Song {
    val parts = subtitle.split(" • ", "•").map { it.trim() }.filter { it.isNotEmpty() }
    val artist = if (parts.size > 1 && parts.first().lowercase() in KINDS) parts[1] else parts.firstOrNull().orEmpty()
    return Song(title = text, details = artist, videoId = videoId.orEmpty(), thumbnailUrl = thumbnailUrl)
}

@Composable
fun SearchContent(
    query: String,
    onQueryChange: (String) -> Unit,
    submitted: String?,
    tab: SearchTab,
    onTab: (SearchTab) -> Unit,
    hits: List<HomeItem>,
    results: Map<SearchTab, List<HomeItem>>,
    discover: List<HomeShelf>,
    isLoading: Boolean,
    message: String,
    currentVideoId: String?,
    isPlaying: Boolean,
    onSubmit: (String) -> Unit,
    onPlay: (Song) -> Unit,
    onOpen: (HomeItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val store = remember { SearchHistoryStore(context) }
    val repo = remember { HomeRepository() }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val focusRequester = remember { FocusRequester() }

    var history by remember { mutableStateOf(store.load()) }
    var suggestions by remember { mutableStateOf(emptyList<SearchSuggestion>()) }

    fun submit(text: String) {
        val q = text.trim()
        if (q.isBlank()) return
        store.add(q)
        history = store.load()
        onSubmit(q)
        keyboard?.hide()
        focus.clearFocus()
    }

    fun playSong(song: Song) {
        keyboard?.hide()
        focus.clearFocus()
        store.add(query.trim())
        history = store.load()
        onPlay(song)
    }

    LaunchedEffect(query) {
        if (query.isBlank() || query.trim() == submitted) { suggestions = emptyList(); return@LaunchedEffect }
        delay(160)
        repo.suggestions(query.trim()).onSuccess { suggestions = it }
    }

    // Real search while typing: songs WITH artist, plus artists, albums, playlists.
    var live by remember { mutableStateOf(emptyList<HomeItem>()) }
    LaunchedEffect(query) {
        if (query.isBlank() || query.trim() == submitted) { live = emptyList(); return@LaunchedEffect }
        delay(260)
        // Old rows stay on screen until new ones land. No flicker.
        repo.search(query.trim()).onSuccess { live = it }
    }

    fun openLive(item: HomeItem) {
        keyboard?.hide()
        focus.clearFocus()
        store.add(query.trim())
        history = store.load()
        onOpen(item)
    }

    val showResults = submitted != null && query.trim() == submitted

    Column(modifier.fillMaxSize().background(Bg).statusBarsPadding()) {
        Text(
            "Search", color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.8).sp,
            modifier = Modifier.padding(start = 20.dp, top = 14.dp, bottom = 12.dp),
        )

        Row(
            Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(52.dp)
                .clip(RoundedCornerShape(26.dp)).background(Fill).padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.Search, null, tint = Dim, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(10.dp))
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = TextStyle(color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Medium, fontFamily = ChordFont),
                cursorBrush = SolidColor(Color.White),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { submit(query) }),
                modifier = Modifier.weight(1f).focusRequester(focusRequester),
                decorationBox = { inner ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (query.isEmpty()) Text("Songs, artists, albums, playlists", color = Dim, fontSize = 17.sp)
                        inner()
                    }
                },
            )
            if (query.isNotEmpty()) {
                Box(
                    Modifier.size(28.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.18f))
                        .clickable { onQueryChange(""); focusRequester.requestFocus() },
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Rounded.Close, "Clear", tint = Color.White, modifier = Modifier.size(16.dp)) }
            }
        }

        if (isLoading) LinearProgressIndicator(
            Modifier.fillMaxWidth().padding(top = 10.dp, start = 16.dp, end = 16.dp).height(2.dp),
            color = Color.White, trackColor = Color.Transparent,
        )
        Spacer(Modifier.height(8.dp))

        when {
            // 1. Results page: tabs + typed rows
            showResults -> ResultsPage(
                tab = tab, onTab = onTab, hits = hits, results = results, isLoading = isLoading, message = message,
                currentVideoId = currentVideoId, isPlaying = isPlaying, onOpen = onOpen,
            )

            // 2. Typing: text suggestions (search) + song rows (play exact ID)
            query.isNotBlank() -> {
                val textSugs = suggestions.filter { it.videoId == null }.take(5)
                val songSugs = suggestions.filter { it.videoId != null }.take(6)
                val liveItems = live.distinctBy { it.key() }.take(8)
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 200.dp)) {
                    items(textSugs, key = { "t:${it.text}" }) { s ->
                        Row(
                            Modifier.fillMaxWidth().clickable { submit(s.text) }.padding(start = 20.dp, end = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Rounded.Search, null, tint = Dim, modifier = Modifier.size(22.dp))
                            Spacer(Modifier.width(14.dp))
                            Text(
                                highlight(s.text, query.trim()),
                                fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f).padding(vertical = 10.dp),
                            )
                            Box(Modifier.size(40.dp).clickable { onQueryChange(s.text) }, contentAlignment = Alignment.Center) {
                                Icon(Icons.Rounded.NorthWest, "Use suggestion", tint = Dim, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                    if (liveItems.isNotEmpty()) {
                        item(key = "hdr") {
                            Text(
                                "Top matches", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(start = 20.dp, top = 10.dp, bottom = 2.dp),
                            )
                        }
                        items(liveItems, key = { "l:" + it.key() }) { h ->
                            SuggestItemRow(
                                h,
                                active = h.videoId != null && h.videoId == currentVideoId,
                                playing = isPlaying,
                            ) { openLive(h) }
                        }
                    } else if (songSugs.isNotEmpty()) {
                        // Live search not back yet: show the plain song suggestions.
                        item(key = "hdr") {
                            Text(
                                "Songs", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(start = 20.dp, top = 10.dp, bottom = 2.dp),
                            )
                        }
                        items(songSugs, key = { "s:${it.videoId}" }) { s ->
                            val song = s.toSong()
                            ResultRow(song, active = song.videoId == currentVideoId, playing = isPlaying) { playSong(song) }
                        }
                    }
                }
            }

            // 3. Empty box: recent + genres + shelves picked for you
            else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 200.dp)) {
                if (history.isNotEmpty()) {
                    item(key = "rhead") {
                        Row(
                            Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("Recent searches", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                            Text(
                                "Clear", color = Dim, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.clickable { store.clear(); history = emptyList() },
                            )
                        }
                    }
                    items(history.take(4), key = { "r:$it" }) { h ->
                        Row(
                            Modifier.fillMaxWidth().clickable { submit(h) }.padding(start = 20.dp, end = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Rounded.History, null, tint = Dim, modifier = Modifier.size(22.dp))
                            Spacer(Modifier.width(16.dp))
                            Text(
                                h, color = Color.White, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f).padding(vertical = 12.dp),
                            )
                            Box(Modifier.size(44.dp).clickable { store.remove(h); history = store.load() }, contentAlignment = Alignment.Center) {
                                Icon(Icons.Rounded.Close, "Remove", tint = Dim, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }

                item(key = "ghead") { SectionTitle("Browse all") }
                items(Genres.chunked(2), key = { "g:" + it.first().first }) { pair ->
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 5.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        pair.forEach { (name, color, q) ->
                            Box(
                                Modifier.weight(1f).height(72.dp).clip(RoundedCornerShape(14.dp))
                                    .background(Brush.linearGradient(listOf(color, color.copy(alpha = 0.55f))))
                                    .clickable { submit(q) }.padding(14.dp),
                                contentAlignment = Alignment.BottomStart,
                            ) { Text(name, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold) }
                        }
                        if (pair.size == 1) Spacer(Modifier.weight(1f))
                    }
                }

                if (discover.isEmpty()) item(key = "dload") {
                    Box(Modifier.fillMaxWidth().padding(28.dp), contentAlignment = Alignment.Center) {
                        androidx.compose.material3.CircularProgressIndicator(Modifier.size(24.dp), color = Color.White, strokeWidth = 2.dp)
                    }
                }
                items(discover, key = { "d:" + it.title }) { shelf ->
                    Shelf(shelf, currentVideoId, isPlaying, onOpen)
                }
            }
        }
    }
}

/** Name, colour, what to search. */
private val Genres = listOf(
    Triple("Pop", Color(0xFFE8115B), "pop hits"),
    Triple("Hip-Hop", Color(0xFFBA5D07), "hip hop hits"),
    Triple("Bollywood", Color(0xFFE13300), "bollywood hits"),
    Triple("Punjabi", Color(0xFF8D67AB), "punjabi hits"),
    Triple("Rock", Color(0xFF477D95), "rock classics"),
    Triple("Chill", Color(0xFF1E3264), "chill songs"),
    Triple("Lo-fi", Color(0xFF148A08), "lofi beats"),
    Triple("Workout", Color(0xFF537AA1), "workout songs"),
    Triple("Party", Color(0xFFAF2896), "party songs"),
    Triple("Romance", Color(0xFFDC148C), "romantic songs"),
    Triple("Focus", Color(0xFF503750), "focus music"),
    Triple("Sleep", Color(0xFF2D46B9), "sleep music"),
)

@Composable
fun PlayingIndicator(color: Color) {
    val t = rememberInfiniteTransition(label = "eq")
    val bar1 by t.animateFloat(0.3f, 1f, infiniteRepeatable(tween(400, easing = LinearEasing), RepeatMode.Reverse), label = "b1")
    val bar2 by t.animateFloat(1f, 0.4f, infiniteRepeatable(tween(350, easing = LinearEasing), RepeatMode.Reverse), label = "b2")
    val bar3 by t.animateFloat(0.5f, 1f, infiniteRepeatable(tween(450, easing = LinearEasing), RepeatMode.Reverse), label = "b3")

    Row(
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier.height(14.dp),
    ) {
        Box(Modifier.width(3.dp).fillMaxHeight(bar1).background(color, RoundedCornerShape(2.dp)))
        Box(Modifier.width(3.dp).fillMaxHeight(bar2).background(color, RoundedCornerShape(2.dp)))
        Box(Modifier.width(3.dp).fillMaxHeight(bar3).background(color, RoundedCornerShape(2.dp)))
    }
}

private fun highlight(suggestion: String, typed: String): AnnotatedString = buildAnnotatedString {
    val matches = typed.isNotEmpty() && suggestion.startsWith(typed, ignoreCase = true)
    if (matches) {
        withStyle(SpanStyle(color = Color.White.copy(alpha = 0.62f), fontWeight = FontWeight.Normal)) { append(suggestion.take(typed.length)) }
        withStyle(SpanStyle(color = Color.White, fontWeight = FontWeight.Bold)) { append(suggestion.drop(typed.length)) }
    } else {
        withStyle(SpanStyle(color = Color.White, fontWeight = FontWeight.SemiBold)) { append(suggestion) }
    }
}

@Composable
private fun ResultRow(song: Song, active: Boolean, playing: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = highResolutionArtworkUrl(song.thumbnailUrl ?: "https://i.ytimg.com/vi/${song.videoId}/hqdefault.jpg"),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(44.dp).clip(RoundedCornerShape(6.dp)).background(Fill),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                song.title, color = if (active) Accent else Color.White, fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            if (song.details.isNotBlank()) Row(verticalAlignment = Alignment.CenterVertically) {
                com.example.chord.ui.components.ExplicitMark(com.example.chord.data.explicit.ExplicitStore.has(song.videoId))
                Text(song.details, color = Dim, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (active && playing) {
            Box(Modifier.padding(start = 8.dp).size(24.dp), contentAlignment = Alignment.Center) {
                PlayingIndicator(Accent)
            }
        }
    }
}

private fun HomeItem.key() = "${type}:${videoId ?: browseId ?: title}"

/** Compact row for the typing screen: small cover, title, artist / album line. Artists are round. */
@Composable
private fun SuggestItemRow(item: HomeItem, active: Boolean, playing: Boolean, onClick: () -> Unit) {
    val round = item.type == HomeItemType.Artist
    val shape = if (round) CircleShape else RoundedCornerShape(6.dp)
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CoverImage(item.thumbnailUrl, 44.dp, shape)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                item.title, color = if (active) Accent else Color.White, fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            val artist = item.subtitle
                .split(" • ", "•")
                .map { it.trim() }
                .lastOrNull { it.isNotBlank() && it.lowercase() !in KINDS }
                .orEmpty()

            if (artist.isNotBlank()) Text(
                artist,
                color = Dim,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (active && playing) {
            Box(Modifier.padding(start = 8.dp).size(24.dp), contentAlignment = Alignment.Center) {
                PlayingIndicator(Accent)
            }
        }
    }
}

@Composable
private fun ResultsPage(
    tab: SearchTab,
    onTab: (SearchTab) -> Unit,
    hits: List<HomeItem>,
    results: Map<SearchTab, List<HomeItem>>,
    isLoading: Boolean,
    message: String,
    currentVideoId: String?,
    isPlaying: Boolean,
    onOpen: (HomeItem) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(bottom = 6.dp),
        ) {
            items(SearchTab.values().toList(), key = { it.name }) { t ->
                val on = t == tab
                Text(
                    t.label,
                    color = if (on) Color.Black else Color.White,
                    fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clip(RoundedCornerShape(50))
                        .background(if (on) Color.White else Fill)
                        .clickable { onTab(t) }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 200.dp)) {
            if (hits.isEmpty() && !isLoading) item(key = "empty") {
                Text(
                    message.ifBlank { "Nothing here" }, color = Dim, fontSize = 15.sp,
                    modifier = Modifier.fillMaxWidth().padding(32.dp),
                )
            }
            if (tab == SearchTab.All && hits.isNotEmpty()) {
                val top = hits.first()
                val rest = hits.drop(1)
                item(key = "toplabel") { SectionTitle("Top result") }
                item(key = "top:" + top.key()) {
                    TopCard(top, top.videoId != null && top.videoId == currentVideoId, isPlaying) { onOpen(top) }
                }
                // Filtered searches were loaded in parallel. Use them: more of each kind.
                fun pool(tab: SearchTab, type: HomeItemType): List<HomeItem> =
                    ((results[tab].orEmpty() + rest).filter { it.type == type }).distinctBy { it.key() }
                val songs = pool(SearchTab.Songs, HomeItemType.Song).filter { it.key() != top.key() }.take(5)
                if (songs.isNotEmpty()) {
                    item(key = "h:Songs") { SectionTitle("Songs", onMore = { onTab(SearchTab.Songs) }) }
                    items(songs, key = { "Songs:" + it.key() }) { h -> HitRow(h, currentVideoId, isPlaying, onOpen) }
                }
                fun cards(title: String, tab: SearchTab, type: HomeItemType) {
                    val list = pool(tab, type).filter { it.key() != top.key() }.take(12)
                    if (list.isEmpty()) return
                    item(key = "h:$title") { SectionTitle(title, onMore = { onTab(tab) }) }
                    item(key = "row:$title") { CardsRow(list, onOpen) }
                }
                cards("Artists", SearchTab.Artists, HomeItemType.Artist)
                cards("Albums", SearchTab.Albums, HomeItemType.Album)
                cards("Playlists", SearchTab.Playlists, HomeItemType.Playlist)
            } else {
                items(hits, key = { it.key() }) { h -> HitRow(h, currentVideoId, isPlaying, onOpen) }
            }
        }
    }
}

/** Horizontal covers: artists round, albums and playlists square. */
@Composable
private fun CardsRow(items: List<HomeItem>, onOpen: (HomeItem) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.padding(top = 6.dp),
    ) {
        items(items, key = { it.key() }) { item ->
            val round = item.type == HomeItemType.Artist
            val shape = if (round) CircleShape else RoundedCornerShape(12.dp)
            Column(
                Modifier.width(132.dp).clip(RoundedCornerShape(12.dp)).clickable { onOpen(item) },
                horizontalAlignment = if (round) Alignment.CenterHorizontally else Alignment.Start,
            ) {
                CoverImage(item.thumbnailUrl, 132.dp, shape)
                Text(
                    item.title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp),
                    textAlign = if (round) androidx.compose.ui.text.style.TextAlign.Center else androidx.compose.ui.text.style.TextAlign.Start,
                )
                if (item.subtitle.isNotBlank()) Text(
                    item.subtitle, color = Dim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String, onMore: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        if (onMore != null) Text(
            "See all", color = Dim, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.clickable { onMore() },
        )
    }
}

@Composable
private fun HitRow(item: HomeItem, currentVideoId: String?, isPlaying: Boolean, onOpen: (HomeItem) -> Unit) {
    MediaRow(
        item = item,
        active = item.videoId != null && item.videoId == currentVideoId,
        playing = isPlaying,
        onClick = { onOpen(item) },
        modifier = Modifier.padding(horizontal = 20.dp),
    )
}

@Composable
private fun TopCard(item: HomeItem, active: Boolean, playing: Boolean, onClick: () -> Unit) {
    val round = item.type == HomeItemType.Artist
    val shape = if (round) CircleShape else RoundedCornerShape(10.dp)
    Row(
        Modifier.padding(horizontal = 16.dp, vertical = 6.dp).fillMaxWidth()
            .clip(RoundedCornerShape(20.dp)).background(Fill)
            .clickable(onClick = onClick).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            CoverImage(item.thumbnailUrl, 96.dp, shape)
            if (active) NowPlayingOverlay(playing, Modifier.size(96.dp).clip(shape))
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(item.title, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(item.subtitle, color = Dim, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}