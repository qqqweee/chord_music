package com.example.chord.data.home

import android.util.Log
import com.example.chord.data.explicit.ExplicitStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

enum class HomeItemType { Song, Album, Playlist, Artist, Other }

@androidx.compose.runtime.Immutable
data class HomeItem(
    val title: String,
    val subtitle: String,
    val thumbnailUrl: String?,
    val videoId: String?,
    val browseId: String?,
    val type: HomeItemType,
)

data class SearchSuggestion(
    val text: String,
    val thumbnailUrl: String?,
    val videoId: String? = null,   // non-null = playable song row
    val subtitle: String = "",     // "Song • Artist • Album"
)

/** How Home draws a shelf. Auto = decide from the items. */
enum class ShelfStyle { Auto, Hero, SpeedDial, Big, TopPicks }

/** What a Top Picks card is. Each kind has its own look in HomeShelves. */
enum class PickKind { Item, NewRelease, Similar, Mood, Rotation }

/** Moods for the Top Picks mood card. [query] finds a real playlist to open. */
enum class PickMood(val title: String, val blurb: String, val query: String, val top: Long, val bottom: Long) {
    Heartbreak("Heartbreak", "Breakups happen, one song at a time.", "heartbreak songs", 0xFFEF3B26, 0xFFE5311F),
    RiseShine("Rise & Shine", "Gentle starts for slow mornings.", "morning feel good songs", 0xFFF59E0B, 0xFFEA580C),
    Focus("Focus", "Lock in. Tune the world out.", "focus music", 0xFF2563EB, 0xFF1E3A8A),
    WindDown("Wind Down", "Ease out of the day.", "chill evening songs", 0xFF7C3AED, 0xFF4C1D95),
    LateNight("Late Night", "For the ones still awake.", "late night vibes", 0xFF0F766E, 0xFF134E4A),
    Workout("Workout", "Turn it up. Keep moving.", "workout motivation songs", 0xFFDC2626, 0xFF7F1D1D);

    companion object {
        /** Moods that fit this hour. Time sets the pool, [seed] picks inside it, so each Home refresh can differ. */
        fun pick(seed: Int): PickMood {
            val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
            val pool = when (hour) {
                in 5..10 -> listOf(RiseShine, Focus, Workout)
                in 11..16 -> listOf(Focus, Workout, RiseShine)
                in 17..21 -> listOf(WindDown, Workout, Heartbreak, Focus)
                else -> listOf(LateNight, WindDown, Heartbreak)
            }
            return pool[Math.floorMod(seed, pool.size)]
        }

        fun now(): PickMood = pick(java.util.Calendar.getInstance().get(java.util.Calendar.DAY_OF_YEAR))
    }
}

/** One card in Top Picks. [open] is what a tap does: play it (videoId) or open it (browseId). */
@androidx.compose.runtime.Immutable
data class PickCard(
    val kind: PickKind,
    val title: String,
    val label: String,
    val subtitle: String,
    val images: List<String?>,
    val open: HomeItem?,
    val mood: PickMood? = null,
)

@androidx.compose.runtime.Immutable
data class HomeShelf(
    val title: String,
    val strapline: String,
    val items: List<HomeItem>,
    val isList: Boolean,
    val local: Boolean = false,
    val style: ShelfStyle = ShelfStyle.Auto,
    /** Top Picks only. Typed cards. Empty = draw [items] as plain cover cards. */
    val picks: List<PickCard> = emptyList(),
    /** Nested shelves are used by grouped Home sections such as the dedicated Last.fm area. */
    val children: List<HomeShelf> = emptyList(),
)

data class ArtistPage(
    val name: String,
    val subtitle: String,
    val description: String,
    val thumbnailUrl: String?,
    val songs: List<HomeItem>,
    val shelves: List<HomeShelf>,
)
data class HomePage(val shelves: List<HomeShelf>, val continuation: String?)

class HomeRepository {

