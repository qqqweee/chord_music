package com.example.chord.data.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import com.example.chord.data.lastfm.LastFmVault
import com.example.chord.playback.SpatialMode
import com.example.chord.playback.SpatialParams
import com.example.chord.ui.components.GlassSpec
import com.example.chord.ui.haptics.HapticSettings
import com.example.chord.ui.theme.ChordTheme
import com.example.chord.ui.theme.ThemeMode

/** What the user wants when saving a song. Opus = small. Lossless = FLAC. HiRes = FLAC above CD (24-bit / over 48 kHz). */
enum class DownloadQuality(val label: String, val hint: String) {
    Opus("Opus", "Small files. YouTube Opus, about 160 kbps."),
    Lossless("Lossless", "FLAC, CD quality or better. Falls back to Opus if none found."),
    HiRes("Hi-Res", "Best FLAC, 24-bit / over 48 kHz when it exists. Falls back to lossless, then Opus."),
}

/**
 * All user settings in one place. To add a setting:
 *   1. add a KEY_ and a default
 *   2. load it in [init]
 *   3. add a setX() that saves it
 *   4. add a row in SettingsScreen
 *
 * Call [init] once in MainActivity.onCreate.
 */
object AppSettings {
    private const val FILE = "chord_settings"
    private const val KEY_THEME = "theme"
    private const val KEY_COVER_TINT = "cover_tint"
    private const val KEY_HAPTICS = "haptics"
    private const val KEY_DL_TREE = "download_tree"
    private const val KEY_DL_QUALITY = "download_quality"
    private const val KEY_DL_ASK = "download_quality_ask"
    private const val KEY_CROSSFADE = "crossfade_sec"
    private const val KEY_QUEUE = "queue_size"
    private const val KEY_SPATIAL_MODE = "spatial_mode"
    private const val KEY_SPATIAL_STRENGTH = "spatial_strength"
    private const val KEY_DEVICE_SPATIAL = "device_spatial"
    private const val KEY_DIAL_VIEW = "speed_dial_view"
    private const val KEY_GLASS = "glass_bars"
    private const val KEY_TRUE_GLASS = "glass_true"
    private const val KEY_G_LBLUR = "glass_liquid_blur"
    private const val KEY_G_RH = "glass_refract_h"
    private const val KEY_G_RA = "glass_refract_a"
    private const val KEY_G_BLUR = "glass_blur"
    private const val KEY_G_TINT = "glass_tint"
    private const val KEY_G_SHEEN = "glass_sheen"
    private const val KEY_G_RIM = "glass_rim"
    private const val KEY_G_DEPTH = "glass_depth"
    private const val KEY_LASTFM = "lastfm_key"
    private const val KEY_LASTFM_SEEN = "lastfm_prompt_seen"
    private const val KEY_FM_SIGNER = "lastfm_signer"
    private const val KEY_FM_SK = "lastfm_sk"
    private const val KEY_FM_USER = "lastfm_user"
    private const val KEY_FM_IMPORT = "lastfm_import_user"

    private var prefs: SharedPreferences? = null

    /** Folder (SAF tree uri) where downloads are saved as files. null = Chord private storage. */
    private var downloadTreeState by mutableStateOf<String?>(null)
    val downloadTree: String? get() = downloadTreeState

    /** Quality for new downloads. Default Hi-Res = best that exists (same as before). */
    private var downloadQualityState by mutableStateOf(DownloadQuality.HiRes)
    val downloadQuality: DownloadQuality get() = downloadQualityState

    /** On = every download tap asks which quality. Off = always use [downloadQuality]. */
    private var askQualityState by mutableStateOf(false)
    val askDownloadQuality: Boolean get() = askQualityState

    /** Songs kept queued after the playing one (radio). Smaller = faster start and lighter. */
    private var queueSizeState by mutableStateOf(20)
    val queueSize: Int get() = queueSizeState

