package com.example.chord.data.lastfm

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.os.SystemClock
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.example.chord.data.history.HistoryStore
import com.example.chord.data.settings.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Scrobbles what the Media3 player plays. Follows Last.fm's rules:
 *  - now playing is sent when a track starts;
 *  - a track counts after it was PLAYED for half its length or 4 minutes (whichever is first), and only if it is over 30 s;
 *  - the timestamp is when the track started; seeking and pausing do not count as play time;
 *  - batches of up to 50, entries older than 14 days are dropped (Last.fm rejects them).
 * Every scrobble goes to a file first, then is sent. Offline, dead session or app killed: it stays in the file and goes out later.
 */
object Scrobbler {
    private const val FILE = "scrobble_queue.json"
    private const val MAX_AGE_SEC = 14 * 86_400L
    private const val MAX_QUEUE = 3_000
    /** Last.fm codes that mean the session is dead: auth failed, invalid session, bad token. */
    private val SESSION = setOf(4, 9, 14, 15)
    /** Last.fm codes that mean "try again later": operation failed, service offline, temp error, rate limit. */
    private val RETRY = setOf(8, 11, 16, 29)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private var ctx: Context? = null
    private var flushJob: Job? = null
    @Volatile private var waiting = false
    private var netCb: ConnectivityManager.NetworkCallback? = null

    private val _pending = MutableStateFlow(0)
    /** Scrobbles waiting to be sent. */
    val pending: StateFlow<Int> = _pending.asStateFlow()
    private val _expired = MutableStateFlow(false)
    /** Last.fm refused the session. Scrobbles keep queuing until the user signs in again. */
    val expired: StateFlow<Boolean> = _expired.asStateFlow()

    fun init(c: Context) {
        if (ctx != null) return
        ctx = c.applicationContext
        scope.launch { lock.withLock { _pending.value = load().size } }
    }

    /** Called once by PlaybackService. Tracks [player] until [svcScope] ends. */
    fun attach(c: Context, player: Player, svcScope: CoroutineScope) {
        init(c)
        watchNetwork()
        flush()
        svcScope.launch { track(player) }
    }

    fun detach() {
        val c = ctx ?: return
        netCb?.let { runCatching { c.getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(it) } }
        netCb = null
    }

    /** After a fresh sign-in. */
    fun signedIn() { _expired.value = false; flush() }

    /** Sign out: forget the session and drop what was queued for that account. */
    fun signOut() {
        AppSettings.setLastFmSession("", "")
        _expired.value = false
        scope.launch { lock.withLock { save(emptyList()) } }
    }

    // ---------------- tracking ----------------

    private fun wanted() = AppSettings.scrobbleOn || _expired.value