    @Volatile private var visitorData: String? = null
    private val region = Locale.getDefault().country.ifBlank { "US" }

    suspend fun home(songsOnly: Boolean = true): Result<HomePage> = runCatching {
        ensureVisitorData()
        val root = post("browse", emptyMap()) { put("browseId", "FEmusic_home") }
        debugFind(root)
        pageOf(root, songsOnly)
    }

    /** Debug. Logs where the words replay / recap sit in the Home reply. Filter Logcat by HomeRepository. */
    private fun debugFind(root: JSONObject) {
        var hits = 0
        fun walk(n: Any?, path: String) {
            if (hits >= 30) return
            when (n) {
                is JSONObject -> n.keys().forEach { k ->
                    val v = n.opt(k)
                    if (v is String && REPLAY_RE.containsMatchIn(v)) { hits++; Log.d(TAG, "FOUND '$v' at $path.$k") }
                    else walk(v, "$path.$k")
                }
                is JSONArray -> for (i in 0 until n.length()) walk(n.opt(i), "$path[$i]")
            }
        }
        walk(root, "root")
        if (hits == 0) Log.d(TAG, "no replay / recap text in Home reply")
    }

    suspend fun more(token: String, songsOnly: Boolean = true): Result<HomePage> = runCatching {
        pageOf(
            post("browse", mapOf("ctoken" to token, "continuation" to token, "type" to "next")) {
                put("continuation", token)
            },
            songsOnly,
        )
    }

    /**
     * Up-next radio for a song. SIGNED-IN request (login cookie), so YouTube tailors it to you.
     * [token] null = first page. Raw JSON back; SmartRadio parses it.
     */
    suspend fun radio(seed: String, token: String?): Result<JSONObject> = runCatching {
        ensureVisitorData()
        if (token == null) {
            post("next", emptyMap(), fast = true) {
                put("videoId", seed)
                put("playlistId", "RDAMVM$seed")
                put("params", "wAEB")
                put("isAudioOnly", true)
                put("tunerSettingValue", "AUTOMIX_SETTING_NORMAL")
            }
        } else {
            post("next", mapOf("ctoken" to token, "continuation" to token, "type" to "next"), fast = true) {
                put("continuation", token)
                put("isAudioOnly", true)
                put("tunerSettingValue", "AUTOMIX_SETTING_NORMAL")
            }
        }
    }

    /** Live search suggestions returning rich objects (text + optional image). */
    suspend fun suggestions(input: String): Result<List<SearchSuggestion>> = runCatching {
        ensureVisitorData()
        // auth = false: typing in Chord must never land in your YouTube search history.
        val root = post("music/get_search_suggestions", emptyMap(), auth = false) { put("input", input) }
        val outMap = LinkedHashMap<String, SearchSuggestion>()

        fun walk(n: Any?) {
            when (n) {
                is JSONObject -> {
                    val suggestionRenderer = n.optJSONObject("searchSuggestionRenderer")
                    if (suggestionRenderer != null) {
                        val text = runs(suggestionRenderer.optJSONObject("suggestion"))
                        if (text.isNotBlank()) {
                            val key = "t:" + text.lowercase()
                            if (!outMap.containsKey(key)) {
                                outMap[key] = SearchSuggestion(text, null)
                            }
                        }
                    }

                    val responsiveRenderer = n.optJSONObject("musicResponsiveListItemRenderer")
                    if (responsiveRenderer != null) {
                        val cols = responsiveRenderer.optJSONArray("flexColumns")
                        fun col(i: Int) = cols?.optJSONObject(i)
                            ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")?.optJSONObject("text")
                        val text = runs(col(0))
                        val videoId = responsiveRenderer.optJSONObject("playlistItemData")
                            ?.optString("videoId")?.takeIf { it.isNotBlank() }
                            ?: responsiveRenderer.optJSONObject("navigationEndpoint")
                                ?.optJSONObject("watchEndpoint")?.optString("videoId")?.takeIf { it.isNotBlank() }
                            ?: col(0)?.optJSONArray("runs")?.optJSONObject(0)?.optJSONObject("navigationEndpoint")
                                ?.optJSONObject("watchEndpoint")?.optString("videoId")?.takeIf { it.isNotBlank() }
                        val thumbUrl = thumb(responsiveRenderer.optJSONObject("thumbnail")?.optJSONObject("musicThumbnailRenderer"))

                        // songs only. Artist/album rows have no videoId. Skip them: dead taps.
                        if (text.isNotBlank() && videoId != null) {
                            ExplicitStore.mark(videoId, responsiveRenderer)
                            outMap.putIfAbsent("v:$videoId", SearchSuggestion(text, thumbUrl, videoId, runs(col(1))))
                        }
                    }

                    n.keys().forEach { walk(n.opt(it)) }
                }
                is JSONArray -> for (i in 0 until n.length()) walk(n.opt(i))
            }
        }
        walk(root)
        outMap.values.take(10).toList()
    }