    /** Crossfade length in seconds. 0 = off. */
    private var crossfadeState by mutableStateOf(0)
    val crossfadeSec: Int get() = crossfadeState

    /** Spatial sound (our own DSP). */
    private var spatialModeState by mutableStateOf(SpatialMode.Off)
    val spatialMode: SpatialMode get() = spatialModeState
    private var spatialStrengthState by mutableStateOf(60)
    /** 0..100 */
    val spatialStrength: Int get() = spatialStrengthState

    /** Let the phone's own spatializer (Dolby Atmos where it exists) work. */
    private var deviceSpatialState by mutableStateOf(true)
    val deviceSpatial: Boolean get() = deviceSpatialState

    /** Home speed dial layout: tiles, grid or row. */
    private var dialViewState by mutableStateOf("tiles")
    val speedDialView: String get() = dialViewState

    /** Glass (haze blur) on nav pill and mini player. Off = solid bars. */
    private var glassState by mutableStateOf(true)
    val glass: Boolean get() = glassState

    /** Glass look knobs. Slider ranges: blur 0..40, tint 0..0.4, sheen 0..0.4, rim 0..1, depth 0..20. */
    var glassBlur by mutableStateOf(20f)
        private set
    var glassTint by mutableStateOf(0.08f)
        private set
    var glassSheen by mutableStateOf(0.14f)
        private set
    var glassRim by mutableStateOf(0.5f)
        private set
    var glassDepth by mutableStateOf(8f)
        private set

    /** True liquid glass (lens refraction, API 33+). Off = hybrid Haze glass. Default off: cheaper. */
    private var trueGlassState by mutableStateOf(false)
    val trueGlass: Boolean get() = trueGlassState

    /** Liquid knobs. Slider ranges: blur 0..12, edge width 0..32, bend 0..60. */
    var glassLiquidBlur by mutableStateOf(2f)
        private set
    var glassRefractH by mutableStateOf(14f)
        private set
    var glassRefractA by mutableStateOf(28f)
        private set

    fun glassSpec() = GlassSpec(
        liquidBlur = glassLiquidBlur.dp,
        refractHeight = glassRefractH.dp,
        refractAmount = glassRefractA.dp,
        blur = glassBlur.dp,
        tintAlpha = glassTint,
        sheen = glassSheen,
        rim = glassRim,
        depth = glassDepth.dp,
    )

    /** Last.fm API key. Blank = Last.fm mixes are off. */
    private var lastFmKeyState by mutableStateOf("")
    val lastFmKey: String get() = lastFmKeyState
    val lastFmOn: Boolean get() = lastFmKeyState.isNotBlank()

    /** Home "add your Last.fm key" popup was shown once. Starts true so it never flashes before init. */
    private var lastFmSeenState by mutableStateOf(true)
    val lastFmPromptSeen: Boolean get() = lastFmSeenState

