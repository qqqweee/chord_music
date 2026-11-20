package com.example.chord.playback

import android.content.Context
import com.example.chord.data.model.Song
import org.json.JSONArray
import org.json.JSONObject

/**
 * Remembers what was playing when the app closed: the queue, which song, and where in the song.
 * On next start the mini player shows that song (paused). Tap play = it continues from the spot.
 *
 * Queue is written only when it changes. Position is cheap (3 numbers), written every few seconds.
 */
class LastPlayedStore(context: Context) {

    class Snapshot(
        val queue: List<Song>,
        val index: Int,
        val positionMs: Long,
        val durationMs: Long,
    ) {
        val current: Song get() = queue[index]
    }

    private val prefs = context.applicationContext
        .getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** Last queue we wrote. Same queue again = skip the write. */
    private var queueSig = 0

    fun load(): Snapshot? {
        return try {
            val raw = prefs.getString(KEY_QUEUE, null) ?: return null
            val arr = JSONArray(raw)
            val songs = ArrayList<Song>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optString("i")
                if (id.isBlank()) continue
                songs += Song(
                    title = o.optString("t"),
                    details = o.optString("d"),
                    videoId = id,
                    thumbnailUrl = o.optString("u").takeIf { it.isNotBlank() },
                )
            }
            if (songs.isEmpty()) return null
            Snapshot(
                queue = songs,
                index = prefs.getInt(KEY_INDEX, 0).coerceIn(0, songs.lastIndex),
                positionMs = prefs.getLong(KEY_POS, 0L).coerceAtLeast(0L),
                durationMs = prefs.getLong(KEY_DUR, 0L).coerceAtLeast(0L),
            )
        } catch (e: Exception) {
            null   // broken data: show nothing, never crash the app on start
        }
    }

    fun saveQueue(queue: List<Song>) {
        if (queue.isEmpty()) return
        val sig = queue.hashCode()
        if (sig == queueSig) return
        queueSig = sig
        val arr = JSONArray()
        for (s in queue) {
            arr.put(
                JSONObject()
                    .put("i", s.videoId)
                    .put("t", s.title)
                    .put("d", s.details)
                    .put("u", s.thumbnailUrl.orEmpty())
            )
        }
        prefs.edit().putString(KEY_QUEUE, arr.toString()).apply()
    }

    fun saveProgress(index: Int, positionMs: Long, durationMs: Long) {
        prefs.edit()
            .putInt(KEY_INDEX, index)
            .putLong(KEY_POS, positionMs)
            .putLong(KEY_DUR, durationMs)
            .apply()
    }

    /** Queue was emptied by the person. Next start shows no mini player. */
    fun clear() {
        queueSig = 0
        prefs.edit().clear().apply()
    }

    private companion object {
        const val FILE = "last_played"
        const val KEY_QUEUE = "queue"
        const val KEY_INDEX = "index"
        const val KEY_POS = "pos"
        const val KEY_DUR = "dur"
    }
}