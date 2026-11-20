package com.example.chord.data.mixes

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.example.chord.data.history.HistoryStore
import com.example.chord.data.home.HomeRepository
import com.example.chord.data.lastfm.LastFmApi
import com.example.chord.data.model.Song
import com.example.chord.data.settings.AppSettings
import com.example.chord.ui.search.SearchTab
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.Random

enum class MixGroup { Daily, Replay, LastFm, Mood }

/** One generated playlist. Opens in MixScreen via HomeItem.browseId = "mix:<id>". */
data class Mix(val id: String, val group: MixGroup, val title: String, val subtitle: String, val songs: List<Song>)

/**
 * Builds the app's own mixes. Two families:
 *  LOCAL   (no key, no network): Daily Mix 1-4 (taste clusters from your sessions), On repeat, Replay month / year, All-time.
 *  LAST.FM (needs free key in Settings): Discover Weekly + "If you like <artist>" mixes, matched to playable YouTube songs.
 *           Imported top songs / artists of a Last.fm profile. Artist tags also sharpen Daily Mixes and make mood mixes (from your own songs).
 * Local mixes change every day. Last.fm mixes change every week and are cached on disk.
 */
object MixEngine {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var ctx: Context? = null

    private val _mixes = MutableStateFlow<List<Mix>>(emptyList())
    val mixes: StateFlow<List<Mix>> = _mixes.asStateFlow()
    private val _busy = MutableStateFlow(false)
    /** True while Last.fm mixes are being built. */
    val lastFmBusy: StateFlow<Boolean> = _busy.asStateFlow()

    private var local: List<Mix> = emptyList()
    private var remote: List<Mix> = emptyList()
    private var cacheWeek = ""
    private var cacheKey = 0
    private var localAt = 0L
    private var localJob: Job? = null
    private var fmJob: Job? = null
    private var imported: List<Mix> = emptyList()
    private var tagJob: Job? = null
    /** artist (lowercase) -> Last.fm tag -> weight 0..1. Filled in the background, read by the local mixes. */
    private val tags = java.util.concurrent.ConcurrentHashMap<String, Map<String, Double>>()
    private val repo by lazy { HomeRepository() }
    private val gate = Semaphore(4)
    private val resolved = HashMap<String, Song?>()

    fun get(id: String): Mix? = _mixes.value.firstOrNull { it.id == id }

    @Synchronized
    fun init(c: Context) {
        if (ctx != null) return
        ctx = c.applicationContext
        loadCache()
        loadTags()
        publish()
        ensure()
    }

    /** Call when Home shows or refreshes. Cheap: local mixes at most every 2 minutes. */
    fun ensure(force: Boolean = false) {
        val c = ctx ?: return
        if (force || System.currentTimeMillis() - localAt > 120_000) {
            localJob?.cancel()
            localJob = scope.launch {
                local = runCatching { buildLocal(c) }.getOrDefault(local)
                localAt = System.currentTimeMillis()
                publish()
            }
        }
        if (AppSettings.lastFmOn) { buildLastFm(false); fillTags(c) } else if (remote.isNotEmpty()) { remote = emptyList(); publish() }
    }

    /** Settings: key saved or "Rebuild" tapped. */
    fun refreshLastFm() = if (AppSettings.lastFmOn) buildLastFm(true) else { remote = emptyList(); publish() }

    private fun publish() { _mixes.value = local + (if (AppSettings.lastFmOn) remote + imported else emptyList()) }

    // ================= LOCAL =================================================

    private class Row(val song: Song, val artist: String, val score: Double)

    private fun cleanArtist(raw: String) = HistoryStore.cleanArtist(raw).ifBlank { raw }

