package com.example.chord.data.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.example.chord.playback.addon.AddonHttp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** A newer Chord release found on GitHub. */
data class AppRelease(
    val version: String,
    val notes: String,
    val apkUrl: String,
    val apkSize: Long,
)

sealed interface UpdatePhase {
    object Idle : UpdatePhase
    /** [progress] 0..1, or -1 when the server did not send a size. */
    data class Downloading(val progress: Float) : UpdatePhase
    data class Ready(val file: File) : UpdatePhase
    /** User must allow "Install unknown apps" for Chord, then tap Install again. */
    object NeedsPermission : UpdatePhase
    data class Failed(val message: String) : UpdatePhase
}

/**
 * In-app updates from GitHub Releases. No backend.
 *
 * Flow: [check] on launch -> [available] has a release -> dialog shows it ->
 * [startUpdate] downloads the APK -> [UpdatePhase.Ready] -> system installer opens.
 *
 * Release rules (or Android refuses the update):
 *   - tag is the version, e.g. v1.4.1 (must match versionName of the APK)
 *   - APK attached to the release as an asset
 *   - APK signed with the SAME keystore as the installed Chord
 *   - versionCode higher than the installed one
 *
 * Call [init] once in MainActivity.onCreate.
 */
object AppUpdater {
    // TODO set to the GitHub repo that hosts the Chord releases.
    private const val OWNER = "qqqweee"
    private const val REPO = "chord"

    private const val FILE = "chord_update"
    private const val KEY_SEEN = "seen_version"
    // Same cache folder the stats share uses, so the existing FileProvider path already covers it.
    private const val APK_DIR = "share"
    private const val APK_NAME = "chord-update.apk"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var appContext: Context? = null
    private var job: Job? = null

    private val availableState = MutableStateFlow<AppRelease?>(null)
    /** Release to show in the popup. null = no popup. */
    val available: StateFlow<AppRelease?> = availableState

    private val phaseState = MutableStateFlow<UpdatePhase>(UpdatePhase.Idle)
    val phase: StateFlow<UpdatePhase> = phaseState

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    private val prefs get() = appContext!!.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    // ---- check ----

    /** Ask GitHub for the latest release. Fails quietly: no network must never bother the user. */
    suspend fun check() = withContext(Dispatchers.IO) {
        val ctx = appContext ?: return@withContext
        runCatching {
            val body = AddonHttp.get(
                url = "https://api.github.com/repos/$OWNER/$REPO/releases/latest",
                headers = mapOf("Accept" to "application/vnd.github+json"),
            )
            val release = parse(JSONObject(body)) ?: return@runCatching
            if (!isNewer(release.version, installedVersion(ctx))) return@runCatching
            // Popup shows once per version. Tapping Later marks it seen.
            if (prefs.getString(KEY_SEEN, null) == release.version) return@runCatching
            availableState.value = release
        }.onFailure { android.util.Log.w("ChordUpdate", "check failed: ${it.message}") }
    }

