package com.example.chord.playback.addon

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/** One installed addon. Order in the list = priority (top first). */
data class Addon(
    val url: String,
    val enabled: Boolean,
    val name: String,
    val version: String = "",
    val formats: List<String> = emptyList(),
    val lossless: Boolean = false,
    val downloads: Boolean = true,
    val searchPath: String = "/search",
    val streamPath: String = "/stream",
    val status: String = "",
    /** Extra request headers (API key, token). Read from the manifest "headers" field. */
    val headers: Map<String, String> = emptyMap(),
    /** Manifest says bitchordAuth: only the official BitChord app can use it. Other apps get 401. Chord skips it. */
    val officialOnly: Boolean = false,
    /** Value to send as ?quality=. First option of the manifest "quality" setting. Empty = LOSSLESS. */
    val quality: String = "",
) {
    val summary: String
        get() = listOfNotNull(
            version.takeIf { it.isNotBlank() }?.let { "v$it" },
            formats.joinToString("/").takeIf { it.isNotBlank() },
            if (lossless) "Lossless" else null,
        ).joinToString(" · ")
}

object AddonStore {
    private const val FILE = "chord_addons"
    private const val KEY = "list"

    private var prefs: SharedPreferences? = null

    private val _addons = MutableStateFlow<List<Addon>>(emptyList())
    val addons: StateFlow<List<Addon>> = _addons.asStateFlow()

    @Synchronized
    fun init(context: Context) {
        if (prefs != null) return

        val p = context.applicationContext
            .getSharedPreferences(FILE, Context.MODE_PRIVATE)

        prefs = p

        _addons.value = runCatching {
            decode(p.getString(KEY, "[]")!!)
        }.getOrDefault(emptyList())
    }

    fun enabled(): List<Addon> =
        _addons.value.filter { it.enabled && it.status.isBlank() && !it.officialOnly }

    fun sig(): String =
        enabled().joinToString("|") { it.url }.hashCode().toUInt().toString(16)

    fun toggle(url: String, on: Boolean) =
        edit { list ->
            list.map {
                if (it.url == url) it.copy(enabled = on) else it
            }
        }

    /** Addon answered 401 and we have no key for it. Stop asking, say why in Settings. */
    fun markOfficialOnly(url: String) =
        edit { list -> list.map { if (it.url == url) it.copy(officialOnly = true) else it } }

    fun remove(url: String) =
        edit { list -> list.filterNot { it.url == url } }

    fun move(url: String, delta: Int) =
        edit { list ->
            val i = list.indexOfFirst { it.url == url }
            val j = i + delta

            if (i < 0 || j !in list.indices) {
                list
            } else {
                list.toMutableList().also {
                    it.add(j, it.removeAt(i))
                }
            }
        }

    /** Reads the addon's manifest. Call from an IO thread. */
    fun test(input: String): Addon {
        val base = normalize(input)

        val manifest = try {
            JSONObject(AddonHttp.get(AddonHttp.join(base, "/manifest.json")))
        } catch (e: Exception) {
            throw IllegalStateException("No manifest at that URL")
        }

        val name = manifest.optString("name")
        check(name.isNotBlank()) { "No manifest at that URL" }

        val endpoints = manifest.optJSONObject("endpoints")

        fun truthy(key: String, default: Boolean): Boolean {
            if (!manifest.has(key)) return default
            return when (val v = manifest.opt(key)) {
                is Boolean -> v
                is Number -> v.toInt() != 0
                is String -> v.lowercase() !in setOf("0", "false", "no", "")
                else -> default
            }
        }

        // Protocol: the "quality" select. Until the listener picks, BitChord sends the FIRST option.
        val quality = manifest.optJSONArray("settings")?.let { arr ->
            (0 until arr.length()).asSequence().mapNotNull { arr.optJSONObject(it) }
                .firstOrNull { it.optString("key") == "quality" && it.optString("type") == "select" }
                ?.optJSONArray("options")?.optJSONObject(0)?.optString("value")
        }.orEmpty()
        val formatsJson = manifest.optJSONArray("formats")

        val formats = if (formatsJson == null) {
            emptyList()
        } else {
            List(formatsJson.length()) { formatsJson.optString(it) }
        }

        return Addon(
            url = base,
            enabled = true,
            name = name,
            version = manifest.optString("version"),
            formats = formats,
            lossless = manifest.optBoolean("lossless", false),
            // Protocol name is allowDownloads. Old name "downloads" still read.
            downloads = truthy("allowDownloads", truthy("downloads", true)),
            officialOnly = truthy("bitchordAuth", false),
            quality = quality,
            searchPath = endpoints?.optString("search")
                ?.ifBlank { null } ?: "/search",
            streamPath = endpoints?.optString("stream")
                ?.ifBlank { null } ?: "/stream",
            headers = manifest.optJSONObject("headers")?.let { h ->
                h.keys().asSequence().associateWith { h.optString(it) }
            } ?: emptyMap(),
        )
    }

