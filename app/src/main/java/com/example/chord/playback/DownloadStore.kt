package com.example.chord.playback

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.util.Log
import android.widget.Toast
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import com.example.chord.data.local.LocalMusicRepository
import com.example.chord.data.model.Song
import com.example.chord.data.repository.LyricsRepository
import com.example.chord.data.settings.AppSettings
import com.example.chord.data.settings.DownloadQuality
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import com.example.chord.playback.addon.AddonResolver
import com.example.chord.playback.addon.AddonStore
import com.example.chord.playback.addon.MonochromeResolver
import com.example.chord.data.history.HistoryStore
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

@androidx.annotation.OptIn(UnstableApi::class)
object DownloadStore {
    private const val PREFS = "chord_downloads"
    private const val KEY = "songs"
    private const val TAG = "ChordDownload"
    private const val BIN = "application/octet-stream"

    private const val UA =
        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

    private class Entry(
        val song: Song,
        val audio: String,
        val lyrics: String?
    )

    private lateinit var app: Context

    private val scope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private const val CHUNK = 1L * 1024 * 1024
    private const val WORKERS = 6
    private const val MAX_ATTEMPTS = 3
    private const val MIN_AUDIO_BYTES = 512L

    private class NoSpaceException(msg: String) : java.io.IOException(msg)