    suspend fun browse(browseId: String): Result<JSONObject> = runCatching {
        ensureVisitorData()
        post("browse", emptyMap()) { put("browseId", browseId) }
    }

    /** Next page of a long playlist or album. */
    suspend fun browseContinuation(token: String): Result<JSONObject> = runCatching {
        post("browse", mapOf("ctoken" to token, "continuation" to token, "type" to "next")) {
            put("continuation", token)
        }
    }


    /** Your saved playlists and albums. Needs sign-in. */
    suspend fun libraryPlaylists(): Result<List<HomeItem>> = runCatching {
        ensureVisitorData()
        val root = post("browse", emptyMap()) { put("browseId", "FEmusic_liked_playlists") }
        val out = LinkedHashMap<String, HomeItem>()
        fun walk(n: Any?) {
            when (n) {
                is JSONObject -> {
                    n.optJSONObject("musicTwoRowItemRenderer")?.let { r ->
                        twoRow(r, false)?.takeIf { it.type == HomeItemType.Playlist && it.browseId != null }
                            ?.let { out.putIfAbsent(it.browseId!!, it) }
                    }
                    n.keys().forEach { walk(n.opt(it)) }
                }
                is JSONArray -> for (i in 0 until n.length()) walk(n.opt(i))
            }
        }
        walk(root)
        // Long libraries come in pages. Follow them, so no playlist is missing.
        var tok = findToken(root)
        var pages = 0
        while (tok != null && pages++ < 8) {
            val t: String = tok
            val next = post("browse", mapOf("ctoken" to t, "continuation" to t, "type" to "next")) { put("continuation", t) }
            walk(next)
            tok = findToken(next)?.takeIf { it != t }
        }
        out.values.toList()
    }

    /** Search. [filter] is a YT Music params string (see SearchTab). Null = all types. */
    suspend fun search(query: String, filter: String? = null): Result<List<HomeItem>> = runCatching {
        ensureVisitorData()
        // auth = false: no login cookie on search, so nothing is written to your YouTube account.
        val root = post("search", emptyMap(), auth = false) {
            put("query", query)
            filter?.let { put("params", it) }
        }
        val out = LinkedHashMap<String, HomeItem>()
        fun add(i: HomeItem?) {
            if (i == null || i.type == HomeItemType.Other) return
            out.putIfAbsent(i.videoId ?: i.browseId ?: i.title, i)
        }
        fun walk(n: Any?) {
            when (n) {
                is JSONObject -> {
                    n.optJSONObject("musicCardShelfRenderer")?.let { add(topCard(it)) }
                    n.optJSONObject("musicResponsiveListItemRenderer")?.let { add(searchRow(it)) }
                    n.keys().forEach { walk(n.opt(it)) }
                }
                is JSONArray -> for (i in 0 until n.length()) walk(n.opt(i))
            }
        }
        walk(root)
        out.values.toList()
    }