    /** Adds an addon after validating its manifest. Call from an IO thread. */
    fun add(input: String) {
        val addon = test(input)

        edit { list ->
            if (list.any { it.url == addon.url }) list
            else list + addon
        }
    }

    /** Re-reads the manifest and updates the stored addon status. */
    fun refresh(url: String) {
        val old = _addons.value.firstOrNull { it.url == url } ?: return

        val updated = try {
            test(url).copy(enabled = old.enabled)
        } catch (e: Exception) {
            old.copy(status = e.message ?: "Can't reach addon")
        }

        edit { list ->
            list.map { if (it.url == url) updated else it }
        }
    }

    fun normalize(input: String): String {
        // Cut "/manifest.json" even when a ?key=... follows it.
        val raw = input.trim()
        var url = (raw.substringBefore('?').removeSuffix("/manifest.json").trimEnd('/') +
            raw.substringAfter('?', "").let { if (it.isEmpty()) "" else "?$it" })

        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = "https://$url"
        }

        return url
    }

    @Synchronized
    private fun edit(transform: (List<Addon>) -> List<Addon>) {
        val updated = transform(_addons.value)
        _addons.value = updated

        prefs?.edit()
            ?.putString(KEY, encode(updated))
            ?.apply()
    }

    private fun encode(list: List<Addon>): String =
        JSONArray().also { array ->
            list.forEach { addon ->
                array.put(
                    JSONObject()
                        .put("url", addon.url)
                        .put("enabled", addon.enabled)
                        .put("name", addon.name)
                        .put("version", addon.version)
                        .put("formats", JSONArray(addon.formats))
                        .put("lossless", addon.lossless)
                        .put("downloads", addon.downloads)
                        .put("search", addon.searchPath)
                        .put("stream", addon.streamPath)
                        .put("headers", JSONObject(addon.headers as Map<*, *>))
                        .put("officialOnly", addon.officialOnly)
                        .put("quality", addon.quality)
                )
            }
        }.toString()

    private fun decode(json: String): List<Addon> {
        val array = JSONArray(json)

        return List(array.length()) { index ->
            val obj = array.getJSONObject(index)
            val formats = obj.optJSONArray("formats")

            Addon(
                url = obj.getString("url"),
                enabled = obj.optBoolean("enabled", true),
                name = obj.optString("name"),
                version = obj.optString("version"),
                formats = if (formats == null) {
                    emptyList()
                } else {
                    List(formats.length()) { formats.optString(it) }
                },
                lossless = obj.optBoolean("lossless"),
                downloads = obj.optBoolean("downloads", true),
                searchPath = obj.optString("search", "/search"),
                streamPath = obj.optString("stream", "/stream"),
                headers = obj.optJSONObject("headers")?.let { h ->
                    h.keys().asSequence().associateWith { h.optString(it) }
                } ?: emptyMap(),
                officialOnly = obj.optBoolean("officialOnly", false),
                quality = obj.optString("quality", ""),
            )
        }
    }
}