    /** Recent plays count more (half life about 2 weeks). */
    private fun scored(db: SQLiteDatabase, now: Long): List<Row> {
        val out = ArrayList<Row>()
        fun read(sql: String) = db.rawQuery(sql, null).use { c ->
            while (c.moveToNext()) {
                val a = cleanArtist(c.getString(2))
                out += Row(Song(c.getString(1), a, c.getString(0), c.getString(3)), a.lowercase(), c.getDouble(4))
            }
        }
        read(
            """SELECT h.videoId,h.title,h.artist,h.thumb,SUM(1.0/(1.0+($now-l.ts)/1209600000.0))
               FROM play_log l JOIN play_history h ON h.videoId=l.videoId
               GROUP BY l.videoId ORDER BY 5 DESC LIMIT 500"""
        )
        if (out.size < 12) {
            out.clear()
            read("SELECT videoId,title,artist,thumb,playCount FROM play_history ORDER BY playCount DESC, lastPlayed DESC LIMIT 300")
        }
        return out
    }

    private fun songsIn(db: SQLiteDatabase, from: Long, to: Long, limit: Int): List<Song> =
        db.rawQuery(
            """SELECT h.videoId,h.title,h.artist,h.thumb FROM play_log l JOIN play_history h ON h.videoId=l.videoId
               WHERE l.ts>=? AND l.ts<? GROUP BY l.videoId ORDER BY COUNT(*) DESC, MAX(l.ts) DESC LIMIT $limit""",
            arrayOf(from.toString(), to.toString())
        ).use { c -> buildList { while (c.moveToNext()) add(Song(c.getString(1), cleanArtist(c.getString(2)), c.getString(0), c.getString(3))) } }

    private fun buildLocal(c: Context): List<Mix> {
        val db = HistoryStore.get(c).readableDatabase
        val now = System.currentTimeMillis()
        val out = ArrayList<Mix>()
        val rows = scored(db, now)
        if (rows.size >= 12) out += dailyMixes(db, rows)
        if (rows.size >= 12 && AppSettings.lastFmOn) out += moodMixes(rows)
        out += replays(db, now)
        return out
    }

    /** Taste clusters: artists played in the same sitting belong together. */
    private fun dailyMixes(db: SQLiteDatabase, rows: List<Row>): List<Mix> {
        val display = HashMap<String, String>()
        rows.forEach { display.putIfAbsent(it.artist, it.song.details) }
        val aScore = rows.groupBy { it.artist }.mapValues { e -> e.value.sumOf { it.score } }
        val ranked = aScore.entries.sortedByDescending { it.value }.map { it.key }
        if (ranked.size < 2) return emptyList()

        // sessions: gap > 45 min starts a new one
        val sess = HashMap<String, Int>()
        val co = HashMap<String, Int>()
        fun pair(a: String, b: String) = if (a < b) "$a|$b" else "$b|$a"
        var cur = HashSet<String>()
        var lastTs = 0L
        fun flush() {
            val l = cur.toList()
            l.forEach { sess.merge(it, 1, Int::plus) }
            for (i in l.indices) for (j in i + 1 until l.size) co.merge(pair(l[i], l[j]), 1, Int::plus)
            cur = HashSet()
        }
        db.rawQuery(
            "SELECT l.ts,h.artist FROM play_log l JOIN play_history h ON h.videoId=l.videoId ORDER BY l.ts ASC LIMIT 6000", null
        ).use { c ->
            while (c.moveToNext()) {
                val ts = c.getLong(0)
                if (lastTs != 0L && ts - lastTs > 45 * 60_000L) flush()
                cur += cleanArtist(c.getString(1)).lowercase()
                lastTs = ts
            }
        }
        flush()
        // Same sitting counts most. Matching Last.fm tags (when cached) pull same-genre artists together.
        fun sim(a: String, b: String) =
            (co[pair(a, b)] ?: 0).toDouble() / maxOf(1, minOf(sess[a] ?: 1, sess[b] ?: 1)) + 0.5 * tagSim(a, b)

        val want = minOf(4, ranked.size)
        val seeds = ArrayList<String>()
        for (a in ranked) { if (seeds.size >= want) break; if (seeds.all { sim(a, it) < 0.45 }) seeds += a }
        for (a in ranked) { if (seeds.size >= minOf(3, ranked.size)) break; if (a !in seeds) seeds += a }
        val groups = seeds.associateWith { mutableListOf(it) }
        for (a in ranked) {
            if (a in seeds) continue
            val best = seeds.maxByOrNull { s -> sim(a, s) - 0.02 * groups.getValue(s).size } ?: continue
            groups.getValue(best) += a
        }

        val day = Calendar.getInstance().let { it.get(Calendar.YEAR) * 400 + it.get(Calendar.DAY_OF_YEAR) }
        val ordered = groups.values.sortedByDescending { g -> g.sumOf { aScore[it] ?: 0.0 } }
        val mixes = ArrayList<Mix>()
        ordered.forEachIndexed { i, members ->
            val rnd = Random((day * 31 + i).toLong())
            val set = members.toSet()
            val pool = rows.filter { it.artist in set }
                .sortedByDescending { it.score * (0.75 + 0.5 * rnd.nextDouble()) }
            val count = HashMap<String, Int>()
            val picked = ArrayList<Row>()
            for (r in pool) { if (picked.size >= 30) break; if ((count[r.artist] ?: 0) < 5) { picked += r; count.merge(r.artist, 1, Int::plus) } }
            if (picked.size < 8) return@forEachIndexed
            val spread = spread(picked)
            val names = members.sortedByDescending { aScore[it] ?: 0.0 }.take(3).mapNotNull { display[it] }
            val genre = topTag(members)?.let { it.replaceFirstChar { c -> c.uppercase() } + " · " }.orEmpty()
            mixes += Mix("daily${mixes.size + 1}", MixGroup.Daily, "Daily Mix ${mixes.size + 1}", genre + names.joinToString(", ") + " and more", spread.map { it.song })
        }
        return mixes
    }

