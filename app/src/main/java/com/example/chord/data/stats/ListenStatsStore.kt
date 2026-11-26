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

data class RecapArtist(val name: String, val thumb: String?, val ms: Long, val topSong: TopSong?)

/** Numbers behind one Story. [hourMs] is 24 long, [weekdayMs] is 7 long (Mon..Sun). */
data class Recap(
    val days: Int,
    val totalMs: Long,
    val songCount: Int,
    val activeDays: Int,
    val topSongs: List<TopSong>,
    val topArtists: List<RecapArtist>,
    val hourMs: LongArray,
    val weekdayMs: LongArray,
    val streakDays: Int,
) {
    val isEmpty get() = totalMs < 60_000L
    val hasHours get() = hourMs.any { it > 0 }
    /** The song the Story plays first: your most played song of the period. */
    val leadSong: TopSong? get() = topSongs.firstOrNull()
}

/** Real listening time (paused time is not counted). One row per song per day. */
class ListenStatsStore private constructor(ctx: Context) :
    SQLiteOpenHelper(ctx.applicationContext, "stats.db", null, 2) {

    private val changes = MutableStateFlow(0)
    private val dayFmt get() = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE listen(
                day TEXT NOT NULL, videoId TEXT NOT NULL, title TEXT NOT NULL, artist TEXT NOT NULL,
                thumb TEXT, ms INTEGER NOT NULL, PRIMARY KEY(day, videoId))"""
        )
        createHourly(db)
    }

    /** v2: listening time per day + hour, so recaps can say "you are a night owl". */
    private fun createHourly(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS hourly(day TEXT NOT NULL, hour INTEGER NOT NULL, ms INTEGER NOT NULL, PRIMARY KEY(day, hour))")
    }

    override fun onUpgrade(db: SQLiteDatabase, o: Int, n: Int) { if (o < 2) createHourly(db) }
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
                val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
                db.execSQL("INSERT OR IGNORE INTO hourly VALUES(?,?,0)", arrayOf<Any?>(day, hour))
                db.execSQL("UPDATE hourly SET ms=ms+? WHERE day=? AND hour=?", arrayOf<Any?>(ms, day, hour))
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

    /** Total listening ms in the last [days] days (cheap check for the notification worker). */
    suspend fun totalMs(days: Int): Long = withContext(Dispatchers.IO) {
        val from = dayFmt.format(Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -(days - 1)) }.time)
        readableDatabase.rawQuery("SELECT COALESCE(SUM(ms),0) FROM listen WHERE day>=?", arrayOf(from)).use {
            if (it.moveToFirst()) it.getLong(0) else 0L
        }
    }

    /** Everything a Story needs for the last [days] days. */
    suspend fun recap(days: Int): Recap = withContext(Dispatchers.IO) {
        val db = readableDatabase
        val fmt = dayFmt
        val from = fmt.format(Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -(days - 1)) }.time)
        val a = arrayOf(from)

        val total = db.rawQuery("SELECT COALESCE(SUM(ms),0), COUNT(DISTINCT videoId), COUNT(DISTINCT day) FROM listen WHERE day>=?", a).use {
            if (it.moveToFirst()) Triple(it.getLong(0), it.getInt(1), it.getInt(2)) else Triple(0L, 0, 0)
        }

        val songs = db.rawQuery(
            "SELECT videoId,title,artist,MAX(thumb),SUM(ms) m FROM listen WHERE day>=? GROUP BY videoId ORDER BY m DESC LIMIT 5", a
        ).use { c -> buildList { while (c.moveToNext()) add(TopSong(c.getString(0), c.getString(1), c.getString(2), c.getString(3), c.getLong(4))) } }

        val artists = db.rawQuery(
            "SELECT artist,MAX(thumb),SUM(ms) m FROM listen WHERE day>=? AND artist<>'' GROUP BY artist ORDER BY m DESC LIMIT 5", a
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    val name = c.getString(0)
                    // The song of THIS artist the user played most. This is what the story plays.
                    val best = db.rawQuery(
                        "SELECT videoId,title,artist,MAX(thumb),SUM(ms) m FROM listen WHERE day>=? AND artist=? GROUP BY videoId ORDER BY m DESC LIMIT 1",
                        arrayOf(from, name)
                    ).use { s -> if (s.moveToFirst()) TopSong(s.getString(0), s.getString(1), s.getString(2), s.getString(3), s.getLong(4)) else null }
                    add(RecapArtist(name, c.getString(1) ?: best?.thumb, c.getLong(2), best))
                }
            }
        }

        // Hours of day (0..23).
        val hours = LongArray(24)
        db.rawQuery("SELECT hour, SUM(ms) FROM hourly WHERE day>=? GROUP BY hour", a).use { while (it.moveToNext()) hours[it.getInt(0)] = it.getLong(1) }

        // Weekday totals, Monday = 0.
        val week = LongArray(7)
        db.rawQuery("SELECT day, SUM(ms) FROM listen WHERE day>=? GROUP BY day", a).use {
            val cal = Calendar.getInstance()
            while (it.moveToNext()) {
                val d = runCatching { fmt.parse(it.getString(0)) }.getOrNull() ?: continue
                cal.time = d
                week[(cal.get(Calendar.DAY_OF_WEEK) + 5) % 7] += it.getLong(1)
            }
        }

        Recap(
            days = days, totalMs = total.first, songCount = total.second, activeDays = total.third,
            topSongs = songs, topArtists = artists, hourMs = hours, weekdayMs = week,
            streakDays = load().streakDays,
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
