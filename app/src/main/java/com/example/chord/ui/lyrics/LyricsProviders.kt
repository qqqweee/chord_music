package com.example.chord.ui.lyrics

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import kotlin.math.abs

data class LyricsQuery(val title: String, val artist: String, val durationMs: Long, val videoId: String? = null)

interface LyricsProvider {
    val name: String
    suspend fun fetch(q: LyricsQuery): String?
}

private val http = OkHttpClient()
private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
private fun b64(s: String) = String(Base64.decode(s, Base64.DEFAULT), Charsets.UTF_8)
private fun clean(s: String) = s.replace(Regex("""\(.*?\)|\[.*?]"""), "").trim()
private fun firstArtist(s: String) = s.split("&", ",", " feat", " ft").first().trim()
private fun ok(s: String?) = s != null && s.isNotBlank() && s != "null"

/**
 * Pick the search hit whose length matches the song.
 * If we do not know the song length yet (0), just take the first hit instead of failing.
 */
private fun <T> List<T>.pickByDuration(q: LyricsQuery, durationMs: (T) -> Long): T? {
    if (q.durationMs <= 0L) return firstOrNull()
    val best = minByOrNull { abs(durationMs(it) - q.durationMs) } ?: return null
    return best.takeIf { abs(durationMs(it) - q.durationMs) <= 5000 }
}

private fun get(url: String, referer: String? = null): String? = runCatching {
    val req = Request.Builder().url(url).header("User-Agent", "BitChord/1.0")
        .apply { referer?.let { header("Referer", it) } }.build()
    http.newCall(req).execute().use { if (it.isSuccessful) it.body?.string() else null }
}.getOrNull()

private fun msToTag(ms: Long) = "%02d:%02d.%02d".format(ms / 60_000, (ms % 60_000) / 1000, (ms % 1000) / 10)

object LrcLibProvider : LyricsProvider {
    override val name = "LRCLIB"
    override suspend fun fetch(q: LyricsQuery): String? = withContext(Dispatchers.IO) {
        val t = clean(q.title); val a = firstArtist(q.artist)
        val dur = if (q.durationMs > 0) "&duration=${q.durationMs / 1000}" else ""
        val exact = get("https://lrclib.net/api/get?track_name=${enc(t)}&artist_name=${enc(a)}$dur")
            ?.let { JSONObject(it).optString("syncedLyrics") }?.takeIf { ok(it) }
        if (exact != null) return@withContext exact
        val arr = get("https://lrclib.net/api/search?q=${enc("$t $a")}")?.let { JSONArray(it) } ?: return@withContext null
        val synced = (0 until arr.length()).map { arr.getJSONObject(it) }
            .filter { ok(it.optString("syncedLyrics")) }
        if (q.durationMs <= 0L) synced.firstOrNull()?.getString("syncedLyrics")
        else synced.minByOrNull { abs(it.optDouble("duration") * 1000 - q.durationMs) }?.getString("syncedLyrics")
    }
}

class NetEaseProvider : LyricsProvider {
    override val name = "NetEase"
    override suspend fun fetch(q: LyricsQuery): String? = withContext(Dispatchers.IO) {
        val ref = "https://music.163.com"
        val query = "${clean(q.title)} ${firstArtist(q.artist)}"
        val songs = get("$ref/api/search/get?s=${enc(query)}&type=1&limit=6", ref)
            ?.let { JSONObject(it).optJSONObject("result")?.optJSONArray("songs") } ?: return@withContext null
        val best = (0 until songs.length()).map { songs.getJSONObject(it) }
            .pickByDuration(q) { it.optLong("duration") } ?: return@withContext null
        val json = get("$ref/api/song/lyric?id=${best.getLong("id")}&lv=1&yv=1", ref)?.let { JSONObject(it) }
            ?: return@withContext null
        val yrc = json.optJSONObject("yrc")?.optString("lyric").orEmpty()
        val lrc = json.optJSONObject("lrc")?.optString("lyric").orEmpty()
        (if (yrc.isNotBlank()) yrcToLrc(yrc) else null) ?: lrc.takeIf { it.isNotBlank() }
    }