    // ---- Last.fm tags (genre / mood) ----

    /** Tags that say nothing about sound. */
    private val NOISE = setOf("seen live", "favorites", "favourites", "favorite", "awesome", "love", "albums i own", "british", "american")

    private fun tagSim(a: String, b: String): Double {
        val x = tags[a] ?: return 0.0
        val y = tags[b] ?: return 0.0
        var dot = 0.0; var nx = 0.0; var ny = 0.0
        x.forEach { (t, w) -> if (t !in NOISE) { nx += w * w; dot += w * (y[t] ?: 0.0) } }
        y.forEach { (t, w) -> if (t !in NOISE) ny += w * w }
        return if (nx == 0.0 || ny == 0.0) 0.0 else dot / Math.sqrt(nx * ny)
    }

    private fun topTag(artists: List<String>): String? {
        val sum = HashMap<String, Double>()
        artists.forEach { a -> tags[a]?.forEach { (t, w) -> if (t !in NOISE) sum.merge(t, w, Double::plus) } }
        return sum.entries.maxByOrNull { it.value }?.takeIf { it.value >= 0.5 }?.key
    }

    private class Mood(val id: String, val title: String, val sub: String, val words: Set<String>)
    private val MOODS = listOf(
        Mood("chill", "Chill", "Easy, mellow songs from your library", setOf("chill", "chillout", "relax", "relaxing", "mellow", "downtempo", "lounge", "ambient", "acoustic")),
        Mood("focus", "Focus", "Calm and instrumental, for working", setOf("instrumental", "ambient", "post-rock", "classical", "lo-fi", "lofi", "study", "piano", "soundtrack")),
        Mood("energy", "Energy", "Fast and loud, from your library", setOf("energetic", "workout", "dance", "edm", "metal", "punk", "hard rock", "drum and bass", "hardcore")),
        Mood("party", "Party", "Songs to put on with friends", setOf("party", "club", "dance", "house", "electronic", "disco", "pop", "reggaeton")),
        Mood("sad", "Sad hours", "Slow and heavy songs you play", setOf("sad", "melancholy", "melancholic", "emotional", "heartbreak", "depressive", "dark")),
        Mood("happy", "Feel good", "Bright songs from your library", setOf("happy", "feel good", "feel-good", "upbeat", "summer", "fun", "sunshine")),
        Mood("love", "Romance", "Love songs you play", setOf("romantic", "love songs", "love song", "sensual", "slow jam")),
    )

