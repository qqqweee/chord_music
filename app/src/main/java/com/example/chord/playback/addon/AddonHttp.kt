package com.example.chord.playback.addon

import com.example.chord.playback.ChordHttp
import java.io.IOException

/** Plain HTTP GET helper used by addon manifest, search and stream requests. */
internal object AddonHttp {
    private const val MAX_BODY_BYTES = 4 * 1024 * 1024

    /**
     * One retry on a network drop or a 5xx. Before, one hiccup made the whole song
     * fall back to YouTube Opus, and that Opus then sat in the disk cache.
     */
    fun get(url: String, headers: Map<String, String> = emptyMap(), timeoutMs: Int = 20000, retries: Int = 1): String {
        var last: Exception? = null
        for (attempt in 0..retries) {
            try {
                return once(url, headers, timeoutMs)
            } catch (e: IOException) {
                last = e
            } catch (e: IllegalStateException) {
                // check() below throws this. Retry server errors only.
                if (e.message?.startsWith("HTTP 5") != true) throw e
                last = e
            }
            if (attempt < retries) Thread.sleep(400L * (attempt + 1))
        }
        throw last ?: IllegalStateException("Addon request failed")
    }

    private fun once(url: String, headers: Map<String, String>, timeoutMs: Int): String {
        // Shared pool and DNS (ChordHttp). Only the timeouts are this call's own.
        val client = ChordHttp.base.newBuilder()
            .connectTimeout(timeoutMs.toLong(), java.util.concurrent.TimeUnit.MILLISECONDS)
            .readTimeout(timeoutMs.toLong(), java.util.concurrent.TimeUnit.MILLISECONDS)
            .build()
        val builder = okhttp3.Request.Builder().url(url)
            .header("Accept", "application/json")
            .header("User-Agent", "Mozilla/5.0 (Android) Chord")

        // https://user:pass@host/... : OkHttp does not send this by itself.
        java.net.URL(url).userInfo?.takeIf { it.isNotBlank() }?.let {
            val token = android.util.Base64.encodeToString(
                java.net.URLDecoder.decode(it, "UTF-8").toByteArray(), android.util.Base64.NO_WRAP
            )
            builder.header("Authorization", "Basic $token")
        }
        // Addon's own headers (API key, token). Set last so they win.
        headers.forEach { (k, v) -> builder.header(k, v) }

        client.newCall(builder.build()).execute().use { resp ->
            if (!resp.isSuccessful) {
                // Body usually says why (bad key, expired token). Goes to Logcat.
                val why = runCatching { resp.peekBody(200).string() }.getOrDefault("").replace('\n', ' ')
                android.util.Log.w("ChordAddon", "HTTP ${resp.code} from ${url.substringBefore('?')} : $why")
                throw IllegalStateException("HTTP ${resp.code}")
            }
            // Cap the body: a broken or hostile addon must not fill memory.
            val source = resp.body?.source() ?: return ""
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(16 * 1024)
            val input = source.inputStream()
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                if (out.size() > MAX_BODY_BYTES) throw IOException("Addon response too large")
            }
            return out.toString(Charsets.UTF_8.name())
        }
    }

    /**
     * base + path + params. Keeps a query already in base, so "https://host?key=abc" still works:
     * join("https://host?key=abc", "/search", "q=x") -> "https://host/search?key=abc&q=x".
     */
    fun join(base: String, path: String, params: String = ""): String {
        val head = base.substringBefore('?')
        val query = base.substringAfter('?', "")
        val all = listOf(query, params).filter { it.isNotBlank() }.joinToString("&")
        return head + path + if (all.isNotEmpty()) "?$all" else ""
    }
}
