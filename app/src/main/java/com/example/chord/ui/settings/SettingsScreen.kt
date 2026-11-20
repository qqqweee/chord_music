package com.example.chord.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.chord.data.auth.AuthStore
import com.example.chord.data.settings.AppSettings
import com.example.chord.playback.DownloadStore
import com.example.chord.playback.SpatialMode
import com.example.chord.ui.components.pressScale
import com.example.chord.ui.haptics.HapticSettings
import com.example.chord.ui.theme.ChordTheme
import com.example.chord.ui.theme.ChordType
import com.example.chord.ui.theme.ThemeMode

/**
 * Settings page. Sections stack in a scroll column.
 * Add a new setting: put a row from SettingsComponents.kt in a section here,
 * and a saver in AppSettings.kt.
 */
@Composable
fun SettingsScreen(onBack: () -> Unit, onLogin: () -> Unit, modifier: Modifier = Modifier) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val signedIn by AuthStore.signedIn.collectAsState()
    val version = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty()
    }

    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
            AppSettings.setDownloadTree(uri.toString())
            DownloadStore.moveAll()
        }
    }
    val spatialSupport = remember { com.example.chord.playback.DeviceSpatial.support(context) }
    val tree = AppSettings.downloadTree
    val folderName = remember(tree) { tree?.let { Uri.parse(it).lastPathSegment?.substringAfter(':')?.ifBlank { null } ?: "Selected folder" } }

    Column(modifier.fillMaxSize().background(ChordTheme.bg).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(42.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.10f))
                    .pressScale(pressedScale = 0.9f, onClick = onBack),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = Color.White) }
            Text("Settings", style = ChordType.Display, color = Color.White, modifier = Modifier.padding(start = 14.dp))
        }

        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            SettingsSection("Appearance") {
                ChoiceRow(
                    title = "Theme",
                    options = ThemeMode.values().toList(),
                    selected = ChordTheme.mode,
                    label = { it.label },
                    onSelect = AppSettings::setTheme,
                )
                SwitchRow(
                    "Cover colour tint", "Tint the top of Home with your top pick's cover",
                    ChordTheme.coverTint, AppSettings::setCoverTint,
                )
                SwitchRow(
                    "Glass bars", "Blur behind the nav bar and mini player. Off = solid, lighter on battery",
                    AppSettings.glass, AppSettings::setGlass,
                )
                if (AppSettings.glass) {
                    val canLiquid = android.os.Build.VERSION.SDK_INT >= 33
                    if (canLiquid) {
                        SwitchRow(
                            "True liquid glass",
                            "Real light bend at the edges. Looks best, uses more battery. Off = lighter Haze glass",
                            AppSettings.trueGlass, AppSettings::setTrueGlass,
                        )
                    }
                    val liquid = canLiquid && AppSettings.trueGlass
                    if (liquid) {
                        SliderRow("Blur", AppSettings.glassLiquidBlur, 0f..12f, { "%.0f".format(it) }) {
                            AppSettings.setLiquidLook(blur = it)
                        }
                        SliderRow("Edge width", AppSettings.glassRefractH, 0f..32f, { "%.0f".format(it) }) {
                            AppSettings.setLiquidLook(edge = it)
                        }
                        SliderRow("Light bend", AppSettings.glassRefractA, 0f..60f, { "%.0f".format(it) }) {
                            AppSettings.setLiquidLook(bend = it)
                        }
                        SliderRow("Frost", AppSettings.glassTint, 0f..0.4f, { "%.0f%%".format(it * 250f) }) {
                            AppSettings.setGlassLook(tint = it)
                        }
                    } else {
                        SliderRow("Blur", AppSettings.glassBlur, 0f..40f, { "%.0f".format(it) }) {
                            AppSettings.setGlassLook(blur = it)
                        }
                        SliderRow("Frost", AppSettings.glassTint, 0f..0.4f, { "%.0f%%".format(it * 250f) }) {
                            AppSettings.setGlassLook(tint = it)
                        }
                        SliderRow("Shine", AppSettings.glassSheen, 0f..0.4f, { "%.0f%%".format(it * 250f) }) {
                            AppSettings.setGlassLook(sheen = it)
                        }
                        SliderRow("Edge light", AppSettings.glassRim, 0f..1f, { "%.0f%%".format(it * 100f) }) {
                            AppSettings.setGlassLook(rim = it)
                        }
                        SliderRow("Depth", AppSettings.glassDepth, 0f..20f, { "%.0f".format(it) }) {
                            AppSettings.setGlassLook(depth = it)
                        }
                    }
                    ActionRow("Glass look", "Back to default values", "Reset", AppSettings::resetGlassLook)
                }
            }
            // Manual key box only when the app has no built-in Last.fm server. Otherwise Connect does everything.
            if (AppSettings.DEFAULT_SIGNER.isBlank()) LastFmSection()
            LastFmScrobbleSection()
            SettingsSection("Feel") {
                SwitchRow("Haptics", "Small vibrations on taps and swipes", HapticSettings.enabled, AppSettings::setHaptics)
            }
            SettingsSection("Playback") {
                ChoiceRow(
                    title = "Crossfade",
                    options = listOf(0, 2, 4, 6, 8, 12),
                    selected = AppSettings.crossfadeSec,
                    label = { if (it == 0) "Off" else "${it}s" },
                    onSelect = AppSettings::setCrossfade,
                )
                ChoiceRow(
                    title = "Queue size",
                    options = listOf(10, 15, 20, 25, 30, 40),
                    selected = AppSettings.queueSize.let { s -> listOf(10, 15, 20, 25, 30, 40).minByOrNull { kotlin.math.abs(it - s) } ?: 20 },
                    label = { "$it songs" },
                    onSelect = AppSettings::setQueueSize,
                )
            }
            SettingsSection("Sound") {
                ChoiceRow(
                    title = "Spatial sound",
                    options = SpatialMode.values().toList(),
                    selected = AppSettings.spatialMode,
                    label = { it.label },
                    onSelect = AppSettings::setSpatialMode,
                )
                if (AppSettings.spatialMode != SpatialMode.Off) {
                    ChoiceRow(
                        title = "Strength",
                        options = listOf(30, 55, 80, 100),
                        selected = AppSettings.spatialStrength.let { s -> listOf(30, 55, 80, 100).minByOrNull { kotlin.math.abs(it - s) } ?: 55 },
                        label = { when (it) { 30 -> "Low"; 55 -> "Medium"; 80 -> "High"; else -> "Max" } },
                        onSelect = AppSettings::setSpatialStrength,
                    )
                }
                InfoRow(
                    "Modes",
                    when (AppSettings.spatialMode) {
                        SpatialMode.Off -> "Plain stereo"
                        SpatialMode.Wide -> "Wider stage"
                        SpatialMode.Surround -> "Out of head, headphones"
                        SpatialMode.Concert -> "Big hall"
                    },
                )
                // Always show the switch. Dolby on most phones is an OEM effect that Android cannot report,
                // so a wrong "not supported" is worse than a switch that does nothing.
                SwitchRow(
                    "Dolby Atmos / device spatial audio",
                    when (spatialSupport) {
                        com.example.chord.playback.SpatialSupport.Yes -> "Phone's own spatializer. Works best with Atmos-ready headphones or speakers"
                        com.example.chord.playback.SpatialSupport.Probable -> "Dolby found on this phone. Turn it on in system sound settings too"
                        com.example.chord.playback.SpatialSupport.None -> "Not reported by Android. Turn this on if your phone has Dolby in sound settings"
                    },
                    AppSettings.deviceSpatial, AppSettings::setDeviceSpatial,
                )
                ActionRow("System sound settings", "Turn Dolby Atmos on or off for your phone", "Open") {
                    val dolbyIntents = listOf(
                        Intent().setClassName("com.dolby.daxappui", "com.dolby.daxappui.MainActivity"),
                        context.packageManager.getLaunchIntentForPackage("com.dolby.daxappui"),
                        Intent("android.settings.panel.action.VOLUME"),
                        Intent(android.provider.Settings.ACTION_SOUND_SETTINGS),
                    )
                    for (i in dolbyIntents.filterNotNull()) {
                        if (runCatching { context.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess) break
                    }
                }
            }
            SettingsSection("Downloads") {
                ChoiceRow(
                    title = "Default download quality",
                    options = com.example.chord.data.settings.DownloadQuality.values().toList(),
                    selected = AppSettings.downloadQuality,
                    label = { it.label },
                    onSelect = AppSettings::setDownloadQuality,
                )
                Text(
                    AppSettings.downloadQuality.hint,
                    style = com.example.chord.ui.theme.ChordType.CardSub,
                    color = androidx.compose.ui.graphics.Color.White.copy(alpha = .62f),
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
                )
                SwitchRow(
                    "Ask for each song", "Pick the quality every time you tap download",
                    AppSettings.askDownloadQuality, AppSettings::setAskDownloadQuality,
                )
                ActionRow(
                    title = "Save folder",
                    hint = if (folderName != null) "$folderName · songs + lyrics (.lrc)" else "Not set. Songs stay inside Chord.",
                    action = if (folderName != null) "Change" else "Choose",
                    onClick = { pickFolder.launch(null) },
                )
                if (folderName != null) {
                    ActionRow("Move existing songs", "Move downloads from Chord into this folder", "Move", DownloadStore::moveAll)
                    ActionRow("Stop saving to folder", "New songs stay in Chord. Saved files stay where they are.", "Clear") {
                        AppSettings.setDownloadTree(null)
                    }
                }
            }
            MonochromeSection()
            AddonsSection()
            SettingsSection("Account") {
                ActionRow(
                    title = if (signedIn) "Signed in to YouTube Music" else "YouTube Music",
                    hint = if (signedIn) "Home shows your own mixes" else "Sign in for your own Home",
                    action = if (signedIn) "Sign out" else "Sign in",
                    onClick = { if (signedIn) AuthStore.signOut() else onLogin() },
                )
            }
            SettingsSection("About") {
                InfoRow("Version", version)
            }
            Spacer(Modifier.height(48.dp))
        }
    }
}