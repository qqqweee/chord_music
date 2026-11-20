package com.example.chord.data.stats

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.example.chord.data.history.HistoryStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

data class TopSong(val videoId: String, val title: String, val artist: String, val thumb: String?, val ms: Long)
data class TopArtist(val name: String, val thumb: String?, val ms: Long)

data class ListenStats(
    val todayMs: Long = 0,
    val weekMs: Long = 0,
    val totalMs: Long = 0,
    val streakDays: Int = 0,
    val topSong: TopSong? = null,
    val topArtist: TopArtist? = null,
)

/** Real listening time (paused time is not counted). One row per song per day. */
class ListenStatsStore private constructor(ctx: Context) :
    SQLiteOpenHelper(ctx.applicationContext, "stats.db", null, 1) {

    private val changes = MutableStateFlow(0)
    private val dayFmt get() = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE listen(
                day TEXT NOT NULL, videoId TEXT NOT NULL, title TEXT NOT NULL, artist TEXT NOT NULL,
                thumb TEXT, ms INTEGER NOT NULL, PRIMARY KEY(day, videoId))"""
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, o: Int, n: Int) = Unit
    override fun onDowngrade(db: SQLiteDatabase, o: Int, n: Int) = Unit

    suspend fun add(videoId: String, title: String, artist: String, thumb: String?, ms: Long) =
        withContext(Dispatchers.IO) {
            if (ms <= 0) return@withContext
            val day = dayFmt.format(System.currentTimeMillis())
            val a = HistoryStore.cleanArtist(artist)
            val db = writableDatabase
            // One transaction: a crash between the two statements must not leave a zero row.
            db.beginTransaction()
            try {
                db.execSQL(
                    "INSERT OR IGNORE INTO listen VALUES(?,?,?,?,?,0)",
                    arrayOf<Any?>(day, videoId, title, a, thumb)
                )
                db.execSQL(
                    "UPDATE listen SET ms=ms+?, thumb=COALESCE(?,thumb) WHERE day=? AND videoId=?",
                    arrayOf<Any?>(ms, thumb, day, videoId)
                )
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            changes.update { it + 1 }
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun stats(): Flow<ListenStats> = changes.mapLatest { load() }.flowOn(Dispatchers.IO)

    private fun load(): ListenStats {
        val db = readableDatabase
        val fmt = dayFmt
        val cal = Calendar.getInstance()
        val today = fmt.format(cal.time)
        cal.add(Calendar.DAY_OF_YEAR, -6)
        val weekStart = fmt.format(cal.time)

        fun sum(where: String, vararg a: String): Long =
            db.rawQuery("SELECT COALESCE(SUM(ms),0) FROM listen $where", a.takeIf { it.isNotEmpty() }).use {
                if (it.moveToFirst()) it.getLong(0) else 0L
            }

        val topSong = db.rawQuery(
            "SELECT videoId,title,artist,thumb,SUM(ms) m FROM listen GROUP BY videoId ORDER BY m DESC LIMIT 1", null
        ).use { c -> if (c.moveToFirst()) TopSong(c.getString(0), c.getString(1), c.getString(2), c.getString(3), c.getLong(4)) else null }

        val topArtist = db.rawQuery(
            "SELECT artist,MAX(thumb),SUM(ms) m FROM listen WHERE artist<>'' GROUP BY artist ORDER BY m DESC LIMIT 1", null
        ).use { c -> if (c.moveToFirst()) TopArtist(c.getString(0), c.getString(1), c.getLong(2)) else null }

        // Streak: consecutive days with listening, ending today (or yesterday).
        val days = db.rawQuery("SELECT DISTINCT day FROM listen ORDER BY day DESC", null).use { c ->
            buildSet { while (c.moveToNext()) add(c.getString(0)) }
        }
        val walk = Calendar.getInstance()
        if (fmt.format(walk.time) !in days) walk.add(Calendar.DAY_OF_YEAR, -1)
        var streak = 0
        while (fmt.format(walk.time) in days) { streak++; walk.add(Calendar.DAY_OF_YEAR, -1) }

        return ListenStats(
            todayMs = sum("WHERE day=?", today),
            weekMs = sum("WHERE day>=?", weekStart),
            totalMs = sum(""),
            streakDays = streak,
            topSong = topSong,
            topArtist = topArtist,
        )
    }

    companion object {
        @Volatile private var inst: ListenStatsStore? = null
        fun get(ctx: Context): ListenStatsStore =
            inst ?: synchronized(this) { inst ?: ListenStatsStore(ctx).also { inst = it } }
    }
}

/** 0 -> "0 min", 42 min -> "42 min", 3h 5m -> "3 h 5 min", 120 h -> "120 h". */
fun formatListenTime(ms: Long): String {
    val min = ms / 60_000
    val h = min / 60
    return when {
        h >= 100 -> "$h h"
        h >= 1 -> "$h h ${min % 60} min"
        else -> "$min min"
    }
}
