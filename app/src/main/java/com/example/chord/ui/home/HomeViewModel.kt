package com.example.chord.ui.home

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.chord.data.auth.AuthStore
import com.example.chord.data.history.HistoryStore
import com.example.chord.data.lastfm.LastFmApi
import com.example.chord.data.settings.AppSettings
import com.example.chord.data.home.HomeItem
import com.example.chord.data.home.HomeItemType
import com.example.chord.data.home.HomeRepository
import com.example.chord.data.home.ShelfStyle
import com.example.chord.ui.search.SearchTab
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import com.example.chord.data.home.HomeShelf
import com.example.chord.data.home.PickCard
import com.example.chord.data.home.PickKind
import com.example.chord.data.home.PickMood
import com.example.chord.data.repository.MusicRepository
import com.example.chord.data.mixes.MixEngine
import com.example.chord.data.mixes.Mix
import com.example.chord.data.mixes.MixGroup
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class HomeUiState(
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val loadingMore: Boolean = false,
    val shelves: List<HomeShelf> = emptyList(),
    val error: String? = null,
    val moreFailed: Boolean = false,
)

/** Local shelves keep their key when the first song changes, so scroll position stays. */
fun HomeShelf.stableKey(): String =
    if (local) "local|$title"
    else title + "|" + (items.firstOrNull()?.let { it.videoId ?: it.browseId ?: it.title } ?: "")

// File level on purpose: the class builds its state flow early, so members declared later would still be null.
private val YEAR = Regex("(19|20)\\d{2}")
private val TYPE_WORDS = setOf("album", "single", "ep", "song", "playlist", "artist")

private const val STALE_MS = 10 * 60_000L
private const val MAX_SHELVES = 26
private const val MAX_REMOTE = 18


/** Last.fm and YouTube Music format names differently (punctuation, featured artists,
 * "Topic", album suffixes). Compare normalized words instead of raw substring checks. */