    /** Scrobbling. Signer server URL, session key (kept encrypted with the Android Keystore), account name. */
    private var fmSignerState by mutableStateOf("")
    /** Signer server address. Paste YOURS here once before you build: friends then need no setup. */
    const val DEFAULT_SIGNER = "https://chord-lastfm-signer.chordmusic.workers.dev/"
    val lastFmSigner: String get() = fmSignerState.ifBlank { DEFAULT_SIGNER }
    private var fmSessionState by mutableStateOf("")
    val lastFmSession: String get() = fmSessionState
    private var fmUserState by mutableStateOf("")
    val lastFmUser: String get() = fmUserState
    val scrobbleOn: Boolean get() = lastFmSigner.isNotBlank() && fmSessionState.isNotBlank()
    private var fmImportState by mutableStateOf("")
    /** Last.fm user name used for "Import listening history". */
    val lastFmImportUser: String get() = fmImportState

    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        prefs = p
        ChordTheme.mode = ThemeMode.values().firstOrNull { it.name == p.getString(KEY_THEME, null) } ?: ThemeMode.Dark
        ChordTheme.coverTint = p.getBoolean(KEY_COVER_TINT, false)
        HapticSettings.enabled = p.getBoolean(KEY_HAPTICS, true)
        downloadTreeState = p.getString(KEY_DL_TREE, null)
        downloadQualityState = DownloadQuality.values().firstOrNull { it.name == p.getString(KEY_DL_QUALITY, null) } ?: DownloadQuality.HiRes
        askQualityState = p.getBoolean(KEY_DL_ASK, false)
        crossfadeState = p.getInt(KEY_CROSSFADE, 0)
        queueSizeState = p.getInt(KEY_QUEUE, 20).coerceIn(10, 40)
        spatialModeState = SpatialMode.values().firstOrNull { it.name == p.getString(KEY_SPATIAL_MODE, null) } ?: SpatialMode.Off
        spatialStrengthState = p.getInt(KEY_SPATIAL_STRENGTH, 60)
        deviceSpatialState = p.getBoolean(KEY_DEVICE_SPATIAL, true)
        dialViewState = p.getString(KEY_DIAL_VIEW, "tiles") ?: "tiles"
        glassState = p.getBoolean(KEY_GLASS, true)
        trueGlassState = p.getBoolean(KEY_TRUE_GLASS, false)
        glassLiquidBlur = p.getFloat(KEY_G_LBLUR, 2f)
        glassRefractH = p.getFloat(KEY_G_RH, 14f)
        glassRefractA = p.getFloat(KEY_G_RA, 28f)
        glassBlur = p.getFloat(KEY_G_BLUR, 20f)
        glassTint = p.getFloat(KEY_G_TINT, 0.08f)
        glassSheen = p.getFloat(KEY_G_SHEEN, 0.14f)
        glassRim = p.getFloat(KEY_G_RIM, 0.5f)
        glassDepth = p.getFloat(KEY_G_DEPTH, 8f)
        lastFmKeyState = p.getString(KEY_LASTFM, "") ?: ""
        lastFmSeenState = p.getBoolean(KEY_LASTFM_SEEN, false)
        fmSignerState = p.getString(KEY_FM_SIGNER, "") ?: ""
        fmSessionState = LastFmVault.dec(p.getString(KEY_FM_SK, "") ?: "")
        fmUserState = p.getString(KEY_FM_USER, "") ?: ""
        fmImportState = p.getString(KEY_FM_IMPORT, "") ?: ""
        SpatialParams.mode = spatialModeState
        SpatialParams.strength = spatialStrengthState / 100f
    }

    fun setTheme(mode: ThemeMode) {
        ChordTheme.mode = mode
        prefs?.edit()?.putString(KEY_THEME, mode.name)?.apply()
    }

    fun setCoverTint(on: Boolean) {
        ChordTheme.coverTint = on
        prefs?.edit()?.putBoolean(KEY_COVER_TINT, on)?.apply()
    }

    fun setHaptics(on: Boolean) {
        HapticSettings.enabled = on
        prefs?.edit()?.putBoolean(KEY_HAPTICS, on)?.apply()
    }

    fun setQueueSize(n: Int) {
        queueSizeState = n.coerceIn(10, 40)
        prefs?.edit()?.putInt(KEY_QUEUE, queueSizeState)?.apply()
    }

    fun setCrossfade(sec: Int) {
        crossfadeState = sec
        prefs?.edit()?.putInt(KEY_CROSSFADE, sec)?.apply()
    }

    fun setSpatialMode(m: SpatialMode) {
        spatialModeState = m
        SpatialParams.mode = m
        prefs?.edit()?.putString(KEY_SPATIAL_MODE, m.name)?.apply()
    }

    fun setSpatialStrength(percent: Int) {
        spatialStrengthState = percent
        SpatialParams.strength = percent / 100f
        prefs?.edit()?.putInt(KEY_SPATIAL_STRENGTH, percent)?.apply()
    }

    fun setDeviceSpatial(on: Boolean) {
        deviceSpatialState = on
        prefs?.edit()?.putBoolean(KEY_DEVICE_SPATIAL, on)?.apply()
    }

    fun setLastFmKey(key: String) {
        lastFmKeyState = key.trim()
        prefs?.edit()?.putString(KEY_LASTFM, key.trim())?.apply()
    }

    fun setLastFmPromptSeen() {
        lastFmSeenState = true
        prefs?.edit()?.putBoolean(KEY_LASTFM_SEEN, true)?.apply()
    }

    fun setLastFmSigner(url: String) {
        fmSignerState = url.trim()
        prefs?.edit()?.putString(KEY_FM_SIGNER, url.trim())?.apply()
    }

    /** Blank [sk] = signed out. */
    fun setLastFmSession(sk: String, user: String) {
        fmSessionState = sk
        fmUserState = user
        prefs?.edit()?.putString(KEY_FM_SK, if (sk.isBlank()) "" else LastFmVault.enc(sk))?.putString(KEY_FM_USER, user)?.apply()
    }

    fun setLastFmImportUser(name: String) {
        fmImportState = name.trim()
        prefs?.edit()?.putString(KEY_FM_IMPORT, name.trim())?.apply()
    }

    fun setGlass(on: Boolean) {
        glassState = on
        prefs?.edit()?.putBoolean(KEY_GLASS, on)?.apply()
    }

    fun setTrueGlass(on: Boolean) {
        trueGlassState = on
        prefs?.edit()?.putBoolean(KEY_TRUE_GLASS, on)?.apply()
    }

    fun setLiquidLook(
        blur: Float = glassLiquidBlur,
        edge: Float = glassRefractH,
        bend: Float = glassRefractA,
    ) {
        glassLiquidBlur = blur
        glassRefractH = edge
        glassRefractA = bend
        prefs?.edit()
            ?.putFloat(KEY_G_LBLUR, blur)
            ?.putFloat(KEY_G_RH, edge)
            ?.putFloat(KEY_G_RA, bend)
            ?.apply()
    }

    /** Change any glass knob. Others keep their value. */
    fun setGlassLook(
        blur: Float = glassBlur,
        tint: Float = glassTint,
        sheen: Float = glassSheen,
        rim: Float = glassRim,
        depth: Float = glassDepth,
    ) {
        glassBlur = blur
        glassTint = tint
        glassSheen = sheen
        glassRim = rim
        glassDepth = depth
        prefs?.edit()
            ?.putFloat(KEY_G_BLUR, blur)
            ?.putFloat(KEY_G_TINT, tint)
            ?.putFloat(KEY_G_SHEEN, sheen)
            ?.putFloat(KEY_G_RIM, rim)
            ?.putFloat(KEY_G_DEPTH, depth)
            ?.apply()
    }

    /** Back to default look. */
    fun resetGlassLook() {
        setGlassLook(20f, 0.08f, 0.14f, 0.5f, 8f)
        setLiquidLook(2f, 14f, 28f)
    }

    fun setSpeedDialView(v: String) {
        dialViewState = v
        prefs?.edit()?.putString(KEY_DIAL_VIEW, v)?.apply()
    }

    fun setDownloadQuality(q: DownloadQuality) {
        downloadQualityState = q
        prefs?.edit()?.putString(KEY_DL_QUALITY, q.name)?.apply()
    }

    fun setAskDownloadQuality(on: Boolean) {
        askQualityState = on
        prefs?.edit()?.putBoolean(KEY_DL_ASK, on)?.apply()
    }

    fun setDownloadTree(uri: String?) {
        downloadTreeState = uri
        prefs?.edit()?.apply { if (uri == null) remove(KEY_DL_TREE) else putString(KEY_DL_TREE, uri) }?.apply()
    }
}