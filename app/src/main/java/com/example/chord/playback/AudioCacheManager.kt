package com.example.chord.playback

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import android.util.Log
import java.io.File

@UnstableApi
object AudioCacheManager {
    private var cache: Cache? = null
    private var db: StandaloneDatabaseProvider? = null

    @Synchronized
    fun getInstance(context: Context): Cache {
        if (cache == null) {
            // 1. Create a dedicated directory for audio chunks inside the app's cache folder
            val cacheDir = File(context.cacheDir, "audio_cache")

            // 2. Set a maximum size for the cache (1 GB: one 24-bit FLAC song can be 60 MB).
            // When full, ExoPlayer will automatically delete the oldest played songs.
            val cacheSize: Long = 1024L * 1024 * 1024
            val evictor = LeastRecentlyUsedCacheEvictor(cacheSize)

            // 3. Database to keep track of what is cached
            val databaseProvider = provider(context)

            // 4. Initialize the SimpleCache. A corrupt folder must not make playback impossible:
            // wipe it once and start empty.
            cache = try {
                SimpleCache(cacheDir, evictor, databaseProvider)
            } catch (e: Exception) {
                Log.w("ChordCache", "Cache folder unusable, resetting: ${e.message}")
                cacheDir.deleteRecursively()
                SimpleCache(cacheDir, LeastRecentlyUsedCacheEvictor(cacheSize), databaseProvider)
            }
        }
        return cache!!
    }

    /** The cache if it is already open, else null. Never opens it. */
    @Synchronized
    fun peek(): Cache? = cache

    private fun provider(context: Context) = db ?: StandaloneDatabaseProvider(context.applicationContext).also { db = it }

    @Synchronized
    fun release() {
        cache?.release()
        cache = null
    }
}