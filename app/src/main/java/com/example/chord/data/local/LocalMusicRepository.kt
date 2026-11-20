package com.example.chord.data.local

import android.content.ContentUris
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.util.Size
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.example.chord.data.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Music files stored on the phone. Songs get id "local:<mediaStoreId>" so the
 * rest of the app (queue, favourites, playlists, stats) treats them like any song.
 */
object LocalMusicRepository {
    const val PREFIX = "local:"

    /** Permission to ask for. Differs by Android version. */
    val permission: String
        get() = if (Build.VERSION.SDK_INT >= 33) android.Manifest.permission.READ_MEDIA_AUDIO
        else android.Manifest.permission.READ_EXTERNAL_STORAGE

    fun isLocal(videoId: String) = videoId.startsWith(PREFIX)

    fun uriOf(videoId: String): Uri =
        ContentUris.withAppendedId(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            videoId.removePrefix(PREFIX).toLongOrNull() ?: -1L,   // bad id = a file that is not found, not a crash
        )

    /**
     * Scans the phone. Cover art is pulled out of each album once and saved as
     * a small JPEG in app storage, so every screen (Home, player, notification)
     * can load it like a normal image URL. Next scans reuse the saved files.
     */
    suspend fun load(context: Context): List<Song> = withContext(Dispatchers.IO) {
        class Row(val id: Long, val title: String, val artist: String, val albumId: Long)
        val rows = ArrayList<Row>()
        val proj = arrayOf(
            MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.ALBUM_ID,
        )
        val where = "${MediaStore.Audio.Media.IS_MUSIC}!=0 AND ${MediaStore.Audio.Media.DURATION}>=30000"
        runCatching {
            context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, proj, where, null,
                "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC",
            )?.use { c ->
                while (c.moveToNext()) {
                    rows += Row(
                        c.getLong(0), c.getString(1).orEmpty().ifBlank { "Unknown" },
                        c.getString(2).orEmpty().takeUnless { it == "<unknown>" }.orEmpty(), c.getLong(3),
                    )
                }
            }
        }

        // One art lookup per album, 6 at a time.
        val gate = Semaphore(6)
        val art: Map<Long, String?> = coroutineScope {
            rows.groupBy { it.albumId }.map { (albumId, tracks) ->
                async { albumId to gate.withPermit { albumArt(context, albumId, uriOf(PREFIX + tracks.first().id)) } }
            }.awaitAll().toMap()
        }
        rows.map { Song(it.title, it.artist, PREFIX + it.id, art[it.albumId]) }
    }

    /** file:// URI of the album cover, or null if the files have none. Result is cached on disk. */
    private fun albumArt(context: Context, albumId: Long, track: Uri): String? {
        val dir = File(context.filesDir, "localart").apply { mkdirs() }
        val jpg = File(dir, "$albumId.jpg")
        val none = File(dir, "$albumId.none")
        if (jpg.length() > 0) return Uri.fromFile(jpg).toString()
        if (none.exists()) return null

        var bmp: Bitmap? = null
        if (Build.VERSION.SDK_INT >= 29) {
            bmp = runCatching { context.contentResolver.loadThumbnail(track, Size(800, 800), null) }.getOrNull()
        }
        if (bmp == null) {
            val r = MediaMetadataRetriever()
            bmp = runCatching {
                r.setDataSource(context, track)
                r.embeddedPicture?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
            }.getOrNull()
            runCatching { r.release() }
        }
        if (bmp == null) { runCatching { none.createNewFile() }; return null }
        runCatching { jpg.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) } }
        return if (jpg.length() > 0) Uri.fromFile(jpg).toString() else null
    }
}