    /** Your own songs, picked by the Last.fm tags of their artist. A new roll every day. Needs tags cached first. */
    private fun moodMixes(rows: List<Row>): List<Mix> {
        if (tags.isEmpty()) return emptyList()
        val day = Calendar.getInstance().let { it.get(Calendar.YEAR) * 400 + it.get(Calendar.DAY_OF_YEAR) }
        val out = ArrayList<Mix>()
        for (m in MOODS) {
            val fit = HashMap<String, Double>()
            tags.forEach { (a, t) -> val w = t.entries.sumOf { (k, v) -> if (k in m.words) v else 0.0 }; if (w >= 0.25) fit[a] = w }
            if (fit.size < 2) continue
            val rnd = Random((day * 17 + m.id.hashCode()).toLong())
            val pool = rows.filter { it.artist in fit }.sortedByDescending { it.score * (0.5 + fit.getValue(it.artist).coerceAtMost(1.0)) * (0.75 + 0.5 * rnd.nextDouble()) }
            val count = HashMap<String, Int>()
            val picked = ArrayList<Row>()
            for (r in pool) { if (picked.size >= 30) break; if ((count[r.artist] ?: 0) < 4) { picked += r; count.merge(r.artist, 1, Int::plus) } }
            if (picked.size < 8) continue
            out += Mix("mood_${m.id}", MixGroup.Mood, m.title, m.sub, spread(picked).map { it.song })
        }
        return out
    }

    /** Background: fetch tags for your top artists once, keep them on disk, then rebuild the local mixes. */
    private fun fillTags(c: Context) {
        if (tagJob?.isActive == true) return
        val key = AppSettings.lastFmKey
        tagJob = scope.launch {
            val rows = runCatching { scored(HistoryStore.get(c).readableDatabase, System.currentTimeMillis()) }.getOrNull() ?: return@launch
            val names = LinkedHashMap<String, String>()
            rows.groupBy { it.artist }.entries.sortedByDescending { e -> e.value.sumOf { it.score } }.take(40)
                .forEach { e -> names[e.key] = e.value.first().song.details }
            val todo = names.filterKeys { !tags.containsKey(it) }
            if (todo.isEmpty()) return@launch
            val before = tags.size
            coroutineScope {
                todo.map { (k, display) ->
                    async {
                        gate.withPermit {
                            // A failed call is retried next time; an artist Last.fm doesn't know is stored as empty.
                            runCatching { LastFmApi.artistTags(key, display) }.getOrNull()?.let { tags[k] = it }
                        }
                    }
                }.awaitAll()
            }
            if (tags.size > before) { saveTags(); ensure(force = true) }
        }
    }

    private fun tagFile() = ctx?.let { File(it.filesDir, "lastfm_tags.json") }

    private fun saveTags() {
        val f = tagFile() ?: return
        runCatching {
            val o = JSONObject()
            tags.forEach { (a, t) -> o.put(a, JSONObject().also { j -> t.forEach { (k, v) -> j.put(k, v) } }) }
            f.writeText(o.toString())
        }
    }

    private fun loadTags() {
        val f = tagFile()?.takeIf { it.exists() } ?: return
        runCatching {
            val o = JSONObject(f.readText())
            o.keys().forEach { a ->
                val j = o.getJSONObject(a)
                tags[a] = j.keys().asSequence().associateWith { j.getDouble(it) }
            }
        }
    }

    /** No same artist twice in a row when we can avoid it. */
    private fun spread(rows: List<Row>): List<Row> {
        val left = ArrayList(rows)
        val out = ArrayList<Row>(rows.size)
        while (left.isNotEmpty()) {
            val last = out.lastOrNull()?.artist
            val i = left.indexOfFirst { it.artist != last }.let { if (it < 0) 0 else it }
            out += left.removeAt(i)
        }
        return out
    }