    private fun yrcToLrc(yrc: String): String? {
        val line = Regex("""^\[(\d+),(\d+)](.*)$""")
        val word = Regex("""\((\d+),(\d+),\d+\)([^(]*)""")
        val out = StringBuilder()
        for (l in yrc.lineSequence()) {
            val m = line.find(l.trim()) ?: continue
            val words = word.findAll(m.groupValues[3]).toList()
            if (words.isEmpty()) continue
            out.append('[').append(msToTag(m.groupValues[1].toLong())).append(']')
            words.forEach { w -> out.append('<').append(msToTag(w.groupValues[1].toLong())).append('>').append(w.groupValues[3]) }
            val last = words.last()
            out.append('<').append(msToTag(last.groupValues[1].toLong() + last.groupValues[2].toLong())).append(">\n")
        }
        return out.toString().takeIf { it.isNotBlank() }
    }
}

object SimpMusicProvider : LyricsProvider {
    override val name = "SimpMusic"
    override suspend fun fetch(q: LyricsQuery): String? = withContext(Dispatchers.IO) {
        val id = q.videoId ?: return@withContext null
        val arr = get("https://api-lyrics.simpmusic.org/v1/$id")
            ?.let { JSONObject(it).optJSONArray("data") } ?: return@withContext null
        (0 until arr.length()).map { arr.getJSONObject(it).optString("syncedLyrics") }.firstOrNull { ok(it) }
    }
}

object QQMusicProvider : LyricsProvider {
    override val name = "QQMusic"
    override suspend fun fetch(q: LyricsQuery): String? = withContext(Dispatchers.IO) {
        val ref = "https://y.qq.com"
        val query = "${clean(q.title)} ${firstArtist(q.artist)}"
        val list = get("https://c.y.qq.com/soso/fcgi-bin/client_search_cp?w=${enc(query)}&format=json&n=6&p=1", ref)
            ?.let { JSONObject(it).optJSONObject("data")?.optJSONObject("song")?.optJSONArray("list") }
            ?: return@withContext null
        val best = (0 until list.length()).map { list.getJSONObject(it) }
            .pickByDuration(q) { it.optLong("interval") * 1000 } ?: return@withContext null
        get("https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg?songmid=${best.getString("songmid")}&format=json", ref)
            ?.let { JSONObject(it).optString("lyric") }?.takeIf { it.isNotBlank() }?.let { b64(it) }
    }
}

object KugouProvider : LyricsProvider {
    override val name = "Kugou"
    override suspend fun fetch(q: LyricsQuery): String? = withContext(Dispatchers.IO) {
        val query = "${clean(q.title)} ${firstArtist(q.artist)}"
        val songs = get("https://mobileservice.kugou.com/api/v3/search/song?keyword=${enc(query)}&page=1&pagesize=6&showtype=1")
            ?.let { JSONObject(it).optJSONObject("data")?.optJSONArray("info") } ?: return@withContext null
        val best = (0 until songs.length()).map { songs.getJSONObject(it) }
            .pickByDuration(q) { it.optLong("duration") * 1000 } ?: return@withContext null
        val cand = get("https://lyrics.kugou.com/search?ver=1&man=yes&client=pc&keyword=${enc(query)}&duration=${q.durationMs}&hash=${best.getString("hash")}")
            ?.let { JSONObject(it).optJSONArray("candidates")?.optJSONObject(0) } ?: return@withContext null
        get("https://lyrics.kugou.com/download?ver=1&client=pc&id=${cand.getString("id")}&accesskey=${cand.getString("accesskey")}&fmt=lrc&charset=utf8")
            ?.let { JSONObject(it).optString("content") }?.takeIf { it.isNotBlank() }?.let { b64(it) }
    }
}