package com.example.chord.data.explicit

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.Collections

/**
 * Remembers which songs YouTube Music marks Explicit (the "E" badge).
 * Parsers call [mark] while reading a row. Any screen asks [has]. Saved on disk,
 * so history, library and playlists show the badge after a restart too.
 */
object ExplicitStore {
    private const val FILE = "explicit_ids"
    private const val KEY = "ids"
    private const val TAG = "MUSIC_EXPLICIT_BADGE"

    private val ids: MutableSet<String> = Collections.synchronizedSet(HashSet())
    private var prefs: android.content.SharedPreferences? = null
    private val main = Handler(Looper.getMainLooper())
    private val flushTask = Runnable { flush() }

    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        prefs = p
        ids.addAll(p.getStringSet(KEY, emptySet()).orEmpty())
    }

    fun has(videoId: String?): Boolean = videoId != null && videoId in ids

    /** [row] is the JSON of one song row. Adds the id when the row carries the explicit badge. */
    fun mark(videoId: String?, row: Any?) {
        if (videoId.isNullOrBlank() || row == null) return
        if (videoId in ids) return
        if (!row.toString().contains(TAG)) return
        ids.add(videoId)
        main.removeCallbacks(flushTask)
        main.postDelayed(flushTask, 1_500)
    }

    private fun flush() {
        val snapshot = synchronized(ids) { HashSet(ids) }
        prefs?.edit()?.putStringSet(KEY, snapshot)?.apply()
    }
}
