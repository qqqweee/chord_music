package com.example.chord.playback

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import java.io.File

@UnstableApi
object AudioCacheManager {
    private var cache: Cache? = null

    @Synchronized
    fun getInstance(context: Context): Cache {
        if (cache == null) {
            // 1. Create a dedicated directory for audio chunks inside the app's cache folder
            val cacheDir = File(context.cacheDir, "audio_cache")

            // 2. Set a maximum size for the cache (e.g., 200 MB).
            // When full, ExoPlayer will automatically delete the oldest played songs.
            val cacheSize: Long = 200 * 1024 * 1024
            val evictor = LeastRecentlyUsedCacheEvictor(cacheSize)

            // 3. Database to keep track of what is cached
            val databaseProvider = StandaloneDatabaseProvider(context)

            // 4. Initialize the SimpleCache
            cache = SimpleCache(cacheDir, evictor, databaseProvider)
        }
        return cache!!
    }

    @Synchronized
    fun release() {
        cache?.release()
        cache = null
    }
}