    private suspend fun track(player: Player) {
        var gen = 0
        val l = object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) { gen++ }
        }
        player.addListener(l)
        try {
            var seen = -1
            var id: String? = null
            var artist = ""; var title = ""
            var startTs = 0L; var played = 0L
            var nowSent = false; var done = false
            var last = SystemClock.elapsedRealtime()
            while (true) {
                delay(1_000)
                val now = SystemClock.elapsedRealtime()
                val dt = (now - last).coerceIn(0L, 2_500L); last = now
                val item = player.currentMediaItem
                if (gen != seen || item?.mediaId != id) {              // new play (also a repeat of the same song)
                    seen = gen
                    id = item?.mediaId
                    val raw = item?.mediaMetadata?.artist?.toString().orEmpty()
                    artist = HistoryStore.cleanArtist(raw).ifBlank { raw }.trim()
                    title = item?.mediaMetadata?.title?.toString().orEmpty().trim()
                    if (artist.isBlank() || title.isBlank() || title == "Unknown") id = null
                    startTs = 0L; played = 0L; nowSent = false; done = false
                }
                if (id == null || !player.isPlaying || !wanted()) continue
                if (startTs == 0L) startTs = System.currentTimeMillis() / 1000
                played += dt
                val d = player.duration
                val sec = if (d > 0) (d / 1000).toInt() else 0
                if (!nowSent && played >= 2_000) { nowSent = true; nowPlaying(artist, title, sec) }
                val need = when {
                    d in 1L..30_000L -> Long.MAX_VALUE                    // too short to count
                    d > 0 -> minOf(d / 2, 240_000L)
                    else -> 240_000L
                }
                if (!done && played >= need) { done = true; enqueue(LastFmApi.Scrobble(artist, title, startTs, sec)) }
            }
        } finally {
            player.removeListener(l)
        }
    }

    private fun nowPlaying(artist: String, title: String, sec: Int) {
        val signer = AppSettings.lastFmSigner; val sk = AppSettings.lastFmSession
        if (signer.isBlank() || sk.isBlank()) return
        scope.launch {
            runCatching { LastFmApi.nowPlaying(signer, sk, artist, title, sec) }
                .onFailure { if (it is LastFmApi.ApiError && it.code in SESSION) expire() }
        }
    }

    // ---------------- queue (file) ----------------

    private fun file() = ctx?.let { File(it.filesDir, FILE) }

    private fun load(): MutableList<LastFmApi.Scrobble> {
        val f = file()?.takeIf { it.exists() } ?: return mutableListOf()
        return runCatching {
            val a = JSONArray(f.readText())
            (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                LastFmApi.Scrobble(o.getString("a"), o.getString("t"), o.getLong("ts"), o.optInt("d"))
            }.toMutableList()
        }.getOrDefault(mutableListOf())
    }

    private fun save(list: List<LastFmApi.Scrobble>) {
        val f = file() ?: return
        runCatching {
            val a = JSONArray()
            list.forEach { a.put(JSONObject().put("a", it.artist).put("t", it.title).put("ts", it.ts).put("d", it.duration)) }
            val tmp = File(f.parentFile, "$FILE.tmp")
            tmp.writeText(a.toString())
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        }
        _pending.value = list.size
    }

    private fun enqueue(s: LastFmApi.Scrobble) {
        scope.launch {
            lock.withLock {
                val cur = load()
                // Same song at the same start second is the same play.
                if (cur.none { it.ts == s.ts && it.title == s.title && it.artist == s.artist }) {
                    cur += s
                    save(if (cur.size > MAX_QUEUE) cur.takeLast(MAX_QUEUE) else cur)
                }
            }
            flush()
        }
    }

    private suspend fun remove(done: List<LastFmApi.Scrobble>) = lock.withLock { save(load().filterNot { it in done }) }

    // ---------------- sending ----------------

    /** Send what is queued. Safe to call any time; a running send is not doubled. */
    fun flush() {
        flushJob?.let { if (it.isActive) { if (waiting) it.cancel() else return } }
        flushJob = scope.launch { drain() }
    }

    private suspend fun drain() {
        var wait = 30_000L
        while (true) {
            val signer = AppSettings.lastFmSigner; val sk = AppSettings.lastFmSession
            if (signer.isBlank() || sk.isBlank()) return
            val now = System.currentTimeMillis() / 1000
            val batch = lock.withLock {
                val all = load()
                val fresh = all.filter { now - it.ts <= MAX_AGE_SEC }
                if (fresh.size != all.size) save(fresh)
                fresh.take(50)
            }
            if (batch.isEmpty()) return
            val err = runCatching { LastFmApi.scrobble(signer, sk, batch) }.exceptionOrNull()
            when {
                err == null -> { remove(batch); wait = 30_000L }
                err is LastFmApi.ApiError && err.code in SESSION -> { expire(); return }
                err is LastFmApi.ApiError && err.code == 6 -> sendOneByOne(signer, sk, batch)   // a bad entry in the batch
                err is LastFmApi.ApiError && err.code !in RETRY -> return                        // key or signer problem: keep the queue
                else -> {                                                                         // offline or Last.fm busy
                    waiting = true
                    try { delay(wait) } finally { waiting = false }
                    wait = minOf(wait * 2, 600_000L)
                }
            }
        }
    }

    private suspend fun sendOneByOne(signer: String, sk: String, batch: List<LastFmApi.Scrobble>) {
        for (s in batch) {
            val e = runCatching { LastFmApi.scrobble(signer, sk, listOf(s)) }.exceptionOrNull()
            if (e == null || (e is LastFmApi.ApiError && e.code == 6)) { remove(listOf(s)); continue }
            waiting = true                                   // network or Last.fm trouble: wait, then the caller loops again
            try { delay(30_000L) } finally { waiting = false }
            return
        }
    }

    private fun expire() {
        _expired.value = true
        AppSettings.setLastFmSession("", AppSettings.lastFmUser)
    }

    private fun watchNetwork() {
        val c = ctx ?: return
        if (netCb != null) return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { flush() }
        }
        runCatching { c.getSystemService(ConnectivityManager::class.java)?.registerDefaultNetworkCallback(cb); netCb = cb }
    }
}

/** Keeps the session key encrypted with a key that never leaves the Android Keystore. */
object LastFmVault {
    private const val ALIAS = "chord_lastfm"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        g.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return g.generateKey()
    }

    fun enc(plain: String): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        return Base64.encodeToString(c.iv + c.doFinal(plain.toByteArray()), Base64.NO_WRAP)
    }

    /** Blank when it can't be read (new phone, reinstall). Then the user signs in again. */
    fun dec(stored: String): String = runCatching {
        if (stored.isBlank()) return ""
        val b = Base64.decode(stored, Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, b, 0, 12)) }
        String(c.doFinal(b, 12, b.size - 12))
    }.getOrDefault("")
}
