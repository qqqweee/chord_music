package com.example.chord.data.library

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.example.chord.data.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** One playlist card. [covers] = up to 4 thumbnails for the collage. */
data class PlaylistSummary(
    val id: Long,
    val name: String,
    val count: Int,
    val covers: List<String?>,
    val isFavourites: Boolean,
    /** Where an imported playlist came from: "yt:VL..." or "sp:playlist:ID". Null = made here. */
    val source: String? = null,
)

/**
 * User playlists + the Favourites list (always id [FAVOURITES_ID]). Plain SQLite.
 * Every read is a Flow that re-runs after any write, so UI stays live.
 *
 * To add a field to a song row: bump DB version, add a migration in [onUpgrade].
 */
class LibraryStore private constructor(ctx: Context) :
    SQLiteOpenHelper(ctx.applicationContext, "library.db", null, 2) {

    private val changes = MutableStateFlow(0)
    /** Toggle is read-then-write. Two fast taps must not race. */
    private val toggleLock = Mutex()

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE playlists(
                id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL,
                kind INTEGER NOT NULL DEFAULT 0, createdAt INTEGER NOT NULL, source TEXT)"""
        )
        db.execSQL(
            """CREATE TABLE playlist_songs(
                playlistId INTEGER NOT NULL, videoId TEXT NOT NULL, title TEXT NOT NULL,
                artist TEXT NOT NULL, thumb TEXT, addedAt INTEGER NOT NULL,
                PRIMARY KEY(playlistId, videoId))"""
        )
        db.execSQL("CREATE INDEX idx_ps_video ON playlist_songs(videoId)")
        val fav = ContentValues().apply {
            put("id", FAVOURITES_ID); put("name", "Favourites"); put("kind", 1)
            put("createdAt", System.currentTimeMillis())
        }
        db.insert("playlists", null, fav)
    }

    override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit   // older build over newer data: keep data, do not crash

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) db.execSQL("ALTER TABLE playlists ADD COLUMN source TEXT")
    }

    // ---- reads ---------------------------------------------------------------

    @OptIn(ExperimentalCoroutinesApi::class)
    fun playlists(): Flow<List<PlaylistSummary>> =
        changes.mapLatest { loadPlaylists() }.flowOn(Dispatchers.IO)

    @OptIn(ExperimentalCoroutinesApi::class)
    fun playlist(id: Long): Flow<PlaylistSummary?> =
        changes.mapLatest { loadPlaylists().firstOrNull { it.id == id } }.flowOn(Dispatchers.IO)

    @OptIn(ExperimentalCoroutinesApi::class)
    fun songs(playlistId: Long): Flow<List<Song>> =
        changes.mapLatest { loadSongs(playlistId) }.flowOn(Dispatchers.IO)

    @OptIn(ExperimentalCoroutinesApi::class)
    fun favouriteIds(): Flow<Set<String>> =
        changes.mapLatest {
            query("SELECT videoId FROM playlist_songs WHERE playlistId=?", FAVOURITES_ID.toString()) { it.getString(0) }.toSet()
        }.flowOn(Dispatchers.IO)

    /** Ids of playlists that already hold [videoId]. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun membership(videoId: String): Flow<Set<Long>> =
        changes.mapLatest {
            query("SELECT playlistId FROM playlist_songs WHERE videoId=?", videoId) { it.getLong(0) }.toSet()
        }.flowOn(Dispatchers.IO)

    // ---- writes --------------------------------------------------------------

    suspend fun create(name: String, source: String? = null): Long = withContext(Dispatchers.IO) {
        val cv = ContentValues().apply {
            put("name", name.trim().ifBlank { "New playlist" }); put("kind", 0); put("source", source)
            put("createdAt", System.currentTimeMillis())
        }
        val id = writableDatabase.insert("playlists", null, cv)
        changes.update { it + 1 }
        id
    }

    suspend fun rename(id: Long, name: String) = withContext(Dispatchers.IO) {
        if (id == FAVOURITES_ID || name.isBlank()) return@withContext
        writableDatabase.execSQL("UPDATE playlists SET name=? WHERE id=?", arrayOf<Any>(name.trim(), id))
        changes.update { it + 1 }
    }

    suspend fun delete(id: Long) = withContext(Dispatchers.IO) {
        if (id == FAVOURITES_ID) return@withContext
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.execSQL("DELETE FROM playlist_songs WHERE playlistId=?", arrayOf<Any>(id))
            db.execSQL("DELETE FROM playlists WHERE id=?", arrayOf<Any>(id))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        changes.update { it + 1 }
    }

    /** Add if missing, remove if there. Returns true when the song is now IN the playlist. */
    suspend fun toggle(playlistId: Long, song: Song): Boolean = withContext(Dispatchers.IO) { toggleLock.withLock {
        val db = writableDatabase
        val exists = query(
            "SELECT 1 FROM playlist_songs WHERE playlistId=? AND videoId=?", playlistId.toString(), song.videoId
        ) { 1 }.isNotEmpty()
        if (exists) {
            db.execSQL("DELETE FROM playlist_songs WHERE playlistId=? AND videoId=?", arrayOf<Any>(playlistId, song.videoId))
        } else {
            db.execSQL(
                "INSERT OR IGNORE INTO playlist_songs VALUES(?,?,?,?,?,?)",
                arrayOf<Any?>(playlistId, song.videoId, song.title, song.details, song.thumbnailUrl, System.currentTimeMillis())
            )
        }
        changes.update { it + 1 }
        !exists
    } }

    suspend fun removeAll(playlistId: Long, videoIds: Collection<String>) = withContext(Dispatchers.IO) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            videoIds.forEach { db.execSQL("DELETE FROM playlist_songs WHERE playlistId=? AND videoId=?", arrayOf<Any>(playlistId, it)) }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        changes.update { it + 1 }
    }

    suspend fun toggleFavourite(song: Song): Boolean = toggle(FAVOURITES_ID, song)

    suspend fun remove(playlistId: Long, videoId: String) = withContext(Dispatchers.IO) {
        writableDatabase.execSQL("DELETE FROM playlist_songs WHERE playlistId=? AND videoId=?", arrayOf<Any>(playlistId, videoId))
        changes.update { it + 1 }
    }

    /** Bulk add (imports). Keeps list order: first song shows first. Skips songs already inside. */
    suspend fun addAll(playlistId: Long, songs: List<Song>) = withContext(Dispatchers.IO) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val now = System.currentTimeMillis()
            songs.forEachIndexed { i, s ->
                db.execSQL(
                    "INSERT OR IGNORE INTO playlist_songs VALUES(?,?,?,?,?,?)",
                    arrayOf<Any?>(playlistId, s.videoId, s.title, s.details, s.thumbnailUrl, now - i)
                )
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        changes.update { it + 1 }
    }

    // ---- internals -----------------------------------------------------------

    private fun loadPlaylists(): List<PlaylistSummary> {
        val rows = query(
            """SELECT p.id,p.name,p.kind,(SELECT COUNT(*) FROM playlist_songs s WHERE s.playlistId=p.id),p.source
               FROM playlists p ORDER BY p.kind DESC, p.createdAt DESC"""
        ) { Pair(Triple(it.getLong(0), it.getString(1), it.getInt(2) to it.getInt(3)), it.getString(4)) }
        return rows.map { (row, source) ->
            val (id, name, kc) = row
            val covers = query(
                "SELECT thumb FROM playlist_songs WHERE playlistId=? ORDER BY addedAt DESC LIMIT 4", id.toString()
            ) { it.getString(0) }
            PlaylistSummary(id, name, kc.second, covers, isFavourites = kc.first == 1, source = source)
        }
    }

    private fun loadSongs(id: Long): List<Song> =
        query(
            "SELECT videoId,title,artist,thumb FROM playlist_songs WHERE playlistId=? ORDER BY addedAt DESC", id.toString()
        ) { Song(title = it.getString(1), details = it.getString(2), videoId = it.getString(0), thumbnailUrl = it.getString(3)) }

    private inline fun <T> query(sql: String, vararg args: String, map: (android.database.Cursor) -> T): List<T> =
        readableDatabase.rawQuery(sql, args.takeIf { it.isNotEmpty() }).use { c ->
            buildList { while (c.moveToNext()) add(map(c)) }
        }

    companion object {
        const val FAVOURITES_ID = 1L
        @Volatile private var inst: LibraryStore? = null
        fun get(ctx: Context): LibraryStore =
            inst ?: synchronized(this) { inst ?: LibraryStore(ctx).also { inst = it } }
    }
}
