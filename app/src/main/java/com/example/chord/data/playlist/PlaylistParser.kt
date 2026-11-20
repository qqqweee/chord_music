package com.example.chord.data.playlist

import org.json.JSONArray
import org.json.JSONObject

/**
 * Turns a YouTube Music "browse" response (playlist `VL...` or album `MPRE...`)
 * into a header plus a track list. Walks the JSON by renderer name, so small
 * layout changes on YouTube's side do not break it.
 */
object PlaylistParser {
    private val SHELVES = setOf("musicPlaylistShelfRenderer", "musicShelfRenderer")
    private val HEADERS = setOf("musicResponsiveHeaderRenderer", "musicDetailHeaderRenderer")
    private val PLAYS = Regex("\\d\\s*(plays?|views?)", RegexOption.IGNORE_CASE)
    private val KINDS = setOf("album", "ep", "single", "playlist", "podcast")

    fun parsePage(root: JSONObject): PlaylistPage {
        val header = firstValue(root, HEADERS)?.let(::header)
        val shelves = ArrayList<JSONObject>().also { collectShelves(root, it) }
        val artist = header?.let(::fallbackArtist).orEmpty()
        val tracks = shelves.flatMap { rows(it.optJSONArray("contents"), artist, header?.thumbnailUrl) }
        return PlaylistPage(header, tracks, shelves.firstNotNullOfOrNull { token(it) })
    }

    /** Next page of a long playlist. */
    fun parseContinuation(root: JSONObject): Pair<List<PlaylistTrack>, String?> {
        val found = ArrayList<JSONObject>().also { collectRows(root, it) }
        return found.mapNotNull { row(it, "", null) } to token(root)
    }

    // ── header ─────────────────────────────────────────────────────────────

    private fun header(h: JSONObject): PlaylistHeader {
        val description = runs(
            h.optJSONObject("description")?.optJSONObject("musicDescriptionShelfRenderer")?.optJSONObject("description"),
        ).ifBlank { runs(h.optJSONObject("description")) }
        val thumb = findThumbnails(h)?.let { lastUrl(it) }?.let { sized(it, 600) }
        return PlaylistHeader(
            title = runs(h.optJSONObject("title")),
            byline = runs(h.optJSONObject("straplineTextOne")),
            subtitle = runs(h.optJSONObject("subtitle")),
            secondSubtitle = runs(h.optJSONObject("secondSubtitle")),
            description = description,
            thumbnailUrl = thumb,
        )
    }

    private fun fallbackArtist(h: PlaylistHeader): String {
        if (h.byline.isNotBlank()) return h.byline
        val parts = h.subtitle.split(" • ").map { it.trim() }
        return if (parts.size > 1 && parts[0].lowercase() in KINDS) parts[1] else ""
    }

    // ── tracks ─────────────────────────────────────────────────────────────

    private fun rows(contents: JSONArray?, artist: String, thumb: String?): List<PlaylistTrack> {
        contents ?: return emptyList()
        return (0 until contents.length()).mapNotNull { i ->
            contents.optJSONObject(i)?.optJSONObject("musicResponsiveListItemRenderer")?.let { row(it, artist, thumb) }
        }
    }