    private fun topCard(c: JSONObject): HomeItem? {
        val title = runs(c.optJSONObject("title")).takeIf { it.isNotBlank() } ?: return null
        val subtitle = runs(c.optJSONObject("subtitle"))
        val thumb = thumb(c.optJSONObject("thumbnail")?.optJSONObject("musicThumbnailRenderer"))
        val nav = c.optJSONObject("title")?.optJSONArray("runs")?.optJSONObject(0)?.optJSONObject("navigationEndpoint")
        val watch = nav?.optJSONObject("watchEndpoint")
        val vid = watch?.optString("videoId")?.takeIf { it.isNotBlank() }
        if (vid != null) return if (isVideoType(watch)) null else { ExplicitStore.mark(vid, c); HomeItem(title, subtitle, thumb, vid, null, HomeItemType.Song) }
        return browseItem(title, subtitle, thumb, nav)
    }

    private fun searchRow(r: JSONObject): HomeItem? {
        val cols = r.optJSONArray("flexColumns") ?: return null
        fun col(i: Int) = cols.optJSONObject(i)?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")?.optJSONObject("text")
        val title = runs(col(0)).takeIf { it.isNotBlank() } ?: return null
        val subtitle = runs(col(1))
        val thumb = thumb(r.optJSONObject("thumbnail")?.optJSONObject("musicThumbnailRenderer"))
        val videoId = r.optJSONObject("playlistItemData")?.optString("videoId")?.takeIf { it.isNotBlank() }
        if (videoId != null) {
            val watch = col(0)?.optJSONArray("runs")?.optJSONObject(0)?.optJSONObject("navigationEndpoint")?.optJSONObject("watchEndpoint")
            if (isVideoType(watch)) return null
            ExplicitStore.mark(videoId, r)
            return HomeItem(title, subtitle, thumb, videoId, null, HomeItemType.Song)
        }
        return browseItem(title, subtitle, thumb, r.optJSONObject("navigationEndpoint"))
    }

    /** Explore tab: new releases, charts, moods carousels. */
    suspend fun explore(): Result<HomePage> = runCatching {
        ensureVisitorData()
        pageOf(post("browse", emptyMap()) { put("browseId", "FEmusic_explore") }, true)
    }

    /** Country-specific YouTube Music charts. Uses the same region selector as music.youtube.com/charts. */
    suspend fun charts(countryCode: String): Result<HomePage> = runCatching {
        require(countryCode.matches(Regex("[A-Z]{2}"))) { "Invalid chart region" }
        ensureVisitorData()
        val root = post("browse", emptyMap()) {
            put("browseId", "FEmusic_charts")
            put("formData", JSONObject().put("selectedValues", JSONArray().put(countryCode)))
        }
        pageOf(root, false, includeVideoShelves = true)
    }

    /** Artist page: header, top songs, then carousels (albums, singles, similar artists). */
    suspend fun artist(browseId: String): Result<ArtistPage> = runCatching {
        ensureVisitorData()
        val root = post("browse", emptyMap()) { put("browseId", browseId) }
        val header = root.optJSONObject("header")
        val h = header?.optJSONObject("musicImmersiveHeaderRenderer") ?: header?.optJSONObject("musicVisualHeaderRenderer")
        val name = runs(h?.optJSONObject("title"))
        val monthly = runs(h?.optJSONObject("monthlyListenerCount"))
        val subs = runs(
            h?.optJSONObject("subscriptionButton")?.optJSONObject("subscribeButtonRenderer")
                ?.optJSONObject("subscriberCountText")
        )
        val shelfNode = firstObj(root, "musicShelfRenderer")
        val rows = shelfNode?.optJSONArray("contents")
        val songs = if (rows == null) emptyList() else (0 until rows.length()).mapNotNull { i ->
            rows.optJSONObject(i)?.optJSONObject("musicResponsiveListItemRenderer")?.let { listRow(it, true) }
        }.filter { it.videoId != null }
        val carousels = ArrayList<JSONObject>().also { collect(root, it) }
        ArtistPage(
            name = name,
            subtitle = monthly.ifBlank { subs },
            description = runs(h?.optJSONObject("description")),
            thumbnailUrl = thumb(h?.optJSONObject("thumbnail")?.optJSONObject("musicThumbnailRenderer")),
            songs = songs,
            shelves = carousels.mapNotNull { shelf(it, true) },
        )
    }