    private fun replays(db: SQLiteDatabase, now: Long): List<Mix> {
        val out = ArrayList<Mix>()
        val day = 86_400_000L
        songsIn(db, now - 14 * day, now + 1, 30).takeIf { it.size >= 5 }?.let {
            out += Mix("onrepeat", MixGroup.Replay, "On repeat", "What you played most these two weeks", it)
        }
        fun monthStart(offset: Int) = Calendar.getInstance().apply {
            add(Calendar.MONTH, offset); set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        val fmt = SimpleDateFormat("MMMM", Locale.getDefault())
        val thisM = monthStart(0)
        val lastM = monthStart(-1)
        songsIn(db, thisM.timeInMillis, now + 1, 50).takeIf { it.size >= 8 }?.let {
            out += Mix("replay_month", MixGroup.Replay, "Replay ${fmt.format(thisM.time)}", "Your top songs this month", it)
        }
        songsIn(db, lastM.timeInMillis, thisM.timeInMillis, 50).takeIf { it.size >= 10 }?.let {
            out += Mix("replay_last", MixGroup.Replay, "Replay ${fmt.format(lastM.time)}", "Time capsule: last month", it)
        }
        val year = Calendar.getInstance().apply {
            set(Calendar.DAY_OF_YEAR, 1); set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        songsIn(db, year.timeInMillis, now + 1, 60).takeIf { it.size >= 20 }?.let {
            out += Mix("replay_year", MixGroup.Replay, "Replay ${year.get(Calendar.YEAR)}", "Your year in songs", it)
        }
        db.rawQuery("SELECT videoId,title,artist,thumb FROM play_history WHERE playCount>0 ORDER BY playCount DESC, lastPlayed DESC LIMIT 50", null).use { c ->
            buildList { while (c.moveToNext()) add(Song(c.getString(1), cleanArtist(c.getString(2)), c.getString(0), c.getString(3))) }
        }.takeIf { it.size >= 15 }?.let { out += Mix("alltime", MixGroup.Replay, "All-time favourites", "Most played, ever", it) }
        return out
    }

    // ================= LAST.FM ===============================================

    private fun weekKey(): String = Calendar.getInstance().let { "${it.get(Calendar.YEAR)}-${it.get(Calendar.WEEK_OF_YEAR)}" }
    internal fun norm(s: String) = s.lowercase()
        .replace(Regex("\\(.*?\\)|\\[.*?]"), " ")
        .replace(Regex("[^\\p{L}\\p{N} ]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun buildLastFm(force: Boolean) {
        val c = ctx ?: return
        val key = AppSettings.lastFmKey
        if (!force && remote.isNotEmpty() && cacheWeek == weekKey() && cacheKey == key.hashCode()) return
        if (!force && fmJob?.isActive == true) return
        fmJob?.cancel()
        fmJob = scope.launch {
            _busy.value = true
            try {
                val built = runCatching { lastFmMixes(c, key, force) }.getOrNull()
                if (built != null) {
                    remote = built; cacheWeek = weekKey(); cacheKey = key.hashCode()
                    saveCache(); publish()
                }
                // Profile top songs and artists come by themselves. No manual import.
                val user = AppSettings.lastFmUser.ifBlank { AppSettings.lastFmImportUser }
                if (user.isNotBlank() && key.isNotBlank()) {
                    importProfile(user, "overall", "All time")
                }
            } finally { _busy.value = false }
        }
    }

    /** Resolve a batch of Last.fm tracks to playable YouTube Music songs for Home shelves. */
    suspend fun resolveLastFmTracks(tracks: List<LastFmApi.Track>): List<Song> = coroutineScope {
        tracks.map { track -> async { resolve(track) } }.awaitAll().filterNotNull().distinctBy { it.videoId }
    }

    /** Last.fm track -> playable YouTube song. Cache only successful matches so temporary
     * search/API failures do not hide a track for the entire app session. */
    private suspend fun resolve(t: LastFmApi.Track): Song? {
        val titleKey = norm(t.title)
        val artistKey = norm(t.artist)
        val k = "$titleKey|$artistKey"
        synchronized(resolved) { resolved[k]?.let { return it } }

        fun overlap(wanted: String, actual: String): Double {
            if (wanted.isBlank() || actual.isBlank()) return 0.0
            if (wanted == actual || actual.contains(wanted) || wanted.contains(actual)) return 1.0
            val a = wanted.split(' ').filter { it.length > 1 }.toSet()
            val b = actual.split(' ').filter { it.length > 1 }.toSet()
            return if (a.isEmpty()) 0.0 else a.intersect(b).size.toDouble() / a.size
        }

        val song = gate.withPermit {
            val queries = listOf("${t.title} ${t.artist}", t.title).distinct()
            var best: com.example.chord.data.home.HomeItem? = null
            var bestScore = 0.0

            for (query in queries) {
                val results = repo.search(query, SearchTab.Songs.params).getOrNull().orEmpty()
                for (item in results) {
                    if (item.videoId.isNullOrBlank()) continue
                    val titleScore = overlap(titleKey, norm(item.title))
                    val artistScore = overlap(artistKey, norm(item.subtitle))
                    // A title match alone is not enough: require some evidence for the artist too.
                    if (titleScore < 0.60 || artistScore < 0.50) continue
                    val score = titleScore * 2.0 + artistScore
                    if (score > bestScore) {
                        best = item
                        bestScore = score
                    }
                }
                if (bestScore >= 2.9) break
            }
            best?.let {
                Song(it.title, HistoryStore.cleanArtist(it.subtitle), it.videoId!!, it.thumbnailUrl)
            }
        }

        if (song != null) synchronized(resolved) { resolved[k] = song }
        return song
    }

    /** Settings: pull top songs and top artists of a Last.fm profile into two mixes. [period]: 7day, 1month, 12month, overall. */
    suspend fun importProfile(user: String, period: String, label: String): Result<Int> = runCatching {
        val key = AppSettings.lastFmKey
        require(key.isNotBlank()) { "Add your Last.fm API key first" }
        val (tracks, artists) = coroutineScope {
            val t = async { LastFmApi.userTopTracks(key, user, period, 50) }
            val a = async { LastFmApi.userTopArtists(key, user, period, 15) }
            t.await() to a.await()
        }
        if (tracks.isEmpty() && artists.isEmpty()) throw java.io.IOException("No listening found for \"$user\" in that time")
        val top = coroutineScope { tracks.map { async { resolve(it) } }.awaitAll() }.filterNotNull().distinctBy { it.videoId }
        val byArtist = coroutineScope {
            artists.map { a -> async { gate.withPermit { runCatching { LastFmApi.topTracks(key, a, 2) }.getOrDefault(emptyList()) } } }.awaitAll()
        }.flatten()
        val fromArtists = coroutineScope { byArtist.map { async { resolve(it) } }.awaitAll() }.filterNotNull().distinctBy { it.videoId }
        val out = ArrayList<Mix>()
        if (top.size >= 5) out += Mix("fm_imp_tracks", MixGroup.LastFm, "$user · top songs", label, top)
        if (fromArtists.size >= 5) out += Mix("fm_imp_artists", MixGroup.LastFm, "$user · top artists", label, fromArtists)
        if (out.isEmpty()) throw java.io.IOException("Couldn't match those songs on YouTube Music")
        imported = out
        saveMixes("lastfm_import.json", out)
        publish()
        out.sumOf { it.songs.size }
    }

    private suspend fun lastFmMixes(c: Context, key: String, force: Boolean): List<Mix> = coroutineScope {
        val hs = HistoryStore.get(c)
        val db = hs.readableDatabase
        val now = System.currentTimeMillis()
        val rows = scored(db, now)
        // Profile charts and album collections are independent of local Chord history.
        // Keep building those when the user has a Last.fm profile but is new to Chord.
        val knownIds = hs.knownIds()
        val knownNames = HashSet<String>()
        db.rawQuery("SELECT title,artist FROM play_history", null).use { cur ->
            while (cur.moveToNext()) knownNames += norm(cur.getString(0)) + "|" + norm(cleanArtist(cur.getString(1)))
        }
        // Weekly seed. "Rebuild" adds the clock, so you get a new roll.
        val rnd = Random(weekKey().hashCode().toLong() + if (force) now / 1000 else 0)
        val out = ArrayList<Mix>()

        // Last.fm profile tracks are the fallback seed for new Chord installs.
        val user = AppSettings.lastFmUser.ifBlank { AppSettings.lastFmImportUser }
        val seedTracks = if (rows.isNotEmpty()) {
            rows.take(12).shuffled(rnd).take(5).map { LastFmApi.Track(it.song.title, it.song.details, 1.0) }
        } else if (user.isNotBlank()) {
            runCatching { LastFmApi.userTopTracks(key, user, "1month", 5) }.getOrDefault(emptyList())
        } else emptyList()

        // 1. Discover Weekly: tracks like your favourites that you never played.
        val agg = LinkedHashMap<String, Pair<LastFmApi.Track, Double>>()
        seedTracks.map { seed ->
            async { gate.withPermit { runCatching { LastFmApi.similarTracks(key, seed.artist, seed.title, 18) }.getOrDefault(emptyList()) } }
        }.awaitAll().forEach { list ->
            list.forEach { t ->
                val k = norm(t.title) + "|" + norm(t.artist)
                if (k !in knownNames) agg.merge(k, t to t.match) { a, b -> a.first to (a.second + b.second) }
            }
        }
        val perArtist = HashMap<String, Int>()
        val cands = agg.values.sortedByDescending { it.second }.map { it.first }
            .filter { t -> (perArtist.merge(norm(t.artist), 1, Int::plus) ?: 1) <= 3 }
            .take(30)
        val discover = cands.map { t -> async { resolve(t) } }.awaitAll().filterNotNull()
            .filter { it.videoId !in knownIds }.distinctBy { it.videoId }.take(20)
        if (discover.size >= 8) {
            out += Mix("fm_discover", MixGroup.LastFm, "Discover Weekly", "New songs picked from what you love", discover)
        }

        // Last.fm profile charts work even when the user has little or no Chord history.
        if (user.isNotBlank()) {
            val weekTracks = runCatching { LastFmApi.userTopTracks(key, user, "7day", 24) }.getOrDefault(emptyList())
            val monthTracks = runCatching { LastFmApi.userTopTracks(key, user, "1month", 24) }.getOrDefault(emptyList())
            val weekSongs = weekTracks.map { async { resolve(it) } }.awaitAll().filterNotNull().distinctBy { it.videoId }
            val monthSongs = monthTracks.map { async { resolve(it) } }.awaitAll().filterNotNull().distinctBy { it.videoId }
            if (weekSongs.size >= 5) out += Mix("fm_week_chart", MixGroup.LastFm, "Your week on Last.fm", "Your most-played tracks this week", weekSongs)
            if (monthSongs.size >= 5) out += Mix("fm_month_chart", MixGroup.LastFm, "Your monthly chart", "The songs you've been returning to", monthSongs)

            val profileArtists = runCatching { LastFmApi.userTopArtists(key, user, "1month", 5) }.getOrDefault(emptyList())
            val artistTracks = profileArtists.take(4).map { artist ->
                async { gate.withPermit { runCatching { LastFmApi.topTracks(key, artist, 3) }.getOrDefault(emptyList()) } }
            }.awaitAll().flatten()
            val artistSongs = artistTracks.map { async { resolve(it) } }.awaitAll().filterNotNull().distinctBy { it.videoId }
            if (artistSongs.size >= 8) out += Mix("fm_artist_rotation", MixGroup.LastFm, "Your artist rotation", "Top tracks from your most-played artists", artistSongs)

            val albums = runCatching { LastFmApi.userTopAlbums(key, user, "1month", 5) }.getOrDefault(emptyList())
            val albumMixes = albums.take(3).mapIndexedNotNull { index, album ->
                val albumTracks = runCatching { LastFmApi.albumTracks(key, album, 12) }.getOrDefault(emptyList())
                val albumSongs = albumTracks.map { async { resolve(it) } }.awaitAll().filterNotNull().distinctBy { it.videoId }
                if (albumSongs.size < 4) null else Mix(
                    "fm_album_$index", MixGroup.LastFm, album.title,
                    "Album replay · ${album.artist}", albumSongs,
                )
            }
            out += albumMixes
        }

        // 2. "If you like X": similar artists -> their top songs.
        val localTop = rows.groupBy { it.artist }.entries.sortedByDescending { e -> e.value.sumOf { it.score } }
            .take(6).map { it.value.first().song.details }
        val top = (localTop.ifEmpty {
            if (user.isNotBlank()) runCatching { LastFmApi.userTopArtists(key, user, "1month", 6) }.getOrDefault(emptyList())
            else emptyList()
        }).shuffled(rnd).take(3)
        top.forEachIndexed { i, name ->
            val similar = runCatching { LastFmApi.similarArtists(key, name, 6) }.getOrDefault(emptyList())
            val tracks = similar.map { a ->
                async { gate.withPermit { runCatching { LastFmApi.topTracks(key, a, 2) }.getOrDefault(emptyList()) } }
            }.awaitAll().flatten()
            val songs = tracks.map { t -> async { resolve(t) } }.awaitAll().filterNotNull()
                .filter { it.videoId !in knownIds }.distinctBy { it.videoId }.take(20)
            if (songs.size >= 8) {
                out += Mix("fm_like$i", MixGroup.LastFm, "If you like $name", similar.take(3).joinToString(", ") + " and more", songs)
            }
        }

        // Track radio: Last.fm's neighbours of individual favourites, not just artist radios.
        seedTracks.take(2).forEachIndexed { i, seed ->
            val neighbours = runCatching {
                LastFmApi.similarTracks(key, seed.artist, seed.title, 20)
            }.getOrDefault(emptyList())
            val songs = neighbours.map { t -> async { resolve(t) } }.awaitAll().filterNotNull()
                .filter { it.videoId !in knownIds }.distinctBy { it.videoId }.take(18)
            if (songs.size >= 7) out += Mix(
                "fm_track_radio_$i", MixGroup.LastFm, "More like ${seed.title}",
                "Track radio · ${seed.artist}", songs,
            )
        }
        out
    }

    // ================= CACHE =================================================

    private fun cacheFile() = ctx?.let { File(it.filesDir, "lastfm_mixes.json") }

    private fun saveCache() {
        val f = cacheFile() ?: return
        runCatching { f.writeText(JSONObject().put("week", cacheWeek).put("key", cacheKey).put("mixes", mixesToJson(remote)).toString()) }
    }

    private fun loadCache() {
        cacheFile()?.takeIf { it.exists() }?.let { f -> runCatching {
            val o = JSONObject(f.readText())
            cacheWeek = o.optString("week"); cacheKey = o.optInt("key")
            remote = mixesFromJson(o.getJSONArray("mixes"))
        } }
        val g = ctx?.let { File(it.filesDir, "lastfm_import.json") }?.takeIf { it.exists() } ?: return
        runCatching { imported = mixesFromJson(JSONObject(g.readText()).getJSONArray("mixes")) }
    }

    private fun saveMixes(name: String, list: List<Mix>) {
        val f = ctx?.let { File(it.filesDir, name) } ?: return
        runCatching { f.writeText(JSONObject().put("mixes", mixesToJson(list)).toString()) }
    }

    private fun mixesToJson(list: List<Mix>) = JSONArray().also { arr ->
        list.forEach { m ->
            arr.put(JSONObject().put("id", m.id).put("t", m.title).put("s", m.subtitle).put("songs", JSONArray().also { a ->
                m.songs.forEach { a.put(JSONObject().put("t", it.title).put("d", it.details).put("v", it.videoId).put("i", it.thumbnailUrl ?: "")) }
            }))
        }
    }

    private fun mixesFromJson(arr: JSONArray) = (0 until arr.length()).map { i ->
        val m = arr.getJSONObject(i)
        val s = m.getJSONArray("songs")
        Mix(m.getString("id"), MixGroup.LastFm, m.getString("t"), m.optString("s"), (0 until s.length()).map { j ->
            val x = s.getJSONObject(j)
            Song(x.getString("t"), x.optString("d"), x.getString("v"), x.optString("i").takeIf { it.isNotBlank() })
        })
    }
}
