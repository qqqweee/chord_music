package com.example.chord.data.repository

import android.content.Context
import com.example.chord.data.explicit.ExplicitStore
import com.example.chord.data.history.HistoryStore
import com.example.chord.data.home.HomeRepository
import com.example.chord.data.lastfm.LastFmApi
import com.example.chord.data.mixes.MixEngine
import com.example.chord.data.model.Song
import com.example.chord.data.settings.AppSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject

/**
 * Up-next that learns you. Three signals:
 *  1. YouTube radio asked WITH your login, so YT Music's own taste model for you is used.
 *  2. First page also pulls radios of 2 songs you play a lot (random each time) = your taste, not only this song.
 *  3. Songs by artists you play most climb the list. Taste picks are woven in, every 4th slot.
 *  4. With a Last.fm key: songs Last.fm lists as similar to the playing song (and their artists) climb too.
 * Anything fails -> caller falls back to the plain radio.
 */
object SmartRadio {
    private val repo by lazy { HomeRepository() }

    suspend fun page(ctx: Context, seed: String, token: String?, blend: Boolean = true): RadioPage = coroutineScope {
        val main = async { fetch(seed, token) }
        // Taste blend only on first page.
        val taste = if (token == null && blend) async { tasteSongs(ctx, seed) } else null
        val (songs, next) = main.await()
        if (songs.isEmpty()) { taste?.cancel(); return@coroutineScope RadioPage(emptyList(), next) }
        // Extras may add taste, never delay the queue: wait at most 1.2 s, then go with what we have.
        val fmJob = if (token == null && blend && AppSettings.lastFmOn) async { lastFmBoost(songs, seed) } else null
        val extra = withTimeoutOrNull(1_200) { taste?.await() }.orEmpty()
        taste?.cancel()

        val fav = if (token == null && blend) favouriteArtists(ctx) else emptySet()
        val fm = fmJob?.let { withTimeoutOrNull(300) { it.await() } }
        fmJob?.cancel()
        val ranked = if (fav.isEmpty() && fm == null) songs else songs.withIndex().sortedBy { (i, s) ->
            i - (if (artistOf(s) in fav) 6 else 0) - (fm?.score(s) ?: 0.0)
        }.map { it.value }

        val seen = HashSet<String>(ranked.map { it.videoId })
        val fresh = extra.filter { seen.add(it.videoId) }
        val out = ArrayList<Song>(ranked.size + fresh.size)
        val t = fresh.iterator()
        ranked.forEachIndexed { i, s ->
            out += s
            if ((i + 1) % 4 == 0 && t.hasNext()) out += t.next()
        }
        while (t.hasNext()) out += t.next()
        RadioPage(out.filterNot { it.videoId == seed }.distinctBy { it.videoId }, next)
    }

    /** Last.fm "similar to the seed" data: songs and artists it names move up the radio list. */
    private class FmBoost(val tracks: Map<String, Double>, val artists: Map<String, Double>) {
        fun score(s: Song): Double {
            val n = MixEngine.norm(HistoryStore.cleanArtist(s.details))
            return maxOf((tracks[MixEngine.norm(s.title) + "|" + n] ?: 0.0) * 20.0, (artists[n] ?: 0.0) * 8.0)
        }
    }

    /** One quick call. If Last.fm is slow or fails, the radio is ranked as before. */
    private suspend fun lastFmBoost(songs: List<Song>, seed: String): FmBoost? = runCatching {
        val s = songs.firstOrNull { it.videoId == seed } ?: return null
        val artist = HistoryStore.cleanArtist(s.details).ifBlank { return null }
        val sim = withTimeoutOrNull(1_200) { LastFmApi.similarTracks(AppSettings.lastFmKey, artist, s.title, 100) } ?: return null
        val byArtist = HashMap<String, Double>()
        sim.forEach { t -> byArtist.merge(MixEngine.norm(t.artist), t.match) { a, b -> maxOf(a, b) } }
        FmBoost(sim.associate { MixEngine.norm(it.title) + "|" + MixEngine.norm(it.artist) to it.match }, byArtist)
    }.getOrNull()

    /**
     * Hedged request: ask once, and if no answer in 2.5 s ask again in parallel. First good answer wins, the other is aborted.
     * One slow or stuck connection no longer stalls the queue.
     */
    private suspend fun fetch(seed: String, token: String?): Pair<List<Song>, String?> = coroutineScope {
        val done = CompletableDeferred<Pair<List<Song>, String?>>()
        var failed = 0
        val tries = listOf(0L, 2_500L).map { wait ->
            launch {
                delay(wait)
                runCatching {
                    val root = repo.radio(seed, token).getOrThrow()
                    parseSongs(root) to continuation(root)
                }.onSuccess { done.complete(it) }
                    .onFailure { if (it is CancellationException) throw it; if (++failed == 2) done.completeExceptionally(it) }
            }
        }
        try { done.await() } finally { tries.forEach { it.cancel() } }
    }

    private suspend fun tasteSongs(ctx: Context, seed: String): List<Song> = runCatching {
        val hs = HistoryStore.get(ctx)
        val seeds = hs.seedSongs(20).mapNotNull { it.videoId }.filter { it != seed }.shuffled().take(2)
        coroutineScope {
            seeds.map { s -> async { runCatching { fetch(s, null).first.take(10) }.getOrDefault(emptyList()) } }
        }.flatMap { it.await() }
    }.getOrDefault(emptyList())

    private suspend fun favouriteArtists(ctx: Context): Set<String> = runCatching {
        HistoryStore.get(ctx).artists().first().take(15).map { it.title.lowercase() }.toSet()
    }.getOrDefault(emptySet())

    private fun artistOf(s: Song) = HistoryStore.cleanArtist(s.details).lowercase()

    // ---- parse -----------------------------------------------------------------

    private fun parseSongs(root: JSONObject): List<Song> {
        val out = LinkedHashMap<String, Song>()
        fun runs(o: JSONObject?): String {
            val a = o?.optJSONArray("runs") ?: return ""
            return (0 until a.length()).joinToString("") { a.optJSONObject(it)?.optString("text").orEmpty() }.trim()
        }
        fun walk(n: Any?) {
            when (n) {
                is JSONObject -> {
                    n.optJSONObject("playlistPanelVideoRenderer")?.let { r ->
                        val id = r.optString("videoId")
                        val title = runs(r.optJSONObject("title"))
                        if (id.isNotBlank() && title.isNotBlank() && id !in out) {
                            val by = runs(r.optJSONObject("longBylineText")).ifBlank { runs(r.optJSONObject("shortBylineText")) }
                            val th = r.optJSONObject("thumbnail")?.optJSONArray("thumbnails")
                            val url = th?.optJSONObject(th.length() - 1)?.optString("url")?.takeIf { it.isNotBlank() }
                            ExplicitStore.mark(id, r)
                            out[id] = Song(title, by, id, url)
                        }
                    }
                    n.keys().forEach { walk(n.opt(it)) }
                }
                is JSONArray -> for (i in 0 until n.length()) walk(n.opt(i))
            }
        }
        walk(root)
        return out.values.toList()
    }

    private fun continuation(n: Any?): String? = when (n) {
        is JSONObject ->
            (n.optJSONObject("nextRadioContinuationData") ?: n.optJSONObject("nextContinuationData"))
                ?.optString("continuation")?.takeIf { it.isNotBlank() }
                ?: n.keys().asSequence().firstNotNullOfOrNull { continuation(n.opt(it)) }
        is JSONArray -> (0 until n.length()).firstNotNullOfOrNull { continuation(n.opt(it)) }
        else -> null
    }
}