    private fun firstObj(node: Any?, key: String): JSONObject? = when (node) {
        is JSONObject -> node.optJSONObject(key)
            ?: node.keys().asSequence().firstNotNullOfOrNull { firstObj(node.opt(it), key) }
        is JSONArray -> (0 until node.length()).firstNotNullOfOrNull { firstObj(node.opt(it), key) }
        else -> null
    }

    private suspend fun ensureVisitorData() {
        if (visitorData != null) return
        runCatching {
            withContext(Dispatchers.IO) {
                val c = URL("https://www.youtube.com/sw.js_data").openConnection() as HttpURLConnection
                c.setRequestProperty("User-Agent", UA)
                c.connectTimeout = 10_000; c.readTimeout = 10_000
                val text = c.inputStream.bufferedReader().use { it.readText() }
                VISITOR.find(text)?.value?.let { visitorData = it }
            }
        }.onFailure { Log.w(TAG, "visitor id unavailable: ${it.message}") }
    }

    private suspend fun post(
        endpoint: String,
        query: Map<String, String>,
        auth: Boolean = true,
        fast: Boolean = false,
        extras: JSONObject.() -> Unit,
    ): JSONObject {
        var backoff = 500L
        repeat(2) {
            try { return request(endpoint, query, auth, fast, extras) } catch (e: IOException) {
                if (e.message?.startsWith("HTTP 4") == true) throw e
                Log.d(TAG, "retrying: ${e.message}")
            }
            delay(backoff); backoff *= 2
        }
        return request(endpoint, query, auth, fast, extras)
    }