private fun lastFmNameScore(wanted: String, actual: String): Double {
    fun clean(value: String) = value.lowercase()
        .replace(Regex("\\([^)]*\\)|\\[[^]]*]"), " ")
        .replace(Regex("[^\\p{L}\\p{N} ]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
    val a = clean(wanted)
    val b = clean(actual)
    if (a.isBlank() || b.isBlank()) return 0.0
    if (a == b) return 1.0
    if (a.contains(b) || b.contains(a)) return 0.95
    val aw = a.split(' ').filter { it.length > 1 }.toSet()
    val bw = b.split(' ').filter { it.length > 1 }.toSet()
    if (aw.isEmpty()) return 0.0
    return aw.intersect(bw).size.toDouble() / aw.size
}

/**
 * Same song or album never shows twice, but your own shelves and YouTube shelves
 * are checked apart. Before, YouTube shelves lost every song you already played,
 * went thin, and were dropped. Speed dial skips the check. Thin shelves go.
 */
private fun List<HomeShelf>.noRepeats(): List<HomeShelf> {
    val seenLocal = HashSet<String>()
    val seenRemote = HashSet<String>()
    val out = ArrayList<HomeShelf>(size)
    var remote = 0
    for (sh in this) {
        if (sh.style == ShelfStyle.SpeedDial) {
            if (sh.items.size >= 3) out += sh
            continue
        }
        // Grouped sections own their child shelves and must survive with no flat items.
        if (sh.children.isNotEmpty()) {
            out += sh
            continue
        }
        if (!sh.local && remote >= MAX_REMOTE) continue
        val seen = if (sh.local) seenLocal else seenRemote
        val keep = sh.items.filter { seen.add(it.videoId ?: it.browseId ?: ("t:" + it.title)) }
        val min = if (sh.local) (if (sh.isMix()) 1 else 3) else if (sh.isReplay()) 1 else 4
        if (keep.size >= min) {
            out += if (keep.size == sh.items.size) sh else sh.copy(items = keep)
            if (!sh.local) remote++
        }
        if (out.size >= MAX_SHELVES) break
    }
    return out
}

/** Title words that mark the YouTube replay shelf. Add more words here if YouTube names it other way. */
private val REPLAY_WORDS = listOf("replay", "listen again", "recap")

private fun HomeShelf.isMix() = local && items.isNotEmpty() && items.all { it.browseId?.startsWith("mix:") == true }

/**
 * Local mixes remain independent. Last.fm becomes one grouped Home section with
 * profile shelves (artists, albums, tracks, recent plays) and generated radio/chart mixes.
 */
private fun List<Mix>.toShelves(lastFmProfile: List<HomeShelf>): List<HomeShelf> {
    fun item(m: Mix) = HomeItem(
        m.title, m.subtitle, m.songs.firstOrNull()?.thumbnailUrl,
        null, "mix:" + m.id, HomeItemType.Playlist,
    )
    fun localShelf(title: String, strap: String, group: MixGroup) =
        filter { it.group == group }.takeIf { it.isNotEmpty() }
            ?.let { HomeShelf(title, strap, it.map(::item), false, true, ShelfStyle.Big) }

    val fm = filter { it.group == MixGroup.LastFm }
    fun fmShelf(title: String, strap: String, matches: (Mix) -> Boolean): HomeShelf? =
        fm.filter(matches).takeIf { it.isNotEmpty() }
            ?.let { HomeShelf(title, strap, it.map(::item), false, true, ShelfStyle.Big) }

    val lastFmChildren = buildList {
        addAll(lastFmProfile)
        fmShelf("Discover & similar artists", "New music based on your listening", {
            it.id == "fm_discover" || it.id.startsWith("fm_like")
        })?.let(::add)
        fmShelf("Your listening charts", "Tracks you played most on Last.fm", {
            it.id == "fm_week_chart" || it.id == "fm_month_chart"
        })?.let(::add)
        fmShelf("Artist rotation", "Top tracks from artists in your rotation", {
            it.id == "fm_artist_rotation"
        })?.let(::add)
        fmShelf("Album replays", "Tracks from albums in your Last.fm chart", {
            it.id.startsWith("fm_album_")
        })?.let(::add)
        fmShelf("Track radio", "More music related to tracks you love", {
            it.id.startsWith("fm_track_radio_")
        })?.let(::add)
        fmShelf("Imported profile mixes", "Music imported from a Last.fm profile", {
            it.id.startsWith("fm_imp_")
        })?.let(::add)
    }

    return listOfNotNull(
        localShelf("Daily mixes", "Made on your phone from what you play", MixGroup.Daily),
        localShelf("Mood mixes", "Your songs, sorted by feel", MixGroup.Mood),
        localShelf("Your replays", "Your listening, replayed", MixGroup.Replay),
        HomeShelf(
            title = "Last.fm",
            strapline = "Your listening profile, charts and discoveries",
            items = emptyList(),
            isList = false,
            local = true,
            style = ShelfStyle.Big,
            children = lastFmChildren,
        ).takeIf { it.children.isNotEmpty() },
    )
}

private fun HomeShelf.isYt(vararg words: String) = !local && words.any { title.contains(it, ignoreCase = true) }

/** Big cover cards for YouTube mixes, so they feel like a feature, not a plain row. */
private fun HomeShelf.styled(): HomeShelf =
    if (!local && style == ShelfStyle.Auto && !isList && isYt("mixed for you", "your mix", "supermix", "made for you", "albums for you", "discover"))
        copy(style = ShelfStyle.Big) else this

/**
 * Speed dial from YouTube itself: Quick picks + Listen again songs, then your own history to fill.
 * Only when signed in (YouTube knows you). Null = keep the local speed dial.
 */
private fun remoteDial(remote: List<HomeShelf>, local: List<HomeShelf>): HomeShelf? {
    val yt = remote.filter { it.isYt("quick picks", "listen again") }
        .flatMap { it.items }.filter { it.videoId != null }
    if (yt.size < 6) return null
    val mine = local.firstOrNull { it.style == ShelfStyle.SpeedDial }?.items.orEmpty()
    val items = (yt + mine).distinctBy { it.videoId }.take(18)
    return HomeShelf("Speed dial", "", items, false, true, ShelfStyle.SpeedDial)
}

private fun HomeShelf.isReplay() = !local && REPLAY_WORDS.any { title.contains(it, ignoreCase = true) }

/** Replay / recap cards inside YouTube shelves. Songs are skipped, so a song named "Replay" does not count. */
private fun List<HomeShelf>.replayItems(): List<HomeItem> =
    flatMap { it.items }
        .filter { it.type != HomeItemType.Song && REPLAY_WORDS.any { w -> it.title.contains(w, ignoreCase = true) } }
        .distinctBy { it.browseId ?: it.title }

/** Put those cards in one shelf of their own, first in the YouTube list so other shelves lose the duplicate. */
private fun List<HomeShelf>.withReplay(): List<HomeShelf> {
    val hits = replayItems()
    if (hits.isEmpty()) return this
    return listOf(HomeShelf("Your Replay", "", hits, false, false, ShelfStyle.Big)) + this
}

/**
 * ORDER ON SCREEN. Edit this list to move shelves.
 *   Top line = top of Home. Move a line up or down to move the shelf.
 *   Shelf that match no line stay below, in their old order (rest of YouTube shelves).
 */
private val ORDER: List<(HomeShelf) -> Boolean> = listOf(
    { it.style == ShelfStyle.TopPicks },                  // Top Picks for You
    { it.style == ShelfStyle.SpeedDial },                 // Speed dial (YouTube Quick picks + Listen again)
    { it.isReplay() },                                    // YouTube Listen again / Replay
    { it.isMix() && it.title == "Daily mixes" },          // Daily Mix 1-4 (phone)
    { it.isMix() && it.title == "Your replays" },          // On repeat, Replay month / year (phone)
    { it.title == "Last.fm" },                            // Dedicated Last.fm profile and recommendations
    { it.isYt("mixed for you", "your mix", "supermix", "daily discover") },   // YouTube mixes made for you
    { it.isYt("replay mix", "remix", "covers") },         // Replay mix, remixes
    { it.local && it.title == "Recents" },                // Recents
    { it.isYt("forgotten", "from your library", "albums for you", "similar to", "because you") },
    { it.isYt("new release", "new albums", "trending") },
    { it.local && it.title.endsWith(" mix") },            // Morning / Afternoon / Evening / Late night mix
    { it.local && it.title == "Your artists" },           // Your artists
    { it.local && it.title.startsWith("More like") },     // More like <artist>
)

/** Stable sort. Same slot keeps old order. No match = after all slots. */
private fun List<HomeShelf>.arranged(): List<HomeShelf> =
    sortedBy { sh -> ORDER.indexOfFirst { it(sh) }.let { if (it < 0) ORDER.size else it } }

/**
 * Order on screen:  your history  ->  "More like <top artist>"  ->  YouTube shelves.
 * Signed in: the YouTube shelves are your own YouTube Music Home.
 */
class HomeViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = HomeRepository()
    private val history = HistoryStore.get(app)

    /** Network state only. */
    private val net = MutableStateFlow(HomeUiState())
    /** Shelves built from a search for your top artist. */
    private val extra = MutableStateFlow<List<HomeShelf>>(emptyList())
    /** Artists + albums of your top artists. Mixed into Top Picks with your songs. */
    private val picks = MutableStateFlow<List<HomeItem>>(emptyList())
    /** Mood card with a real playlist behind it. Null until found. */
    private val mood = MutableStateFlow<PickCard?>(null)
    /**
     * Changes on every Home refresh so personalized cards rotate instead of repeating.
     * Starts at a time-based number, not 0, so every app open begins with a different mix.
     */
    private val pickRefresh = MutableStateFlow((System.currentTimeMillis() / 1000 % 10_000).toInt())
    /** Songs you have NOT played yet, from YouTube radio seeded by what you like. "New for you" cards. */
    private val fresh = MutableStateFlow<List<HomeItem>>(emptyList())
    private val lastFmProfile = MutableStateFlow<List<HomeShelf>>(emptyList())
    private val music = MusicRepository(app)
    private var lastFmProfileJob: Job? = null
    private var lastFmProfileLoadedAt = 0L
    private var lastFmProfileIdentity = ""

    private data class Disc(
        val mood: PickCard?,
        val rotation: Int,
        val forgotten: List<HomeItem>,
        val fresh: List<HomeItem>,
        val mixList: List<Mix>,
        val mixes: List<HomeShelf> = emptyList(),
    )

    val state: StateFlow<HomeUiState> =
        combine(
            net,
            history.shelves(),
            extra,
            picks,
            combine(
                combine(mood, pickRefresh, history.forgotten(), fresh, MixEngine.mixes) {
                        moodCard, rotation, forgotten, newSongs, mixList ->
                    Disc(moodCard, rotation, forgotten, newSongs, mixList)
                },
                lastFmProfile,
            ) { disc, profileShelves ->
                disc.copy(mixes = disc.mixList.toShelves(profileShelves))
            },
        ) { n, local0, more, mix, disc ->
            var local = local0.withMixedPicks(mix, disc.forgotten, disc.mood, disc.rotation, disc.fresh)
            val signedIn = AuthStore.signedIn.value
            val localDial = local.firstOrNull { it.style == ShelfStyle.SpeedDial }

            // During the first load, don't show the local dial and then replace it
            // a moment later when YouTube recommendations arrive. Wait for the
            // initial YouTube response, then build one combined dial.
            val dial = if (signedIn && !n.loading) remoteDial(n.shelves, local) else null
            val remoteShelves = if (dial != null) {
                n.shelves.filterNot { it.isYt("quick picks", "listen again") }
            } else {
                n.shelves
            }

            if (signedIn) {
                local = local.filterNot { it.style == ShelfStyle.SpeedDial }
                if (!n.loading) {
                    // Keep the local dial as a fallback if YouTube has too few
                    // Quick Picks / Listen Again tracks to make a useful mix.
                    (dial ?: localDial)?.let { local = local + it }
                }
            }
            local = local + disc.mixes
            // New install or little history: still show a real Top Picks, built from YouTube + mood.
            if (local.none { it.style == ShelfStyle.TopPicks }) {
                coldStartPicks(n.shelves, disc)?.let { local = listOf(it) + local }
            }
            n.copy(
                // Show history at once, do not wait for network.
                loading = n.loading && local.isEmpty(),
                shelves = (local + more + remoteShelves.withReplay()).distinctBy { it.stableKey() }.noRepeats().arranged(),
            )
        }.stateIn(viewModelScope, SharingStarted.Eagerly, HomeUiState())

    private var token: String? = null
    private var job: Job? = null
    private var moreJob: Job? = null
    private var loadedAt = 0L
    private var autoPages = 0

    init {
        MixEngine.init(app)
        refresh()
        watchTopArtist()
        // Sign in or out: reload so Home is yours.
        viewModelScope.launch { AuthStore.signedIn.drop(1).collect { refresh() } }
    }

    /** Home came on screen. Reload if the YouTube part is old. */
    fun onShown() {
        MixEngine.ensure()
        if (System.currentTimeMillis() - loadedAt > STALE_MS && !net.value.loading) refresh()
    }

    fun refresh() {
        MixEngine.ensure(force = true)
        loadLastFmProfile(force = false)
        history.reshuffle()
        val rotation = pickRefresh.value + 1
        pickRefresh.value = rotation
        loadMood(rotation)
        loadFresh(rotation)
        job?.cancel()
        moreJob?.cancel()
        job = viewModelScope.launch {
            net.update {
                if (it.shelves.isEmpty()) HomeUiState(loading = true)
                else it.copy(refreshing = true, loadingMore = false, moreFailed = false, error = null)
            }
            repo.home()
                .onSuccess { page ->
                    android.util.Log.d("HomeShelves", "YouTube shelves: " + page.shelves.joinToString(" | ") { it.title })
                    token = page.continuation
                    if (mood.value == null) loadMood(pickRefresh.value)
                    loadedAt = System.currentTimeMillis()
                    net.value = HomeUiState(loading = false, shelves = page.shelves.map { it.styled() }.dedup())
                    autoPages = 0
                    if (AuthStore.signedIn.value || page.shelves.size < 4 || net.value.shelves.replayItems().isEmpty()) autoLoad()
                }
                .onFailure { e ->
                    net.update {
                        if (it.shelves.isEmpty()) HomeUiState(loading = false, error = e.message ?: "Couldn't load Home")
                        else it.copy(refreshing = false)
                    }
                }
        }
    }

    /**
     * Refresh is intentionally scoped to Last.fm. The Home header calls this instead
     * of refreshing YouTube shelves or the local mixes.
     */
    fun refreshLastFm() {
        MixEngine.refreshLastFm()
        loadLastFmProfile(force = true)
    }

    /** Build real profile shelves from Last.fm and resolve tracks to playable YouTube items. */
    private fun loadLastFmProfile(force: Boolean) {
        val key = AppSettings.lastFmKey
        val user = AppSettings.lastFmUser.ifBlank { AppSettings.lastFmImportUser }
        if (key.isBlank() || user.isBlank()) {
            lastFmProfileJob?.cancel()
            lastFmProfileJob = null
            lastFmProfileLoadedAt = 0L
            lastFmProfileIdentity = ""
            lastFmProfile.value = emptyList()
            return
        }
        val now = System.currentTimeMillis()
        val identity = "$user|${key.hashCode()}"
        if (!force && identity == lastFmProfileIdentity && now - lastFmProfileLoadedAt < 30 * 60_000L) return
        if (!force && lastFmProfileJob?.isActive == true) return

        lastFmProfileJob?.cancel()
        lastFmProfileJob = viewModelScope.launch {
            val profile = runCatching {
                data class ProfileData(
                    val artists: List<String>,
                    val albums: List<LastFmApi.Album>,
                    val topTracks: List<LastFmApi.Track>,
                    val recentTracks: List<LastFmApi.Track>,
                )
                val data = coroutineScope {
                    val a = async { runCatching { LastFmApi.userTopArtists(key, user, "1month", 6) }.getOrDefault(emptyList()) }
                    val b = async { runCatching { LastFmApi.userTopAlbums(key, user, "1month", 6) }.getOrDefault(emptyList()) }
                    val t = async { runCatching { LastFmApi.userTopTracks(key, user, "overall", 12) }.getOrDefault(emptyList()) }
                    val r = async { runCatching { LastFmApi.userRecentTracks(key, user, 12) }.getOrDefault(emptyList()) }
                    ProfileData(a.await(), b.await(), t.await(), r.await())
                }
                val artistNames = data.artists
                val albumData = data.albums
                val topTrackData = data.topTracks
                val recentTrackData = data.recentTracks

                val artistItems = coroutineScope {
                    artistNames.map { name ->
                        async {
                            repo.search(name, SearchTab.Artists.params).getOrNull().orEmpty()
                                .asSequence()
                                .filter { it.type == HomeItemType.Artist && !it.browseId.isNullOrBlank() }
                                .map { it to lastFmNameScore(name, it.title) }
                                .filter { it.second >= 0.50 }
                                .maxByOrNull { it.second }
                                ?.first
                        }
                    }.awaitAll().filterNotNull().distinctBy { it.browseId }
                }
                val albumItems = coroutineScope {
                    albumData.map { album ->
                        async {
                            repo.search("${album.title} ${album.artist}", SearchTab.Albums.params).getOrNull().orEmpty()
                                .asSequence()
                                .filter { it.type == HomeItemType.Album && !it.browseId.isNullOrBlank() }
                                .map { item ->
                                    val titleScore = lastFmNameScore(album.title, item.title)
                                    val artistScore = if (album.artist.isBlank()) 0.5
                                        else lastFmNameScore(album.artist, item.subtitle)
                                    item to (titleScore * 2.0 + artistScore)
                                }
                                .filter { (item, score) ->
                                    lastFmNameScore(album.title, item.title) >= 0.50 &&
                                        (album.artist.isBlank() || lastFmNameScore(album.artist, item.subtitle) >= 0.30)
                                }
                                .maxByOrNull { it.second }
                                ?.first
                        }
                    }.awaitAll().filterNotNull().distinctBy { it.browseId }
                }
                val topSongs = MixEngine.resolveLastFmTracks(topTrackData).map {
                    HomeItem(it.title, it.details, it.thumbnailUrl, it.videoId, null, HomeItemType.Song)
                }
                val recentSongs = MixEngine.resolveLastFmTracks(recentTrackData).map {
                    HomeItem(it.title, it.details, it.thumbnailUrl, it.videoId, null, HomeItemType.Song)
                }

                buildList {
                    if (artistItems.isNotEmpty()) add(
                        HomeShelf("Your top artists", "Your most-played artists this month", artistItems, false, true, ShelfStyle.Auto)
                    )
                    if (albumItems.isNotEmpty()) add(
                        HomeShelf("Your top albums", "Albums you've returned to this month", albumItems, false, true, ShelfStyle.Big)
                    )
                    if (topSongs.isNotEmpty()) add(
                        HomeShelf("All-time top tracks", "Your most-played tracks on Last.fm", topSongs, true, true, ShelfStyle.Auto)
                    )
                    if (recentSongs.isNotEmpty()) add(
                        HomeShelf("Recently played on Last.fm", "Your latest scrobbles", recentSongs, true, true, ShelfStyle.Auto)
                    )
                }
            }.getOrDefault(emptyList())

            currentCoroutineContext().ensureActive()
            lastFmProfile.value = profile
            lastFmProfileIdentity = identity
            lastFmProfileLoadedAt = System.currentTimeMillis()
        }
    }

    /** Replay often sits on page 2 or 3 of YouTube Home. Fetch up to 3 more pages without scrolling. */
    private fun autoLoad() {
        if (token != null && autoPages++ < 6) loadMore()
    }

    fun loadMore() {
        val t = token ?: return
        val s = net.value
        if (s.loading || s.refreshing || s.loadingMore) return
        if (state.value.shelves.count { !it.local } >= MAX_REMOTE) return
        moreJob = viewModelScope.launch {
            net.update { it.copy(loadingMore = true, moreFailed = false) }
            repo.more(t)
                .onSuccess { page ->
                    token = page.continuation
                    net.update { cur ->
                        cur.copy(loadingMore = false, shelves = (cur.shelves + page.shelves.map { it.styled() }).dedup())
                    }
                    if (net.value.shelves.size < 14 || net.value.shelves.replayItems().isEmpty()) autoLoad()
                }
                .onFailure { net.update { it.copy(loadingMore = false, moreFailed = true) } }
        }
    }

    /** When your top artists change, fetch songs, artists and albums like theirs. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun watchTopArtist() {
        viewModelScope.launch {
            var previousNames: List<String>? = null
            combine(history.artists(), pickRefresh) { artists, rotation ->
                artists.take(3).map { it.title }.filter { it.isNotBlank() } to rotation
            }.collectLatest { (names, rotation) ->
                // Re-fetch personalized cards on refresh, but only rebuild the separate
                // "More like" shelf when listening history actually changes.
                picks.value = picksFor(names, rotation)
                if (names != previousNames) {
                    previousNames = names
                    extra.value = if (names.isEmpty()) emptyList() else moreLike(names.first())
                }
            }
        }
    }

    private suspend fun moreLike(name: String): List<HomeShelf> {
        val songs = repo.search(name).getOrNull().orEmpty().filter { it.videoId != null }.take(12)
        return if (songs.size >= 4) {
            listOf(HomeShelf("More like $name", "Because you listen to them", songs, isList = false, local = true))
        } else emptyList()
    }

    /** For each top artist: the real artist page item + two albums. */
    private suspend fun picksFor(names: List<String>, rotation: Int): List<HomeItem> = coroutineScope {
        // Blend Last.fm's monthly artist chart with local listening history.
        val fmData = if (AppSettings.lastFmOn && AppSettings.lastFmUser.isNotBlank()) {
            runCatching {
                coroutineScope {
                    val artists = async { LastFmApi.userTopArtists(AppSettings.lastFmKey, AppSettings.lastFmUser, "1month", 8) }
                    val albums = async { LastFmApi.userTopAlbums(AppSettings.lastFmKey, AppSettings.lastFmUser, "1month", 8) }
                    artists.await() to albums.await()
                }
            }.getOrNull()
        } else null
        val fmNames = fmData?.first.orEmpty()
        val orderedNames = (names + fmNames).distinctBy { it.lowercase() }.rotated(rotation).take(6)
        val artistAndAlbumItems = orderedNames.map { n ->
            async {
                val artist = repo.search(n, SearchTab.Artists.params).getOrNull()
                    ?.firstOrNull { it.type == HomeItemType.Artist && it.browseId != null }
                val albums = repo.search(n, SearchTab.Albums.params).getOrNull().orEmpty()
                    .filter { it.type == HomeItemType.Album && it.browseId != null }
                    .let { all -> all.filter { it.subtitle.contains(n, ignoreCase = true) }.ifEmpty { all } }
                    .take(6)
                    .rotated(rotation)
                    .take(2)
                listOfNotNull(artist) + albums
            }
        }.awaitAll().flatten()
        val fmAlbumItems = fmData?.second.orEmpty().map { album ->
            async {
                repo.search(album.title, SearchTab.Albums.params).getOrNull().orEmpty()
                    .firstOrNull { it.type == HomeItemType.Album && it.browseId != null &&
                        (it.title.equals(album.title, true) || it.title.contains(album.title, true)) }
            }
        }.awaitAll().filterNotNull()
        (artistAndAlbumItems + fmAlbumItems).distinctBy { it.browseId ?: it.title }
    }

    private fun <T> List<T>.rotated(amount: Int): List<T> {
        if (size < 2) return this
        val shift = Math.floorMod(amount, size)
        return drop(shift) + take(shift)
    }

    /** Find a real playlist for this hour's mood. Mood and playlist both change between refreshes. */
    private fun loadMood(rotation: Int) {
        viewModelScope.launch {
            val m = PickMood.pick(rotation)
            val hits = repo.search(m.query, SearchTab.Playlists.params).getOrNull().orEmpty()
                .filter { it.type == HomeItemType.Playlist && it.browseId != null }
                .take(4)
            val hit = hits.getOrNull(Math.floorMod(rotation, hits.size.coerceAtLeast(1)))
            mood.value = hit?.let { PickCard(PickKind.Mood, m.title, "Mood", m.blurb, listOf(it.thumbnailUrl), it, m) }
        }
    }

    /**
     * "New for you": ask YouTube radio for songs like the ones you love, drop everything you
     * already played, keep a few. Seeds change with every refresh, so the cards do too.
     */
    private fun loadFresh(rotation: Int) {
        viewModelScope.launch {
            val seeds = history.seedSongs(20).shuffled(java.util.Random(rotation.toLong())).take(3)
            if (seeds.isEmpty()) { fresh.value = emptyList(); return@launch }
            val known = history.knownIds()
            val found = coroutineScope {
                seeds.map { seed ->
                    async {
                        runCatching { music.getRadioPage(seed.videoId.orEmpty(), null, blend = false).songs }.getOrDefault(emptyList())
                    }
                }.awaitAll()
            }
            fresh.value = found.flatten()
                .filter { it.videoId.isNotBlank() && it.videoId !in known }
                .distinctBy { it.videoId }
                .shuffled(java.util.Random(rotation.toLong() * 31 + 7))
                .take(6)
                .map {
                    HomeItem(
                        it.title, it.details,
                        it.thumbnailUrl ?: "https://i.ytimg.com/vi/${it.videoId}/hqdefault.jpg",
                        it.videoId, null, HomeItemType.Song,
                    )
                }
        }
    }

    /** No listening history yet: Top Picks from YouTube Home + a mood card, so the shelf is never empty. */
    private fun coldStartPicks(remote: List<HomeShelf>, d: Disc): HomeShelf? {
        val pool = remote.filter { !it.local && it.style != ShelfStyle.Hero }
            .flatMap { it.items }
            .filter { it.videoId != null || it.browseId != null }
            .distinctBy { it.videoId ?: it.browseId ?: it.title }
            .rotated(d.rotation)
            .take(8)
        val items = (pool + d.fresh).distinctBy { it.videoId ?: it.browseId ?: it.title }.take(10)
        if (items.size < 3) return null
        val cards = ArrayList<PickCard>()
        d.fresh.take(2).forEach { cards += freshCard(it) }
        d.mood?.let { cards += it }
        pool.forEach { i ->
            cards += PickCard(
                PickKind.Item, i.title,
                when (i.type) { HomeItemType.Artist -> "Artist"; HomeItemType.Album -> "Album"; HomeItemType.Playlist -> "Playlist"; else -> "Trending for you" },
                i.subtitle, listOf(i.thumbnailUrl), i,
            )
        }
        return HomeShelf("Top Picks for You", "", items, false, true, ShelfStyle.TopPicks, cards.distinctBy { it.title + it.label }.take(12))
    }

    private fun freshCard(i: HomeItem) =
        PickCard(PickKind.Item, i.title, "New for you", HistoryStore.cleanArtist(i.subtitle).ifBlank { i.subtitle }, listOf(i.thumbnailUrl), i)

    private fun yearOf(item: HomeItem): Int = YEAR.find(item.subtitle)?.value?.toIntOrNull() ?: 0

    private fun artistOf(item: HomeItem): String =
        item.subtitle.split("•", "·").map { it.trim() }
            .firstOrNull { it.isNotBlank() && it.lowercase() !in TYPE_WORDS && !YEAR.matches(it) }.orEmpty()

    /**
     * Top Picks cards, in this order:
     *   New Release Mix, <Artist> & Similar Artists, Mood, Heavy Rotation, then your songs, artists, albums.
     * A card is left out when its data is missing. Plain cover cards stay in [HomeShelf.items] as fallback.
     */
    private fun List<HomeShelf>.withMixedPicks(
        extra: List<HomeItem>,
        forgotten: List<HomeItem>,
        moodCard: PickCard?,
        rotation: Int,
        newSongs: List<HomeItem>,
    ): List<HomeShelf> {
        return map { sh ->
            if (sh.style != ShelfStyle.TopPicks) return@map sh
            val hero = sh.items.firstOrNull()
            val artists = extra.filter { it.type == HomeItemType.Artist }
            val albums = extra.filter { it.type == HomeItemType.Album }

            // Plain cards: hero stays first, then rotate artist, album, song.
            val songItems = sh.items.drop(1)
            val songShift = Math.floorMod(rotation, songItems.size.coerceAtLeast(1))
            val songs = (songItems.drop(songShift) + songItems.take(songShift)).iterator()
            val ar = artists.iterator()
            val al = albums.iterator()
            val mixed = ArrayList<HomeItem>()
            hero?.let { mixed += it }
            while (songs.hasNext() || ar.hasNext() || al.hasNext()) {
                if (ar.hasNext()) mixed += ar.next()
                if (al.hasNext()) mixed += al.next()
                if (songs.hasNext()) mixed += songs.next()
            }
            val items = mixed.distinctBy { it.videoId ?: it.browseId ?: it.title }.take(10)

            // Order of cards: new things first, then your mood, then songs to hear again.
            //   New Release Mix, New for you, Similar Artists, Mood, New for you, Listen again, Heavy Rotation, rest.
            val cards = ArrayList<PickCard>()
            albums.sortedByDescending { yearOf(it) }
                .getOrNull(Math.floorMod(rotation, albums.size.coerceAtLeast(1)))?.let { newest ->
                    val names = (albums.map { artistOf(it) } + artists.map { it.title })
                        .filter { it.isNotBlank() }.distinct().take(4).joinToString(", ")
                    cards += PickCard(PickKind.NewRelease, "New Release Mix", "Made for You", names, listOf(newest.thumbnailUrl), newest)
                }
            newSongs.getOrNull(0)?.let { cards += freshCard(it) }
            artists.firstOrNull()?.let { top ->
                val faces = (artists.map { it.thumbnailUrl } + albums.map { it.thumbnailUrl }).filterNotNull().distinct().take(3)
                cards += PickCard(PickKind.Similar, "${top.title} & Similar Artists", "Featuring ${top.title}", "", faces, top)
            }
            moodCard?.let { cards += it }
            newSongs.getOrNull(1)?.let { cards += freshCard(it) }

            // Songs you played a lot but not for 30+ days. Kept apart from ordinary history picks.
            val alreadyIncluded = buildSet {
                addAll(items.mapNotNull { it.videoId })
                hero?.videoId?.let(::add)
            }
            val forgottenCandidates = forgotten
                .asSequence()
                .filter { it.videoId != null && it.videoId !in alreadyIncluded }
                .distinctBy { it.videoId }
                .toList()

            if (forgottenCandidates.isNotEmpty()) {
                val offset = Math.floorMod(rotation, forgottenCandidates.size)
                (forgottenCandidates.drop(offset) + forgottenCandidates.take(offset)).take(2).forEach { song ->
                    cards += PickCard(
                        PickKind.Item,
                        song.title,
                        "Listen again",
                        HistoryStore.cleanArtist(song.subtitle).ifBlank { "A favourite worth revisiting" },
                        listOf(song.thumbnailUrl),
                        song,
                    )
                }
            }
            newSongs.getOrNull(2)?.let { cards += freshCard(it) }

            hero?.let {
                cards += PickCard(PickKind.Rotation, "Heavy Rotation", "Your most played", listOf(it.title, it.subtitle).filter { t -> t.isNotBlank() }.joinToString(" · "), listOf(it.thumbnailUrl), it)
            }
            newSongs.drop(3).forEach { cards += freshCard(it) }

            items.filter { it.videoId != hero?.videoId || hero == null }.forEach { i ->
                cards += PickCard(
                    PickKind.Item, i.title,
                    when (i.type) { HomeItemType.Artist -> "Artist"; HomeItemType.Album -> "Album"; HomeItemType.Playlist -> "Playlist"; else -> "Song" },
                    i.subtitle, listOf(i.thumbnailUrl), i,
                )
            }
            sh.copy(items = items, picks = cards.distinctBy { it.kind.name + it.title + it.label }.take(14))
        }
    }

    private fun List<HomeShelf>.dedup() = distinctBy { it.stableKey() }
}