    private fun row(r: JSONObject, fallbackArtist: String, fallbackThumb: String?): PlaylistTrack? {
        // Greyed-out rows are tracks YouTube cannot play any more.
        if (r.optString("musicItemRendererDisplayPolicy").contains("GREY_OUT")) return null
        val videoId = r.optJSONObject("playlistItemData")?.optString("videoId")?.takeIf { it.isNotBlank() } ?: return null
        val cols = r.optJSONArray("flexColumns") ?: return null
        fun col(i: Int) = cols.optJSONObject(i)
            ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")?.optJSONObject("text")

        val title = runs(col(0)).takeIf { it.isNotBlank() } ?: return null
        var artist = runs(col(1)).substringBefore(" • ").trim()
        // Album rows show a play count where playlists show the artist.
        if (artist.isBlank() || PLAYS.containsMatchIn(artist)) artist = fallbackArtist
        val duration = runs(
            r.optJSONArray("fixedColumns")?.optJSONObject(0)
                ?.optJSONObject("musicResponsiveListItemFixedColumnRenderer")?.optJSONObject("text"),
        )
        val thumb = r.optJSONObject("thumbnail")?.optJSONObject("musicThumbnailRenderer")
            ?.let(::findThumbnails)?.let { lastUrl(it) }?.let { sized(it, 226) }
            ?: fallbackThumb
        com.example.chord.data.explicit.ExplicitStore.mark(videoId, r)
        return PlaylistTrack(videoId, title, artist, duration, thumb)
    }

    // ── JSON walking ───────────────────────────────────────────────────────

    private fun collectShelves(node: Any?, out: MutableList<JSONObject>) {
        when (node) {
            is JSONObject -> {
                SHELVES.forEach { key -> node.optJSONObject(key)?.let(out::add) }
                node.keys().forEach { collectShelves(node.opt(it), out) }
            }
            is JSONArray -> for (i in 0 until node.length()) collectShelves(node.opt(i), out)
        }
    }

    private fun collectRows(node: Any?, out: MutableList<JSONObject>) {
        when (node) {
            is JSONObject -> {
                node.optJSONObject("musicResponsiveListItemRenderer")?.let(out::add)
                node.keys().forEach { collectRows(node.opt(it), out) }
            }
            is JSONArray -> for (i in 0 until node.length()) collectRows(node.opt(i), out)
        }
    }

    private fun firstValue(node: Any?, keys: Set<String>): JSONObject? = when (node) {
        is JSONObject -> keys.firstNotNullOfOrNull { node.optJSONObject(it) }
            ?: node.keys().asSequence().firstNotNullOfOrNull { firstValue(node.opt(it), keys) }
        is JSONArray -> (0 until node.length()).firstNotNullOfOrNull { firstValue(node.opt(it), keys) }
        else -> null
    }

    private fun findThumbnails(node: Any?): JSONArray? = when (node) {
        is JSONObject -> node.optJSONArray("thumbnails")
            ?: node.keys().asSequence().firstNotNullOfOrNull { findThumbnails(node.opt(it)) }
        is JSONArray -> (0 until node.length()).firstNotNullOfOrNull { findThumbnails(node.opt(it)) }
        else -> null
    }

    private fun lastUrl(a: JSONArray): String? =
        a.optJSONObject(a.length() - 1)?.optString("url")?.takeIf { it.isNotBlank() }

    private fun token(node: Any?): String? = when (node) {
        is JSONObject ->
            node.optJSONObject("nextContinuationData")?.optString("continuation")?.takeIf { it.isNotBlank() }
                ?: node.optJSONObject("continuationItemRenderer")?.optJSONObject("continuationEndpoint")
                    ?.optJSONObject("continuationCommand")?.optString("token")?.takeIf { it.isNotBlank() }
                ?: node.keys().asSequence().firstNotNullOfOrNull { token(node.opt(it)) }
        is JSONArray -> (0 until node.length()).firstNotNullOfOrNull { token(node.opt(it)) }
        else -> null
    }

    private fun runs(o: JSONObject?): String {
        val a = o?.optJSONArray("runs") ?: return ""
        return (0 until a.length()).joinToString("") { a.optJSONObject(it)?.optString("text").orEmpty() }
    }

    /** Ask Google's image host for the size we need instead of a thumbnail. */
    private fun sized(url: String, px: Int): String =
        if ("googleusercontent" in url || "ggpht" in url) {
            url.replace(Regex("=w\\d+-h\\d+[^&]*$"), "=w$px-h$px-l90-rj")
                .replace(Regex("=s\\d+[^&]*$"), "=s$px")
        } else url
}