    /** Refuse early when the phone cannot hold the file. Keeps 50 MB free for the system. */
    private fun ensureSpace(bytes: Long) {
        if (bytes <= 0L) return
        val free = app.filesDir.usableSpace
        if (free < bytes + 50L * 1024 * 1024) throw NoSpaceException("Not enough storage")
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val gate = Semaphore(2)
    private val jobs = HashMap<String, Job>()

    private val cancelled =
        java.util.Collections.synchronizedSet(HashSet<String>())

    /** Quality picked for each queued song. Set when queued, so a later Settings change never hits it. */
    private val wanted = java.util.concurrent.ConcurrentHashMap<String, DownloadQuality>()

    @Volatile
    private var entries: List<Entry> = emptyList()

    private val _songs = MutableStateFlow<List<Song>>(emptyList())
    val songs: StateFlow<List<Song>> = _songs.asStateFlow()

    private val _progress = MutableStateFlow<Map<String, Float>>(emptyMap())
    val progress: StateFlow<Map<String, Float>> = _progress.asStateFlow()

    /** One short line per running download: "Waiting…", "Finding best quality…", "Downloading 42%", "Finishing…". */
    private val _status = MutableStateFlow<Map<String, String>>(emptyMap())
    val status: StateFlow<Map<String, String>> = _status.asStateFlow()

    /** Songs being saved now, newest last. Downloads tab shows them at the top. */
    private val _active = MutableStateFlow<List<Song>>(emptyList())
    val active: StateFlow<List<Song>> = _active.asStateFlow()

    private fun setStatus(id: String, text: String) {
        _status.update { if (it[id] == text) it else it + (id to text) }
    }

    fun init(context: Context) {
        if (::app.isInitialized) return

        app = context.applicationContext
        setEntries(load())

        scope.launch {
            File(app.filesDir, "audio_downloads").deleteRecursively()
            privateDir().listFiles { f -> f.name.endsWith(".tmp") }?.forEach { it.delete() }
            prune()
        }
    }

    fun isDownloaded(id: String) =
        entries.any { it.song.videoId == id }

    fun audioUriOf(id: String): Uri? {
        if (!::app.isInitialized) return null

        val value = entries.firstOrNull {
            it.song.videoId == id
        }?.audio ?: return null

        val uri = Uri.parse(value)

        return if (
            uri.scheme == "file" &&
            !File(uri.path.orEmpty()).exists()
        ) null else uri
    }

    suspend fun readLyrics(id: String): String? =
        withContext(Dispatchers.IO) {
            if (!::app.isInitialized) return@withContext null

            val value = entries.firstOrNull {
                it.song.videoId == id
            }?.lyrics ?: return@withContext null

            try {
                app.contentResolver.openInputStream(Uri.parse(value))
                    ?.use { it.readBytes().toString(Charsets.UTF_8) }
                    ?.takeIf { it.isNotBlank() }
            } catch (_: Exception) {
                null
            }
        }

    /** [quality] null = the default from Settings. */
    fun download(song: Song, quality: DownloadQuality? = null) {
        if (!::app.isInitialized || song.videoId.isBlank()) return
        if (LocalMusicRepository.isLocal(song.videoId)) return

        synchronized(jobs) {
            if (jobs.containsKey(song.videoId) || isDownloaded(song.videoId)) {
                return
            }

            cancelled.remove(song.videoId)
            wanted[song.videoId] = quality ?: AppSettings.downloadQuality

            _progress.update {
                it + (song.videoId to 0f)
            }
            setStatus(song.videoId, "Waiting…")
            _active.update { list -> list.filterNot { it.videoId == song.videoId } + song }

            jobs[song.videoId] = scope.launch {
                fetch(song)
            }
        }
        // Keeps the process alive while the app is in the background.
        com.example.chord.data.download.DownloadService.start(app)
    }

    fun downloadAll(list: List<Song>, quality: DownloadQuality? = null) =
        list.forEach { download(it, quality) }

    fun cancel(id: String) {
        cancelled.add(id)

        synchronized(jobs) {
            jobs[id]?.cancel()
        }
    }

    fun remove(song: Song) =
        removeAll(setOf(song.videoId))

    fun removeAll(ids: Set<String>) {
        if (!::app.isInitialized) return

        ids.forEach(::cancel)

        val gone = entries.filter {
            it.song.videoId in ids
        }

        update { list ->
            list.filterNot { it.song.videoId in ids }
        }

        scope.launch {
            gone.forEach {
                deleteUri(it.audio)
                it.lyrics?.let(::deleteUri)
            }
        }
    }

    fun moveAll() {
        val tree = AppSettings.downloadTree?.let(Uri::parse) ?: return
        if (!::app.isInitialized) return

        scope.launch {
            val todo = entries.filter {
                Uri.parse(it.audio).scheme == "file"
            }

            var ok = 0

            todo.forEach { entry ->
                try {
                    val source = File(
                        Uri.parse(entry.audio).path.orEmpty()
                    )

                    val base = fileBase(entry.song)

                    val mime = when (source.extension.lowercase()) {
                        "m4a" -> "audio/mp4"
                        "flac" -> "audio/flac"
                        "mp3" -> "audio/mpeg"
                        "webm" -> "audio/webm"
                        "mka" -> "audio/x-matroska"
                        "ogg", "opus" -> "audio/ogg"
                        "wav" -> "audio/wav"
                        else -> BIN
                    }

                    val audio = copyTo(
                        tree,
                        mime,
                        "$base.${source.extension}",
                        source
                    )

                    var lyrics = entry.lyrics

                    lyrics?.let(Uri::parse)
                        ?.takeIf { it.scheme == "file" }
                        ?.let { uri ->
                            val file = File(uri.path.orEmpty())

                            lyrics = copyTo(
                                tree,
                                BIN,
                                "$base.lrc",
                                file
                            ).toString()

                            file.delete()
                        }

                    source.delete()

                    update { list ->
                        list.map {
                            if (it.song.videoId == entry.song.videoId) {
                                Entry(it.song, audio.toString(), lyrics)
                            } else {
                                it
                            }
                        }
                    }

                    ok++
                } catch (e: Exception) {
                    Log.w(TAG, "Move failed: ${entry.song.videoId}", e)
                }
            }

            if (todo.isNotEmpty()) {
                toast(
                    if (ok == todo.size) {
                        "Moved $ok songs to your folder"
                    } else {
                        "Moved $ok of ${todo.size}. Check the folder."
                    }
                )
            }
        }
    }

    // ---------------------------------------------------------------------
    // Download
    // ---------------------------------------------------------------------

    private suspend fun fetch(song: Song) {
        val id = song.videoId
        var saved: Sink? = null

        try {
            gate.withPermit {
                if (id in cancelled) return@withPermit

                val sink = Sink(song)

                // Links expire and networks blip: try up to 3 times, each with a fresh link.
                var ok = false
                for (attempt in 1..MAX_ATTEMPTS) {
                    try {
                        copyAll(id, sink)
                        ok = id !in cancelled
                        break
                    } catch (e: CancellationException) {
                        sink.discard()
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "Download failed: $id (attempt $attempt)", e)
                        if (attempt == MAX_ATTEMPTS || id in cancelled || e is NoSpaceException) break
                        setStatus(id, "Retrying…")
                        delay(2_000L * attempt)
                    }
                }

                if (ok) {
                    // Bytes are in. Turn the temp file into the real file (remux, copy to your folder).
                    setStatus(id, "Finishing…")
                    val audio = try {
                        sink.finish()
                    } catch (e: CancellationException) {
                        sink.discard()
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "Finish failed: $id", e)
                        sink.discard()
                        toast("Couldn't save ${song.title}")
                        return@withPermit
                    }

                    // Song is usable NOW. Lyrics come after, so the row never sits at 100% waiting for them.
                    update { list ->
                        list.filterNot {
                            it.song.videoId == id
                        } + Entry(song, audio, null)
                    }
                    saved = sink
                } else {
                    sink.discard()

                    if (id !in cancelled) {
                        toast("Couldn't download ${song.title}")
                    }
                }
            }
        } finally {
            synchronized(jobs) {
                jobs.remove(id)
            }

            cancelled.remove(id)
            wanted.remove(id)

            _progress.update {
                it - id
            }
            _status.update { it - id }
            _active.update { list -> list.filterNot { it.videoId == id } }
        }

