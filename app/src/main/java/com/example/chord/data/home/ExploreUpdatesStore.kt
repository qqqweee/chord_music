package com.example.chord.data.home

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

/** Local, in-app change log for content returned by YouTube Music Explore. */
data class ExploreUpdate(
    val id: String,
    val section: String,
    val title: String,
    val subtitle: String,
    val thumbnailUrl: String?,
    val browseId: String?,
    val videoId: String?,
    val type: HomeItemType,
    val detectedAt: Long,
    val read: Boolean,
)

class ExploreUpdatesStore private constructor(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("explore_updates", Context.MODE_PRIVATE)
    private val mutable = MutableStateFlow(readAll())
    val updates: StateFlow<List<ExploreUpdate>> = mutable

    fun shouldCheck(maxAgeMs: Long = 30 * 60_000L): Boolean =
        System.currentTimeMillis() - prefs.getLong("last_checked", 0L) >= maxAgeMs

    @Synchronized
    fun compareAndSave(shelves: List<HomeShelf>) {
        val old = prefs.getStringSet("snapshot", emptySet()).orEmpty()
        val current = shelves.flatMap { shelf ->
            shelf.items.mapNotNull { item ->
                val key = item.videoId ?: item.browseId ?: return@mapNotNull null
                key to (shelf.title to item)
            }
        }
        // First successful fetch establishes a baseline; it should not notify every item.
        if (prefs.getBoolean("has_snapshot", false)) {
            val now = System.currentTimeMillis()
            val additions = current.filter { it.first !in old }
                .take(30)
                .map { (key, pair) ->
                    val (section, item) = pair
                    ExploreUpdate(
                        id = key,
                        section = section.ifBlank { "Explore" },
                        title = item.title,
                        subtitle = item.subtitle,
                        thumbnailUrl = item.thumbnailUrl,
                        browseId = item.browseId,
                        videoId = item.videoId,
                        type = item.type,
                        detectedAt = now,
                        read = false,
                    )
                }
            if (additions.isNotEmpty()) write((additions + mutable.value).distinctBy { it.id }.take(50))
        }
        prefs.edit().putStringSet("snapshot", current.map { it.first }.toSet()).putBoolean("has_snapshot", true)
            .putLong("last_checked", System.currentTimeMillis()).apply()
    }

    @Synchronized
    fun markAllRead() {
        write(mutable.value.map { it.copy(read = true) })
    }

    private fun write(items: List<ExploreUpdate>) {
        val json = JSONArray()
        items.forEach { u ->
            json.put(JSONObject().put("id", u.id).put("section", u.section).put("title", u.title)
                .put("subtitle", u.subtitle).put("thumbnail", u.thumbnailUrl)
                .put("browse", u.browseId).put("video", u.videoId).put("type", u.type.name)
                .put("time", u.detectedAt).put("read", u.read))
        }
        prefs.edit().putString("updates", json.toString()).apply()
        mutable.value = items
    }

    private fun readAll(): List<ExploreUpdate> = runCatching {
        val json = JSONArray(prefs.getString("updates", "[]"))
        (0 until json.length()).map { i ->
            val o = json.getJSONObject(i)
            ExploreUpdate(o.getString("id"), o.optString("section"), o.optString("title"),
                o.optString("subtitle"), o.optString("thumbnail").takeIf { it.isNotBlank() },
                o.optString("browse").takeIf { it.isNotBlank() }, o.optString("video").takeIf { it.isNotBlank() },
                runCatching { HomeItemType.valueOf(o.optString("type")) }.getOrDefault(HomeItemType.Other), o.optLong("time"), o.optBoolean("read"))
        }
    }.getOrDefault(emptyList())

    companion object {
        @Volatile private var instance: ExploreUpdatesStore? = null
        fun get(context: Context): ExploreUpdatesStore = instance ?: synchronized(this) {
            instance ?: ExploreUpdatesStore(context).also { instance = it }
        }
    }
}