    private fun parse(json: JSONObject): AppRelease? {
        if (json.optBoolean("draft") || json.optBoolean("prerelease")) return null
        val tag = json.optString("tag_name").takeIf { it.isNotBlank() } ?: return null
        val assets = json.optJSONArray("assets") ?: return null
        var apk: JSONObject? = null
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            if (a.optString("name").endsWith(".apk", ignoreCase = true)) { apk = a; break }
        }
        apk ?: return null
        return AppRelease(
            version = tag.removePrefix("v").removePrefix("V"),
            notes = cleanNotes(json.optString("body")),
            apkUrl = apk.getString("browser_download_url"),
            apkSize = apk.optLong("size", -1L),
        )
    }

    /** GitHub notes are Markdown. The popup is plain text, so strip the markup. */
    private fun cleanNotes(md: String): String =
        md.replace("\r", "")
            .lines()
            .joinToString("\n") { line ->
                line.trim()
                    .replace(Regex("^#{1,6}\\s*"), "")
                    .replace(Regex("^[-*+]\\s+"), "\u2022 ")
                    .replace("**", "").replace("__", "").replace("`", "")
                    .replace(Regex("\\[([^\\]]+)]\\([^)]*\\)"), "$1")
            }
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()

    /** 1.10.0 > 1.9.2. Compares numbers, not text. Ignores a suffix like -beta. */
    internal fun isNewer(remote: String, local: String): Boolean {
        fun parts(v: String) = v.substringBefore('-').substringBefore('+')
            .split('.').map { it.filter(Char::isDigit).toIntOrNull() ?: 0 }
        val r = parts(remote)
        val l = parts(local)
        for (i in 0 until maxOf(r.size, l.size)) {
            val a = r.getOrElse(i) { 0 }
            val b = l.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return false
    }

    private fun installedVersion(ctx: Context): String =
        runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }
            .getOrNull().orEmpty()

    // ---- dialog actions ----

    /** Later / close. Stops a running download and never shows this version's popup again. */
    fun dismiss() {
        val release = availableState.value
        job?.cancel()
        job = null
        runCatching { File(File(appContext!!.cacheDir, APK_DIR), APK_NAME).delete() }
        phaseState.value = UpdatePhase.Idle
        if (release != null) prefs.edit().putString(KEY_SEEN, release.version).apply()
        availableState.value = null
    }

    /** Update now. Downloads the APK, or installs it when it is already downloaded. */
    fun startUpdate() {
        val release = availableState.value ?: return
        when (val p = phaseState.value) {
            is UpdatePhase.Downloading -> return
            is UpdatePhase.Ready -> { install(p.file); return }
            is UpdatePhase.NeedsPermission -> {
                val file = File(File(appContext!!.cacheDir, APK_DIR), APK_NAME)
                if (file.exists()) { install(file); return }
            }
            else -> Unit
        }
        prefs.edit().putString(KEY_SEEN, release.version).apply()
        phaseState.value = UpdatePhase.Downloading(0f)
        job = scope.launch {
            try {
                val file = download(release)
                verify(file)
                phaseState.value = UpdatePhase.Ready(file)
                install(file)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.w("ChordUpdate", "update failed", e)
                phaseState.value = UpdatePhase.Failed(e.message ?: "Download failed")
            }
        }
    }

    // ---- download ----

    private suspend fun download(release: AppRelease): File {
        val ctx = appContext!!
        val dir = File(ctx.cacheDir, APK_DIR).apply { mkdirs() }
        val out = File(dir, APK_NAME)
        val part = File(dir, "$APK_NAME.part")
        part.delete()

        // GitHub answers with a redirect to its CDN. HttpURLConnection follows it (https to https).
        val conn = URL(release.apkUrl).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 20_000
            conn.readTimeout = 20_000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Android) Chord")
            val code = conn.responseCode
            if (code !in 200..299) throw IOException("Download failed (HTTP $code)")

            val total = conn.contentLengthLong.takeIf { it > 0 } ?: release.apkSize
            var done = 0L
            var lastPct = -1
            conn.inputStream.use { input ->
                part.outputStream().use { output ->
                    val buf = ByteArray(32 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        done += n
                        if (total > 0) {
                            val pct = (done * 100 / total).toInt()
                            // Only push a new value when the percent changes: fewer redraws.
                            if (pct != lastPct) {
                                lastPct = pct
                                phaseState.value = UpdatePhase.Downloading(pct / 100f)
                            }
                        } else {
                            phaseState.value = UpdatePhase.Downloading(-1f)
                        }
                    }
                }
            }
            if (total > 0 && done != total) throw IOException("Download incomplete")
        } catch (e: Exception) {
            part.delete()
            throw e
        } finally {
            conn.disconnect()
        }
        out.delete()
        if (!part.renameTo(out)) throw IOException("Could not save the update")
        return out
    }

    // ---- verify ----

    /**
     * Check the file BEFORE the installer opens. Android only says "App not installed"
     * when the signature differs. This gives a clear message instead.
     */
    @Suppress("DEPRECATION")
    private fun verify(file: File) {
        val ctx = appContext!!
        val pm = ctx.packageManager
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES
        else PackageManager.GET_SIGNATURES

        val archive = pm.getPackageArchiveInfo(file.absolutePath, flags)
            ?: throw IOException("Downloaded file is not a valid APK")
        if (archive.packageName != ctx.packageName) {
            throw IOException("Update is for a different app")
        }
        val installed = pm.getPackageInfo(ctx.packageName, flags)
        val newCode = if (Build.VERSION.SDK_INT >= 28) archive.longVersionCode else archive.versionCode.toLong()
        val oldCode = if (Build.VERSION.SDK_INT >= 28) installed.longVersionCode else installed.versionCode.toLong()
        if (newCode <= oldCode) throw IOException("Update is not newer than the installed app")

        fun certs(info: android.content.pm.PackageInfo) =
            if (Build.VERSION.SDK_INT >= 28) {
                info.signingInfo?.apkContentsSigners?.map { it.toByteArray().contentHashCode() }?.toSet()
            } else {
                info.signatures?.map { it.toByteArray().contentHashCode() }?.toSet()
            }.orEmpty()

        // pm.getPackageArchiveInfo does not always fill signatures on old Android. Skip then,
        // the system installer still enforces the signature.
        val newCerts = certs(archive)
        if (newCerts.isNotEmpty() && newCerts.intersect(certs(installed)).isEmpty()) {
            throw IOException("Update is signed with a different key, so it cannot replace Chord")
        }
    }

    // ---- install ----

    private fun install(file: File) {
        val ctx = appContext!!
        if (Build.VERSION.SDK_INT >= 26 && !ctx.packageManager.canRequestPackageInstalls()) {
            // One-time switch: "Allow from this source". User comes back and taps Install.
            phaseState.value = UpdatePhase.NeedsPermission
            val settings = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${ctx.packageName}"),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { ctx.startActivity(settings) }
            return
        }
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            ctx.startActivity(intent)
            phaseState.value = UpdatePhase.Ready(file)
        } catch (e: Exception) {
            phaseState.value = UpdatePhase.Failed("Could not open the installer")
        }
    }
}