        // Outside the download slot: the next song can start while lyrics load.
        saved?.let { attachLyrics(song, it) }
    }

    private suspend fun attachLyrics(song: Song, sink: Sink) {
        val text = lyricsFor(song) ?: return
        if (!isDownloaded(song.videoId)) return   // removed meanwhile
        val uri = sink.sidecar(text) ?: return
        update { list ->
            list.map {
                if (it.song.videoId == song.videoId) Entry(it.song, it.audio, uri) else it
            }
        }
    }

    /**
     * Downloads a resolved stream using HTTP range requests only when the server proves
     * that byte ranges are supported. Every response is checked against the requested
     * range; incomplete or malformed responses are never accepted as a completed file.
     */
    private suspend fun copyAll(id: String, sink: Sink) {
        val song = sink.song
        setStatus(id, "Finding best quality…")
        val want = wanted[id] ?: AppSettings.downloadQuality

        val stream = withContext(Dispatchers.IO) {
            val artist = HistoryStore.cleanArtist(song.details)
            val addonOn = AddonStore.enabled().any { it.downloads }
            val monoOn = MonochromeResolver.isEnabled()

            fun hiRes(s: AudioStream?) = s != null &&
                    listOf("hi-res", "hires", "24-bit", "24 bit").any { it in s.quality.lowercase() }

            var lossless: AudioStream? = null
            if (want != DownloadQuality.Opus) {
                if (monoOn) {
                    repeat(2) { attempt ->
                        if (lossless == null && id !in cancelled) {
                            lossless = runCatching { MonochromeResolver.resolve(song) }.getOrNull()
                            if (lossless == null && attempt == 0) delay(1_200)
                        }
                    }
                }
                if ((lossless == null || (want == DownloadQuality.HiRes && !hiRes(lossless))) && addonOn) {
                    var fromAddon: AudioStream? = null
                    repeat(2) { attempt ->
                        if (fromAddon == null && id !in cancelled) {
                            fromAddon = AddonResolver.resolve(id, song.title, artist, true)
                            if (fromAddon == null && attempt == 0) delay(1_200)
                        }
                    }
                    if (lossless == null || hiRes(fromAddon)) lossless = fromAddon ?: lossless
                }
            }

            val chosen = lossless ?: AudioStreamExtractor(app).extract(id)
            when {
                lossless == null && want != DownloadQuality.Opus ->
                    toast("No lossless match for ${song.title}. Saved Opus.")
                lossless != null && want == DownloadQuality.HiRes && !hiRes(lossless) ->
                    toast("No Hi-Res for ${song.title}. Saved lossless.")
            }
            chosen
        }
        sink.hint = listOfNotNull(stream.mimeType, stream.quality).joinToString(" ")
        currentCoroutineContext().ensureActive()
        if (id in cancelled) throw CancellationException("cancelled")

        fun request(range: String? = null) = Request.Builder().url(stream.url).apply {
            stream.headers.forEach { (key, value) -> header(key, value) }
            if (stream.headers.keys.none { it.equals("User-Agent", true) }) header("User-Agent", UA)
            // Byte offsets must refer to the actual representation, not a transparently
            // compressed transfer representation.
            if (stream.headers.keys.none { it.equals("Accept-Encoding", true) }) header("Accept-Encoding", "identity")
            if (range != null) header("Range", range)
        }.build()

        val file = sink.temp
        file.parentFile?.mkdirs()
        if (file.exists() && !file.delete()) error("Could not reset temporary download")

        val lastProgress = AtomicInteger(-1)
        ensureSpace(total0(stream))
        fun report(done: Long, size: Long) {
            if (size <= 0L) {
                val mb = (done / (1024L * 1024L)).toInt()
                if (lastProgress.getAndSet(mb) != mb) setStatus(id, "Downloading $mb MB")
                return
            }
            val pct = ((done * 100L) / size).toInt().coerceIn(0, 99)
            if (lastProgress.getAndSet(pct) != pct) {
                _progress.update { it + (id to pct / 100f) }
                setStatus(id, "Downloading $pct%")
            }
        }

        // Probe one byte. A 206 alone is not enough: require the exact Content-Range
        // and one-byte body before enabling parallel writes.
        var total = stream.contentLength?.takeIf { it > 0L } ?: -1L
        var supportsRanges = false
        http.newCall(request("bytes=0-0")).execute().use { response ->
            when (response.code) {
                206 -> {
                    val match = CONTENT_RANGE.matchEntire(response.header("Content-Range").orEmpty())
                    val body = response.body ?: error("Empty range probe")
                    val probe = body.bytes()
                    if (match != null && match.groupValues[1].toLongOrNull() == 0L &&
                        match.groupValues[2].toLongOrNull() == 0L &&
                        probe.size == 1
                    ) {
                        val reportedTotal = match.groupValues[3].toLongOrNull() ?: -1L
                        if (reportedTotal > 0L) {
                            total = reportedTotal
                            supportsRanges = true
                        }
                    }
                }
                200 -> {
                    val length = response.body?.contentLength() ?: -1L
                    if (length > 0L) total = length
                }
                else -> if (!response.isSuccessful) error("HTTP ${response.code} while checking stream")
            }
        }

        ensureSpace(total)

        if (!supportsRanges || total <= 0L) {
            // Compatibility path for hosts that do not implement byte ranges.
            http.newCall(request()).execute().use { response ->
                if (!response.isSuccessful) error("HTTP ${response.code}")
                val body = response.body ?: error("Empty response")
                val expected = body.contentLength().takeIf { it > 0L } ?: total
                var received = 0L
                val buffer = ByteArray(128 * 1024)
                FileOutputStream(file).use { output ->
                    body.byteStream().use { input ->
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            if (id in cancelled) throw CancellationException("cancelled")
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                            received += count
                            report(received, expected)
                        }
                    }
                    output.fd.sync()
                }
                if (expected > 0L && received != expected) {
                    error("Incomplete download: received $received of $expected bytes")
                }
                if (received == 0L) error("Empty audio response")
                total = received
            }
            if (file.length() != total) error("Downloaded file size mismatch")
            _progress.update { it + (id to 1f) }
            setStatus(id, "Finishing…")
            return
        }

        val chunkSize = 4L * 1024L * 1024L
        val workerCount = minOf(4, ((total + chunkSize - 1L) / chunkSize).toInt()).coerceAtLeast(1)
        val completed = AtomicLong(0L)
        val chunks = Channel<LongRange>(Channel.UNLIMITED)
        var start = 0L
        while (start < total) {
            val end = minOf(start + chunkSize, total) - 1L
            chunks.trySend(start..end)
            start = end + 1L
        }
        chunks.close()

        RandomAccessFile(file, "rw").use { randomFile ->
            randomFile.setLength(total)
            val channel = randomFile.channel
            coroutineScope {
                repeat(workerCount) {
                    launch(Dispatchers.IO) {
                        val buffer = ByteArray(128 * 1024)
                        for (range in chunks) {
                            currentCoroutineContext().ensureActive()
                            if (id in cancelled) throw CancellationException("cancelled")
                            val expectedBytes = range.last - range.first + 1L
                            var succeeded = false
                            var lastError: Exception? = null

                            // Retry the whole chunk, not just a possibly ambiguous partial
                            // response. Each retry overwrites the same isolated byte range.
                            repeat(4) { attempt ->
                                if (!succeeded) {
                                    try {
                                        http.newCall(request("bytes=${range.first}-${range.last}")).execute().use { response ->
                                            if (response.code != 206) error("Range request returned HTTP ${response.code}")
                                            val match = CONTENT_RANGE.matchEntire(response.header("Content-Range").orEmpty())
                                                ?: error("Missing or invalid Content-Range")
                                            val actualStart = match.groupValues[1].toLongOrNull()
                                            val actualEnd = match.groupValues[2].toLongOrNull()
                                            val actualTotal = match.groupValues[3].toLongOrNull()
                                            if (actualStart != range.first || actualEnd != range.last || actualTotal != total) {
                                                error("Server returned an unexpected byte range")
                                            }
                                            val body = response.body ?: error("Empty range body")
                                            if (body.contentLength() >= 0L && body.contentLength() != expectedBytes) {
                                                error("Range body length mismatch")
                                            }
                                            body.byteStream().use { input ->
                                                var position = range.first
                                                var remaining = expectedBytes
                                                while (remaining > 0L) {
                                                    currentCoroutineContext().ensureActive()
                                                    if (id in cancelled) throw CancellationException("cancelled")
                                                    val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                                                    if (count < 0) error("Truncated range response")
                                                    val bytes = ByteBuffer.wrap(buffer, 0, count)
                                                    var writePosition = position
                                                    while (bytes.hasRemaining()) {
                                                        val written = channel.write(bytes, writePosition)
                                                        if (written <= 0) error("Could not write downloaded bytes")
                                                        writePosition += written
                                                    }
                                                    position += count
                                                    remaining -= count.toLong()
                                                }
                                                if (input.read() != -1) error("Range response exceeded requested length")
                                            }
                                        }
                                        succeeded = true
                                    } catch (cancel: CancellationException) {
                                        throw cancel
                                    } catch (e: Exception) {
                                        lastError = e
                                        if (attempt < 3) delay(350L * (attempt + 1))
                                    }
                                }
                            }
                            if (!succeeded) throw (lastError ?: java.io.IOException("Range download failed"))
                            val nowDone = completed.addAndGet(expectedBytes)
                            report(nowDone, total)
                        }
                    }
                }
            }
            channel.force(true)
        }

        if (completed.get() != total || file.length() != total) {
            error("Download integrity check failed: ${completed.get()} of $total bytes")
        }
        _progress.update { it + (id to 1f) }
        setStatus(id, "Finishing…")
    }

    private fun total0(stream: AudioStream): Long = stream.contentLength?.takeIf { it > 0L } ?: -1L

    private val CONTENT_RANGE = Regex("bytes (\\d+)-(\\d+)/(\\d+)")

    private fun fetchCover(song: Song): Pair<ByteArray, String>? = try {
        val url = song.thumbnailUrl?.replace(Regex("=w\\d+-h\\d+[^&?]*"), "=w544-h544")
        if (url.isNullOrBlank()) null
        else http.newCall(Request.Builder().url(url).build()).execute().use { r ->
            val bytes = r.body?.bytes()?.takeIf { r.isSuccessful && it.size in 1..3_000_000 }
            bytes?.let {
                val mime = when {
                    it.size > 3 && it[0] == 0x89.toByte() && it[1] == 'P'.code.toByte() -> "image/png"
                    it.size > 11 && it[8] == 'W'.code.toByte() && it[9] == 'E'.code.toByte() -> "image/webp"
                    else -> "image/jpeg"
                }
                it to mime
            }
        }
    } catch (_: Exception) {
        null
    }

    private suspend fun lyricsFor(song: Song): String? =
        try {
            withTimeoutOrNull(15_000L) {
                val list = LyricsRepository().getAllLyrics(
                    song.title,
                    song.details,
                    0L,
                    song.videoId
                )

                (
                        list.firstOrNull {
                            !it.syncedLyrics.isNullOrBlank()
                        } ?: list.firstOrNull()
                        )?.best
            }?.takeIf { it.isNotBlank() }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }

    // ---------------------------------------------------------------------
    // Output sink
    // ---------------------------------------------------------------------

    private class Sink(val song: Song) {
        private val tree = AppSettings.downloadTree?.let(Uri::parse)
        private val base = fileBase(song)

        val temp = File(privateDir(), "${song.videoId}.tmp")
        private val oggTemp = File(privateDir(), "${song.videoId}.ogg.tmp")
        private var document: Uri? = null

        /** What the stream said it was ("audio/flac FLAC · Tidal"). Used when the first bytes do not tell. */
        @Volatile var hint: String = ""

        /** Turns the temp file into the final file. Returns its uri. */
        fun finish(): String {
            val head = ByteArray(12)
            if (temp.length() < MIN_AUDIO_BYTES) error("Downloaded file is too small")
            temp.inputStream().use { it.read(head) }
            // An error page saved as audio would never play. Text/JSON/HTML starts with these bytes.
            val first = head[0].toInt().toChar()
            if (first == '<' || first == '{' || first == '[' || (head[0] == 'H'.code.toByte() && head[1] == 'T'.code.toByte() && head[2] == 'T'.code.toByte())) {
                error("Source returned a web page, not audio")
            }

            fun at(i: Int, text: String) =
                text.indices.all { head[i + it] == text[it].code.toByte() }

            val webm = head[0] == 0x1A.toByte() && head[1] == 0x45.toByte()
            val flac = at(0, "fLaC")
            val ogg = at(0, "OggS")
            val wav = at(0, "RIFF") && at(8, "WAVE")
            val mp3 = at(0, "ID3") ||
                    (head[0] == 0xFF.toByte() && (head[1].toInt() and 0xE0) == 0xE0)
            val mp4 = at(4, "ftyp")

            // Trust the bytes. If they are not clear, trust what the addon told us.
            val h = hint.lowercase()
            val hintFlac = !flac && !ogg && !wav && !mp3 && !mp4 && !webm && "flac" in h

            var src = temp
            var ext = when {
                webm -> "mka"; flac || hintFlac -> "flac"; ogg -> "ogg"; wav -> "wav"; mp3 -> "mp3"; else -> "m4a"
            }
            var mime = when {
                webm -> "audio/x-matroska"; flac || hintFlac -> "audio/flac"; ogg -> "audio/ogg"
                wav -> "audio/wav"; mp3 -> "audio/mpeg"; else -> "audio/mp4"
            }

            if (webm) {
                // Files app calls .webm a video with no thumbnail. Ogg Opus is audio + cover art.
                val cover = fetchCover(song)
                val artist = HistoryStore.cleanArtist(song.details)
                if (OggOpusRemux.run(temp, oggTemp, song.title, artist, cover?.first, cover?.second ?: "image/jpeg")) {
                    src = oggTemp; ext = "ogg"; mime = "audio/ogg"
                    temp.delete()
                }
            }

            if (ext == "flac") {
                // Keep the downloaded source intact until the tagged replacement has
                // been finalized successfully. A failed tag/rename must not destroy audio.
                val tagged = File(privateDir(), "${song.videoId}.tag.tmp")
                tagged.delete()
                val cover = fetchCover(song)
                val artist = HistoryStore.cleanArtist(song.details)
                if (FlacTagger.run(src, tagged, song.title, artist, cover?.first, cover?.second ?: "image/jpeg") &&
                    tagged.length() > 0L
                ) {
                    src = tagged
                } else {
                    tagged.delete()
                }
            }

            tree?.let { selected ->
                try {
                    val uri = copyTo(selected, mime, "$base.$ext", src)
                    document = uri
                    src.delete()
                    temp.delete()
                    oggTemp.delete()
                    File(privateDir(), "${song.videoId}.tag.tmp").delete()
                    return uri.toString()
                } catch (e: Exception) {
                    Log.w(TAG, "Folder unavailable; using Chord storage", e)
                    toast("Folder not available. Saved inside Chord.")
                }
            }

            val finalFile = File(privateDir(), "${song.videoId}.$ext")
            if (finalFile.exists() && !finalFile.delete()) error("Could not replace existing download")
            check(src.renameTo(finalFile)) { "Rename failed" }
            temp.delete()
            oggTemp.delete()
            File(privateDir(), "${song.videoId}.tag.tmp").delete()
            return Uri.fromFile(finalFile).toString()
        }

        fun sidecar(text: String): String? =
            try {
                if (document != null && tree != null) {
                    val uri = createIn(tree, BIN, "$base.lrc")
                    app.contentResolver.openOutputStream(uri, "w")!!.use { it.write(text.toByteArray()) }
                    uri.toString()
                } else {
                    val file = File(privateDir(), "${song.videoId}.lrc")
                    file.writeText(text)
                    Uri.fromFile(file).toString()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Lyrics not saved", e)
                null
            }

        fun discard() {
            temp.delete()
            oggTemp.delete()
            File(privateDir(), "${song.videoId}.tag.tmp").delete()
            document?.let { deleteUri(it.toString()) }
            document = null
        }
    }

    // ---------------------------------------------------------------------
    // File helpers
    // ---------------------------------------------------------------------

    private fun privateDir() =
        File(app.filesDir, "downloads").apply {
            mkdirs()
        }

    private fun createIn(
        tree: Uri,
        mime: String,
        name: String
    ): Uri {
        val parent = DocumentsContract
            .buildDocumentUriUsingTree(
                tree,
                DocumentsContract.getTreeDocumentId(tree)
            )

        return DocumentsContract.createDocument(
            app.contentResolver,
            parent,
            mime,
            name
        ) ?: error("Could not create file")
    }

    private fun copyTo(
        tree: Uri,
        mime: String,
        name: String,
        source: File
    ): Uri {
        val destination = createIn(tree, mime, name)

        try {
            app.contentResolver
                .openOutputStream(destination, "w")!!
                .use { output ->
                    source.inputStream().use {
                        it.copyTo(output)
                    }
                }
        } catch (e: Exception) {
            runCatching {
                DocumentsContract.deleteDocument(
                    app.contentResolver,
                    destination
                )
            }

            throw e
        }

        return destination
    }

    private fun deleteUri(value: String) {
        val uri = Uri.parse(value)

        try {
            if (uri.scheme == "file") {
                File(uri.path.orEmpty()).delete()
            } else {
                DocumentsContract.deleteDocument(
                    app.contentResolver,
                    uri
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Delete failed: $value", e)
        }
    }

    private fun prune() {
        val missing = entries
            .filter { !exists(Uri.parse(it.audio)) }
            .map { it.song.videoId }
            .toSet()

        if (missing.isNotEmpty()) {
            update { list ->
                list.filterNot {
                    it.song.videoId in missing
                }
            }
        }
    }

    private fun exists(uri: Uri): Boolean =
        if (uri.scheme == "file") {
            File(uri.path.orEmpty()).exists()
        } else {
            try {
                app.contentResolver.query(
                    uri,
                    arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID),
                    null,
                    null,
                    null
                )?.use {
                    it.moveToFirst()
                } ?: true
            } catch (_: Exception) {
                true
            }
        }

    private fun fileBase(song: Song): String =
        listOf(song.title, song.details)
            .filter { it.isNotBlank() }
            .joinToString(" - ")
            .replace(
                Regex("[\\\\/:*?\"<>|\\r\\n]"),
                "_"
            )
            .trim()
            .take(100)
            .ifBlank { song.videoId }

    private fun toast(text: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(
                app,
                text,
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    // ---------------------------------------------------------------------
    // Download index
    // ---------------------------------------------------------------------

    @Synchronized
    private fun update(transform: (List<Entry>) -> List<Entry>) {
        setEntries(transform(entries))
    }

    private fun setEntries(list: List<Entry>) {
        entries = list
        _songs.value = list.map { it.song }

        val array = JSONArray()

        list.forEach { entry ->
            array.put(
                JSONObject()
                    .put("t", entry.song.title)
                    .put("d", entry.song.details)
                    .put("v", entry.song.videoId)
                    .put("u", entry.song.thumbnailUrl ?: "")
                    .put("a", entry.audio)
                    .put("l", entry.lyrics ?: "")
            )
        }

        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY, array.toString())
            .apply()
    }

    private fun load(): List<Entry> =
        runCatching {
            val array = JSONArray(
                app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getString(KEY, "[]")
            )

            (0 until array.length()).mapNotNull { index ->
                val obj = array.getJSONObject(index)
                val audio = obj.optString("a")

                if (audio.isBlank()) {
                    null
                } else {
                    Entry(
                        Song(
                            obj.optString("t"),
                            obj.optString("d"),
                            obj.getString("v"),
                            obj.optString("u").ifBlank { null }
                        ),
                        audio,
                        obj.optString("l").ifBlank { null }
                    )
                }
            }
        }.getOrDefault(emptyList())
}