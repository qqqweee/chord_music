package com.example.chord.playback

import android.util.Log
import okhttp3.Dns
import okhttp3.OkHttpClient
import org.json.JSONObject
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URLEncoder
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * The one OkHttp client of the app's audio path. Pool, dispatcher and DNS are shared:
 * the stream player, Monochrome API calls and addon calls reuse the same connections
 * (one TLS handshake per host, not one per request).
 *
 * Only the timeouts differ. [newBuilder] keeps the shared pool, so derived clients cost nothing.
 */
object ChordHttp {
    private const val TAG = "ChordHttp"

    /** Base client. Do not use directly for audio bytes: use [stream]. */
    val base: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .dns(FallbackDns)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)   // stale pooled socket: retry once on a fresh one
            .build()
    }

    /**
     * Audio bytes. Connect 10 s. Read 15 s: a dead socket is cut and resumed with a Range
     * request (cheap), instead of spinning for 30 s. No call timeout: a long song must not be cut.
     */
    val stream: OkHttpClient by lazy {
        base.newBuilder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    /** Small JSON calls: search, addon manifest, relay link. Fail fast, a retry is cheap. */
    val api: OkHttpClient by lazy {
        base.newBuilder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .build()
    }

    /** Downloads of whole files. Slower reads allowed, same pool. */
    val download: OkHttpClient by lazy {
        base.newBuilder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    /** System DNS first. If it fails, ask DoH (1.1.1.1 / 8.8.8.8 by IP, so no DNS needed). IPv4 first. */
    private object FallbackDns : Dns {
        private val cache = ConcurrentHashMap<String, List<InetAddress>>()
        private val bootstrap by lazy {
            OkHttpClient.Builder()
                .connectTimeout(4, TimeUnit.SECONDS)
                .readTimeout(4, TimeUnit.SECONDS)
                .build()
        }

        override fun lookup(hostname: String): List<InetAddress> {
            val found = try {
                Dns.SYSTEM.lookup(hostname).also { cache[hostname] = it }
            } catch (e: UnknownHostException) {
                doh(hostname)?.also { cache[hostname] = it } ?: cache[hostname] ?: throw e
            }
            return found.sortedBy { it is Inet6Address }
        }

        private fun doh(host: String): List<InetAddress>? {
            val name = URLEncoder.encode(host, "UTF-8")
            for (endpoint in listOf("https://1.1.1.1/dns-query?name=$name&type=A", "https://8.8.8.8/resolve?name=$name&type=A")) {
                try {
                    val req = okhttp3.Request.Builder().url(endpoint).header("Accept", "application/dns-json").build()
                    bootstrap.newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful) return@use
                        val answers = JSONObject(resp.body?.string().orEmpty()).optJSONArray("Answer") ?: return@use
                        val ips = (0 until answers.length()).mapNotNull { i ->
                            val a = answers.optJSONObject(i)
                            if (a != null && a.optInt("type") == 1) InetAddress.getByName(a.optString("data")) else null
                        }
                        if (ips.isNotEmpty()) return ips
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "DoH failed via $endpoint: ${e.message}")
                }
            }
            return null
        }
    }
}