    private suspend fun request(
        endpoint: String,
        query: Map<String, String>,
        auth: Boolean,
        fast: Boolean,
        extras: JSONObject.() -> Unit,
    ): JSONObject = withContext(Dispatchers.IO) {
        val qs = query.entries.joinToString("") { "&${it.key}=${URLEncoder.encode(it.value, "UTF-8")}" }
        val conn = URL("$BASE/$endpoint?prettyPrint=false&hl=en$qs").openConnection() as HttpURLConnection
        // Cancelled caller = abort the socket now. Without this a cancelled call keeps blocking until its timeout.
        val onCancel = kotlin.coroutines.coroutineContext[kotlinx.coroutines.Job]
            ?.invokeOnCompletion { if (it != null) runCatching { conn.disconnect() } }
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = if (fast) 5_000 else 15_000   // fast = radio: a stuck call is dropped and retried, not waited on
            conn.readTimeout = if (fast) 8_000 else 20_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("User-Agent", UA)
            conn.setRequestProperty("X-Origin", ORIGIN)
            conn.setRequestProperty("Origin", ORIGIN)
            conn.setRequestProperty("Referer", "$ORIGIN/")
            conn.setRequestProperty("X-YouTube-Client-Name", "67")
            conn.setRequestProperty("X-YouTube-Client-Version", CLIENT_VERSION)
            visitorData?.let { conn.setRequestProperty("X-Goog-Visitor-Id", it) }
            if (auth) com.example.chord.data.auth.AuthStore.apply(conn, ORIGIN)

            val client = JSONObject()
                .put("clientName", "WEB_REMIX").put("clientVersion", CLIENT_VERSION)
                .put("hl", "en").put("gl", region)
            visitorData?.let { client.put("visitorData", it) }
            val body = JSONObject().put(
                "context",
                JSONObject().put("client", client)
                    .put("user", JSONObject().put("lockedSafetyMode", false))
                    .put("request", JSONObject().put("useSsl", true)),
            ).apply(extras)
            conn.outputStream.use { it.write(body.toString().toByteArray()) }

            val code = conn.responseCode
            if (code !in 200..299) throw IOException("HTTP $code")
            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            if (visitorData == null) {
                json.optJSONObject("responseContext")?.optString("visitorData")
                    ?.takeIf { it.isNotBlank() }?.let { visitorData = it }
            }
            json
        } finally {
            onCancel?.dispose()
            conn.disconnect()
        }
    }

    private fun pageOf(
        root: JSONObject,
        songsOnly: Boolean,
        includeVideoShelves: Boolean = false,
    ): HomePage {
        val carousels = ArrayList<JSONObject>().also { collect(root, it) }
        return HomePage(
            carousels.mapNotNull { shelf(it, songsOnly, includeVideoShelves) },
            findToken(root),
        )
    }

    private fun collect(node: Any?, out: MutableList<JSONObject>) {
        when (node) {
            is JSONObject -> {
                node.optJSONObject("musicCarouselShelfRenderer")?.let(out::add)
                node.keys().forEach { collect(node.opt(it), out) }
            }
            is JSONArray -> for (i in 0 until node.length()) collect(node.opt(i), out)
        }
    }

    private fun findToken(node: Any?): String? = when (node) {
        is JSONObject ->
            node.optJSONObject("nextContinuationData")?.optString("continuation")?.takeIf { it.isNotBlank() }
                ?: node.optJSONObject("continuationItemRenderer")?.optJSONObject("continuationEndpoint")
                    ?.optJSONObject("continuationCommand")?.optString("token")?.takeIf { it.isNotBlank() }
                ?: node.keys().asSequence().firstNotNullOfOrNull { findToken(node.opt(it)) }
        is JSONArray -> (0 until node.length()).firstNotNullOfOrNull { findToken(node.opt(it)) }
        else -> null
    }

    private fun shelf(
        c: JSONObject,
        songsOnly: Boolean,
        includeVideoShelves: Boolean = false,
    ): HomeShelf? {
        val header = c.optJSONObject("header")?.optJSONObject("musicCarouselShelfBasicHeaderRenderer")
        val title = runs(header?.optJSONObject("title"))
        if (!includeVideoShelves && VIDEO_WORD.containsMatchIn(title)) return null
        var isList = false
        val contents = c.optJSONArray("contents") ?: return null
        val items = (0 until contents.length()).mapNotNull { i ->
            val e = contents.optJSONObject(i) ?: return@mapNotNull null
            e.optJSONObject("musicTwoRowItemRenderer")?.let { return@mapNotNull twoRow(it, songsOnly) }
            e.optJSONObject("musicResponsiveListItemRenderer")?.let { isList = true; return@mapNotNull listRow(it, songsOnly) }
            null
        }
        if (items.isEmpty()) return null
        return HomeShelf(title.ifBlank { "For you" }, runs(header?.optJSONObject("strapline")), items, isList)
    }

    private fun twoRow(r: JSONObject, songsOnly: Boolean): HomeItem? {
        val title = runs(r.optJSONObject("title")).takeIf { it.isNotBlank() } ?: return null
        val subtitle = runs(r.optJSONObject("subtitle"))
        if (VIDEO_WORD.containsMatchIn(subtitle)) return null
        val thumb = thumb(r.optJSONObject("thumbnailRenderer")?.optJSONObject("musicThumbnailRenderer"))
        val nav = r.optJSONObject("navigationEndpoint")
        val watch = nav?.optJSONObject("watchEndpoint")
        val videoId = watch?.optString("videoId")?.takeIf { it.isNotBlank() }
        if (videoId != null) {
            if (songsOnly && isVideoType(watch)) return null
            ExplicitStore.mark(videoId, r)
            return HomeItem(title, subtitle, thumb, videoId, null, HomeItemType.Song)
        }
        val playlistId = (watch?.optString("playlistId") ?: nav?.optJSONObject("watchPlaylistEndpoint")?.optString("playlistId"))
            ?.takeIf { it.isNotBlank() }
        if (playlistId != null) return HomeItem(title, subtitle, thumb, null, "VL$playlistId", HomeItemType.Playlist)
        return browseItem(title, subtitle, thumb, nav)
    }

    private fun listRow(r: JSONObject, songsOnly: Boolean): HomeItem? {
        val cols = r.optJSONArray("flexColumns") ?: return null
        fun col(i: Int) = cols.optJSONObject(i)?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")?.optJSONObject("text")
        val title = runs(col(0)).takeIf { it.isNotBlank() } ?: return null
        val subtitle = runs(col(1))
        val thumb = thumb(r.optJSONObject("thumbnail")?.optJSONObject("musicThumbnailRenderer"))
        val videoId = r.optJSONObject("playlistItemData")?.optString("videoId")?.takeIf { it.isNotBlank() }
        if (videoId != null) {
            val watch = col(0)?.optJSONArray("runs")?.optJSONObject(0)?.optJSONObject("navigationEndpoint")?.optJSONObject("watchEndpoint")
            if (songsOnly && isVideoType(watch)) return null
            ExplicitStore.mark(videoId, r)
            return HomeItem(title, subtitle, thumb, videoId, null, HomeItemType.Song)
        }
        return browseItem(title, subtitle, thumb, r.optJSONObject("navigationEndpoint"))
    }

    private fun browseItem(title: String, subtitle: String, thumb: String?, nav: JSONObject?): HomeItem? {
        val be = nav?.optJSONObject("browseEndpoint") ?: return null
        val id = be.optString("browseId").takeIf { it.isNotBlank() } ?: return null
        val pageType = be.optJSONObject("browseEndpointContextSupportedConfigs")
            ?.optJSONObject("browseEndpointContextMusicConfig")?.optString("pageType").orEmpty()
        val type = when {
            "ALBUM" in pageType -> HomeItemType.Album
            "ARTIST" in pageType -> HomeItemType.Artist
            "PLAYLIST" in pageType -> HomeItemType.Playlist
            else -> HomeItemType.Other
        }
        return HomeItem(title, subtitle, thumb, null, id, type)
    }

    private fun isVideoType(watch: JSONObject?): Boolean {
        val t = watch?.optJSONObject("watchEndpointMusicSupportedConfigs")
            ?.optJSONObject("watchEndpointMusicConfig")?.optString("musicVideoType").orEmpty()
        return t.isNotEmpty() && t != "MUSIC_VIDEO_TYPE_ATV"
    }

    private fun runs(o: JSONObject?): String {
        val a = o?.optJSONArray("runs") ?: return ""
        return (0 until a.length()).joinToString("") { a.optJSONObject(it)?.optString("text").orEmpty() }
    }

    private fun thumb(renderer: JSONObject?): String? {
        val a = renderer?.optJSONObject("thumbnail")?.optJSONArray("thumbnails") ?: return null
        val url = a.optJSONObject(a.length() - 1)?.optString("url")?.takeIf { it.isNotBlank() } ?: return null
        return if ("googleusercontent" in url || "ggpht" in url) {
            url.replace(Regex("=w\\d+-h\\d+[^&]*$"), "=w544-h544-l90-rj").replace(Regex("=s\\d+[^&]*$"), "=s544")
        } else url
    }

    private companion object {
        const val TAG = "HomeRepository"
        const val BASE = "https://music.youtube.com/youtubei/v1"
        const val ORIGIN = "https://music.youtube.com"
        const val CLIENT_VERSION = "1.20250915.03.01"
        const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36"
        val VISITOR = Regex("Cg[A-Za-z0-9_%-]{40,}")
        val VIDEO_WORD = Regex("\\bvideos?\\b", RegexOption.IGNORE_CASE)
        val REPLAY_RE = Regex("replay|recap", RegexOption.IGNORE_CASE)
    }
}