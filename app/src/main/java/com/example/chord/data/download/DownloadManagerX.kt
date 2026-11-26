package com.example.chord.data.download

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import com.example.chord.data.model.Song
import com.example.chord.playback.AudioStreamExtractor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

data class DownloadedSong(
    val videoId: String,
    val title: String,
    val artist: String,
    val thumb: String?,
    val path: String,
    val quality: String,
    val bytes: Long,
) {
    fun toSong() = Song(title = title, details = artist, videoId = videoId, thumbnailUrl = thumb)
}

/**
 * Saves the best-quality audio of a song to the phone and plays it from there with no network.
 *
 *  enqueue(song)  put in line. One song at a time.
 *  active         videoId -> progress 0..1 (-1 = waiting)
 *  downloaded     finished songs, newest first
 *  pathOf(id)     file path if saved, else null. Player uses this.
 *
 * A small foreground service keeps the process alive while the line is not empty.
 */
class DownloadManagerX private constructor(private val ctx: Context) :
    SQLiteOpenHelper(ctx, "downloads.db", null, 1) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val queue = Channel<Song>(Channel.UNLIMITED)
    private val paths = ConcurrentHashMap<String, String>()
    private val cancelled = ConcurrentHashMap.newKeySet<String>()
    private val extractor = AudioStreamExtractor(ctx)
    private val http = com.example.chord.playback.ChordHttp.download

    private val _active = MutableStateFlow<Map<String, Float>>(emptyMap())
    val active: StateFlow<Map<String, Float>> = _active.asStateFlow()

    private val _downloaded = MutableStateFlow<List<DownloadedSong>>(emptyList())
    val downloaded: StateFlow<List<DownloadedSong>> = _downloaded.asStateFlow()

    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 4)
    /** Short messages for a toast: "Download failed ...". */
    val events: SharedFlow<String> = _events.asSharedFlow()

    init {
        reload()
        scope.launch {
            for (song in queue) {
                if (song.videoId in cancelled) { cancelled.remove(song.videoId); drop(song.videoId); continue }
                run(song)
            }
        }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE dl(videoId TEXT PRIMARY KEY, title TEXT NOT NULL, artist TEXT NOT NULL, thumb TEXT,
               path TEXT NOT NULL, quality TEXT NOT NULL, bytes INTEGER NOT NULL, ts INTEGER NOT NULL)"""
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, o: Int, n: Int) = Unit

    // ---- read ------------------------------------------------------------------

    fun pathOf(videoId: String): String? = paths[videoId]?.takeIf { File(it).exists() }

    private fun reload() {
        val rows = readableDatabase.rawQuery(
            "SELECT videoId,title,artist,thumb,path,quality,bytes FROM dl ORDER BY ts DESC", null,
        ).use { c ->
            buildList {
                while (c.moveToNext()) add(
                    DownloadedSong(c.getString(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4), c.getString(5), c.getLong(6))
                )
            }
        }.filter { File(it.path).exists() }
        paths.clear()
        rows.forEach { paths[it.videoId] = it.path }
        _downloaded.value = rows
    }

    // ---- write -----------------------------------------------------------------

    fun enqueue(song: Song) {
        if (song.videoId.isBlank() || com.example.chord.data.local.LocalMusicRepository.isLocal(song.videoId)) return
        if (paths.containsKey(song.videoId) || _active.value.containsKey(song.videoId)) return
        cancelled.remove(song.videoId)
        _active.update { it + (song.videoId to -1f) }
        queue.trySend(song)
        DownloadService.start(ctx)
    }

    fun cancel(videoId: String) {
        cancelled.add(videoId)
    }

    fun remove(videoId: String) {
        paths.remove(videoId)?.let { File(it).delete() }
        writableDatabase.delete("dl", "videoId=?", arrayOf(videoId))
        reload()
    }

    private fun drop(videoId: String) = _active.update { it - videoId }

    // ---- worker ----------------------------------------------------------------

    private suspend fun run(song: Song) {
        val id = song.videoId
        val dir = File(ctx.filesDir, "downloads").apply { mkdirs() }
        val part = File(dir, "$id.part")
        try {
            _active.update { it + (id to 0f) }
            // Resolve with the full song metadata so a Monochrome FLAC match is
            // reused for the download instead of silently resolving YouTube Opus.
            val stream = extractor.extract(song, forDownload = true)
            val mime = stream.mimeType.orEmpty().substringBefore(';').lowercase()
            val ext = when (mime) {
                "audio/flac", "audio/x-flac" -> "flac"
                "audio/ogg", "application/ogg" -> "ogg"
                "audio/mpeg", "audio/mp3" -> "mp3"
                "audio/webm" -> "webm"
                "audio/mp4", "audio/aac" -> "m4a"
                else -> if (stream.url.substringBefore('?').endsWith(".flac", true)) "flac" else "m4a"
            }

            // A single streaming GET avoids fragile range-loop behaviour on relay
            // URLs that do not expose stable Content-Range metadata.
            val request = Request.Builder().url(stream.url).apply {
                stream.headers.forEach { (key, value) -> header(key, value) }
            }.build()
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) error("HTTP ${response.code}")
                val body = response.body ?: error("Empty response")
                val total = body.contentLength()
                FileOutputStream(part).use { out ->
                    body.byteStream().use { input ->
                        val buffer = ByteArray(64 * 1024)
                        var copied = 0L
                        while (true) {
                            if (id in cancelled) throw CancellationException("cancelled")
                            val count = input.read(buffer)
                            if (count < 0) break
                            if (count == 0) continue
                            out.write(buffer, 0, count)
                            copied += count
                            if (total > 0) {
                                _active.update { it + (id to (copied.toFloat() / total).coerceIn(0f, 1f)) }
                            }
                        }
                        out.fd.sync()
                    }
                }
            }

            // Do not save a response as FLAC merely because its metadata claimed it.
            if (ext == "flac") {
                val magic = part.inputStream().use { input -> ByteArray(4).also { bytes ->
                    var offset = 0
                    while (offset < bytes.size) {
                        val count = input.read(bytes, offset, bytes.size - offset)
                        if (count < 0) break
                        offset += count
                    }
                } }
                if (!magic.contentEquals("fLaC".toByteArray(Charsets.US_ASCII))) {
                    error("The selected source did not return a valid FLAC file.")
                }
            }

            val file = File(dir, "$id.$ext")
            file.delete()
            if (!part.renameTo(file)) error("Could not save file")
            val label = stream.quality
            writableDatabase.insertWithOnConflict(
                "dl", null,
                ContentValues().apply {
                    put("videoId", id); put("title", song.title); put("artist", song.details)
                    put("thumb", song.thumbnailUrl); put("path", file.absolutePath)
                    put("quality", label); put("bytes", file.length()); put("ts", System.currentTimeMillis())
                },
                SQLiteDatabase.CONFLICT_REPLACE,
            )
            reload()
            _events.tryEmit("Downloaded: ${song.title}")
        } catch (e: CancellationException) {
            part.delete()
            cancelled.remove(id)
        } catch (e: Exception) {
            Log.w("ChordDownload", "failed $id", e)
            part.delete()
            _events.tryEmit("Download failed: ${song.title}")
        } finally {
            drop(id)
        }
    }

    companion object {
        private const val CHUNK = 4L * 1024 * 1024
        @Volatile private var inst: DownloadManagerX? = null
        fun get(ctx: Context): DownloadManagerX =
            inst ?: synchronized(this) { inst ?: DownloadManagerX(ctx.applicationContext).also { inst = it } }
    }
}
