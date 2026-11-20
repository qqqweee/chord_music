
package com.example.chord.data.history

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.example.chord.data.home.HomeItem
import com.example.chord.data.home.HomeItemType
import com.example.chord.data.home.HomeShelf
import com.example.chord.data.home.ShelfStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.util.Calendar

/**
 * Local listening memory.
 *
 * play_history: one row per song.
 * play_log: one row per qualified play.
 */
class HistoryStore private constructor(ctx: Context) :
    SQLiteOpenHelper(ctx.applicationContext, "history.db", null, 2) {

    private val changes = MutableStateFlow(0)
    private val seed = MutableStateFlow(System.currentTimeMillis().toInt())
    private val lastCounted = HashMap<String, Long>()

    private var heroId: String? = null
    private var heroSeed = -1

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE play_history(
                videoId TEXT PRIMARY KEY,
                title TEXT NOT NULL,
                artist TEXT NOT NULL,
                thumb TEXT,
                playCount INTEGER NOT NULL,
                lastPlayed INTEGER NOT NULL
            )"""
        )
        db.execSQL("CREATE INDEX idx_last ON play_history(lastPlayed)")
        createLog(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) createLog(db)
    }

    override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    private fun createLog(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS play_log(videoId TEXT NOT NULL, ts INTEGER NOT NULL)"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_log_id ON play_log(videoId)")
    }

    // ---- Write ---------------------------------------------------------------

    /** Song started. Updates recency without increasing play count. */
    suspend fun touch(
        id: String,
        title: String,
        artist: String,
        thumb: String?
    ) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val clean = cleanArtist(artist)
        val db = writableDatabase

        db.execSQL(
            "INSERT OR IGNORE INTO play_history VALUES(?,?,?,?,0,?)",
            arrayOf<Any?>(id, title, clean, thumb, now)
        )

        db.execSQL(
            """UPDATE play_history
               SET lastPlayed=?, title=?, artist=?, thumb=COALESCE(?,thumb)
               WHERE videoId=?""",
            arrayOf<Any?>(now, title, clean, thumb, id)
        )

        changes.update { it + 1 }
    }

    /** A qualified play. Same song within 90 seconds counts once. */
    suspend fun record(
        id: String,
        title: String,
        artist: String,
        thumb: String?
    ) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()

        synchronized(lastCounted) {
            val previous = lastCounted[id]
            if (previous != null && now - previous < 90_000L) {
                return@withContext
            }
            lastCounted[id] = now
        }

        val clean = cleanArtist(artist)
        val db = writableDatabase

        db.beginTransaction()
        try {
            db.execSQL(
                "INSERT OR IGNORE INTO play_history VALUES(?,?,?,?,0,?)",
                arrayOf<Any?>(id, title, clean, thumb, now)
            )

            db.execSQL(
                """UPDATE play_history
                   SET playCount=playCount+1,
                       lastPlayed=?,
                       title=?,
                       artist=?,
                       thumb=COALESCE(?,thumb)
                   WHERE videoId=?""",
                arrayOf<Any?>(now, title, clean, thumb, id)
            )

            db.execSQL(
                "INSERT INTO play_log VALUES(?,?)",
                arrayOf<Any?>(id, now)
            )

            // Keep the log from growing forever: drop plays older than 2 years.
            db.execSQL(
                "DELETE FROM play_log WHERE ts < ?",
                arrayOf<Any?>(now - LOG_KEEP_MS)
            )

            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }

        changes.update { it + 1 }
    }

    /** Changes the selection seed and rebuilds personalized shelves. */
    fun reshuffle() {
        seed.update { it + 1 }
    }

    // ---- Read: Home ----------------------------------------------------------

    @OptIn(ExperimentalCoroutinesApi::class)
    fun shelves(): Flow<List<HomeShelf>> =
        combine(changes, seed) { _, currentSeed -> currentSeed }
            .mapLatest { build(it) }
            .flowOn(Dispatchers.IO)

    // ---- Library lists -------------------------------------------------------

    @OptIn(ExperimentalCoroutinesApi::class)
    fun recent(): Flow<List<HomeItem>> =
        changes.mapLatest {
            songs("SELECT $COLS FROM play_history ORDER BY lastPlayed DESC LIMIT 100")
        }.flowOn(Dispatchers.IO)

    @OptIn(ExperimentalCoroutinesApi::class)
    fun mostPlayed(): Flow<List<HomeItem>> =
        changes.mapLatest {
            songs(
                """SELECT $COLS FROM play_history
                   WHERE playCount>0
                   ORDER BY playCount DESC, lastPlayed DESC
                   LIMIT 100"""
            )
        }.flowOn(Dispatchers.IO)

    /**
     * Previously played favourites that have genuinely gone quiet.
     * Uses play_log because touch() is not a qualified play.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun forgotten(): Flow<List<HomeItem>> =
        changes.mapLatest {
            val cutoff = System.currentTimeMillis() - FORGOTTEN_AFTER_MS

            readableDatabase.rawQuery(
                """SELECT h.videoId, h.title, h.artist, h.thumb
                   FROM play_history h
                   INNER JOIN (
                       SELECT videoId, MAX(ts) AS lastRealPlay
                       FROM play_log
                       GROUP BY videoId
                   ) p ON p.videoId = h.videoId
                   WHERE h.playCount >= 2 AND p.lastRealPlay < ?
                   ORDER BY
                       (h.playCount * 1.0) /
                       (1.0 + ((? - p.lastRealPlay) / 2592000000.0)) DESC,
                       p.lastRealPlay ASC
                   LIMIT 30""",
                arrayOf(
                    cutoff.toString(),
                    System.currentTimeMillis().toString()
                )
            ).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        add(
                            HomeItem(
                                title = cursor.getString(1),
                                subtitle = cursor.getString(2),
                                thumbnailUrl = cursor.getString(3),
                                videoId = cursor.getString(0),
                                browseId = null,
                                type = HomeItemType.Song
                            )
                        )
                    }
                }
            }
        }.flowOn(Dispatchers.IO)

    @OptIn(ExperimentalCoroutinesApi::class)
    fun artists(): Flow<List<HomeItem>> =
        changes.mapLatest { artistRows(50) }.flowOn(Dispatchers.IO)

    /** IDs of all songs already in listening history. */
    suspend fun knownIds(): Set<String> = withContext(Dispatchers.IO) {
        readableDatabase.rawQuery(
            "SELECT videoId FROM play_history",
            null
        ).use { cursor ->
            buildSet {
                while (cursor.moveToNext()) {
                    add(cursor.getString(0))
                }
            }
        }
    }

    /** Songs used to seed personalized radio recommendations. */
    suspend fun seedSongs(limit: Int): List<HomeItem> =
        withContext(Dispatchers.IO) {
            (
                    songs(
                        """SELECT $COLS FROM play_history
                       WHERE playCount>0
                       ORDER BY playCount DESC, lastPlayed DESC
                       LIMIT 15"""
                    ) +
                            songs(
                                """SELECT $COLS FROM play_history
                       ORDER BY lastPlayed DESC
                       LIMIT 5"""
                            )
                    ).distinctBy { it.videoId }.take(limit)
        }

    // ---- Build Home shelves --------------------------------------------------

    private fun build(seedValue: Int): List<HomeShelf> {
        val now = System.currentTimeMillis()
        val out = ArrayList<HomeShelf>(8)

        // Recent listening has more weight than older listening.
        var scored = songs(
            """SELECT h.videoId,h.title,h.artist,h.thumb
               FROM play_log l
               JOIN play_history h ON h.videoId=l.videoId
               GROUP BY l.videoId
               ORDER BY
                   SUM(1.0/(1.0+($now-l.ts)/604800000.0)) DESC,
                   MAX(l.ts) DESC
               LIMIT 30"""
        )

        if (scored.size < 4) {
            scored = songs(
                """SELECT $COLS FROM play_history
                   WHERE playCount>0
                   ORDER BY playCount DESC, lastPlayed DESC
                   LIMIT 30"""
            )
        }

        // 1. Top Picks.
        val pool = scored.take(8)

        val hero: HomeItem? = if (pool.size >= 3) {
            if (
                heroSeed != seedValue ||
                heroId == null ||
                pool.none { it.videoId == heroId }
            ) {
                heroId = pool.random().videoId
                heroSeed = seedValue
            }

            pool.firstOrNull { it.videoId == heroId }
        } else {
            null
        }

        val shown = HashSet<String>()

        if (hero != null) {
            val picks = listOf(hero) +
                    scored.filter { it.videoId != hero.videoId }.take(4)

            picks.forEach { it.videoId?.let(shown::add) }

            out += HomeShelf(
                "Top Picks for You",
                "",
                picks,
                false,
                true,
                ShelfStyle.TopPicks
            )
        }

        // 2. Speed Dial.
        // Mix most-played songs with recent listening.
        // Shuffle a larger pool using the current seed.
        val dialPool = (
                songs(
                    """SELECT $COLS FROM play_history
                   WHERE playCount>0
                   ORDER BY playCount DESC, lastPlayed DESC
                   LIMIT 30"""
                ) +
                        songs(
                            """SELECT $COLS FROM play_history
                   ORDER BY lastPlayed DESC
                   LIMIT 30"""
                        )
                ).distinctBy { it.videoId }

        val dial = dialPool
            .shuffled(java.util.Random(seedValue.toLong()))
            .take(16)

        if (dial.size >= 4) {
            out += HomeShelf(
                "Speed dial",
                "",
                dial,
                false,
                true,
                ShelfStyle.SpeedDial
            )
        }

        // 3. Recents.
        val recents = songs(
            """SELECT $COLS FROM play_history
               ORDER BY lastPlayed DESC
               LIMIT 24"""
        ).filter { it.videoId !in shown }.take(15)

        if (recents.size >= 3) {
            recents.forEach { it.videoId?.let(shown::add) }

            out += HomeShelf(
                "Recents",
                "",
                recents,
                true,
                true
            )
        }

        // 4. Time-of-day listening mix.
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)

        val byHour = songs(
            """SELECT h.videoId,h.title,h.artist,h.thumb
               FROM play_log l
               JOIN play_history h ON h.videoId=l.videoId
               WHERE (
                   (CAST(strftime('%H', l.ts/1000, 'unixepoch', 'localtime') AS INTEGER)
                   - $hour + 24) % 24
               ) IN (0,1,2,22,23)
               GROUP BY l.videoId
               ORDER BY COUNT(*) DESC, MAX(l.ts) DESC
               LIMIT 20"""
        ).filter { it.videoId !in shown }.take(12)

        if (byHour.size >= 3) {
            out += HomeShelf(
                partOfDay(hour),
                "",
                byHour,
                false,
                true
            )
        }

        // 5. Artists.
        val artists = artistRows(10)

        if (artists.size >= 3) {
            out += HomeShelf(
                "Your artists",
                "",
                artists,
                false,
                true
            )
        }

        return out
    }

    private fun partOfDay(hour: Int) = when (hour) {
        in 5..11 -> "Morning mix"
        in 12..16 -> "Afternoon mix"
        in 17..21 -> "Evening mix"
        else -> "Late night mix"
    }

    private fun songs(sql: String, args: Array<String>? = null): List<HomeItem> =
        readableDatabase.rawQuery(sql, args).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        HomeItem(
                            title = cursor.getString(1),
                            subtitle = cursor.getString(2),
                            thumbnailUrl = cursor.getString(3),
                            videoId = cursor.getString(0),
                            browseId = null,
                            type = HomeItemType.Song
                        )
                    )
                }
            }
        }

    private fun artistRows(limit: Int): List<HomeItem> =
        readableDatabase.rawQuery(
            """SELECT artist,thumb,SUM(playCount) c
               FROM play_history
               WHERE artist<>''
               GROUP BY artist
               ORDER BY c DESC, MAX(lastPlayed) DESC
               LIMIT $limit""",
            null
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        HomeItem(
                            cursor.getString(0),
                            "${cursor.getInt(2)} plays",
                            cursor.getString(1),
                            null,
                            null,
                            HomeItemType.Artist
                        )
                    )
                }
            }
        }

    companion object {
        private const val COLS = "videoId,title,artist,thumb"
        private const val FORGOTTEN_AFTER_MS = 30L * 24 * 60 * 60 * 1000
        private const val LOG_KEEP_MS = 2L * 365 * 24 * 60 * 60 * 1000

        @Volatile
        private var inst: HistoryStore? = null

        fun get(ctx: Context): HistoryStore =
            inst ?: synchronized(this) {
                inst ?: HistoryStore(ctx).also { inst = it }
            }

        private val NOISE = setOf(
            "song",
            "video",
            "single",
            "album",
            "ep"
        )

        /** "Song • Daft Punk • 1.2M plays" -> "Daft Punk". */
        fun cleanArtist(raw: String): String =
            raw.split("•", "·")
                .map { it.trim() }
                .firstOrNull {
                    it.isNotBlank() && it.lowercase() !in NOISE
                }.orEmpty()
    }
}
