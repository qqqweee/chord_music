package com.example.chord.ui.library

import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import com.example.chord.ui.components.chordCard
import com.example.chord.ui.theme.ChordType
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
//import androidx.compose.foundation.lazy.stickyHeader 
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.FileOpen
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.chord.data.auth.AuthStore
import com.example.chord.ui.components.CoverImage
import com.example.chord.ui.haptics.Haptic
import com.example.chord.ui.haptics.hapticClickable
import com.example.chord.ui.haptics.rememberHaptics
import com.example.chord.ui.theme.ChordTheme

private val SpotifyDot = Color(0xFF1DB954)
private val YtDot = Color(0xFFE5393F)

/**
 * Library > Import. Spotify (link or CSV) and YouTube Music (library or link).
 * Progress and result sit in a bar that sticks to the top, so you never scroll to find it.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun ImportTab(
    onLogin: () -> Unit,
    onOpenPlaylist: (Long) -> Unit,
    modifier: Modifier = Modifier,
    vm: ImportViewModel = viewModel(),
) {
    val ui by vm.ui.collectAsState()
    val yt by vm.yt.collectAsState()
    val signedIn by AuthStore.signedIn.collectAsState()
    val context = LocalContext.current
    val haptics = rememberHaptics()

    LaunchedEffect(signedIn) { if (signedIn) vm.loadYtLibrary() }
    LaunchedEffect(ui) {
        when (ui) {
            is ImportUi.Done -> haptics.perform(Haptic.Confirm)
            is ImportUi.Failed -> haptics.perform(Haptic.Reject)
            else -> Unit
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: "Spotify import"
            vm.spotifyCsv(uri, name)
        }
    }

    var spotifyText by remember { mutableStateOf("") }
    var ytText by remember { mutableStateOf("") }

    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    // Import started, finished or failed: jump to the top so the bar is in view.
    LaunchedEffect(ui::class) { if (ui !is ImportUi.Idle) listState.animateScrollToItem(0) }

    LazyColumn(
        modifier.fillMaxSize(),
        state = listState,
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 200.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (ui !is ImportUi.Idle) stickyHeader(key = "status") {
            Box(Modifier.fillMaxWidth().background(ChordTheme.bg).padding(bottom = 6.dp)) {
                StatusCard(ui, onCancel = vm::cancel, onDismiss = vm::dismiss, onOpen = { id -> vm.dismiss(); onOpenPlaylist(id) })
            }
        }

        item(key = "spotify") {
            SourceCard(
                dot = SpotifyDot, title = "Spotify",
                hint = "Paste a public playlist or album link. For private or big lists, use a CSV from Exportify.",
            ) {
                LinkField(spotifyText, { spotifyText = it }, "open.spotify.com/playlist/…") {
                    vm.spotifyLink(spotifyText); spotifyText = ""
                }
                GhostButton("Choose CSV file", Icons.Rounded.FileOpen) {
                    picker.launch(arrayOf("text/csv", "text/comma-separated-values", "application/csv", "text/plain", "application/vnd.ms-excel"))
                }
            }
        }

        item(key = "yt") {
            SourceCard(
                dot = YtDot, title = "YouTube Music",
                hint = if (signedIn) "Tap a playlist below to copy it here." else "Sign in to import your own playlists and likes.",
            ) {
                if (!signedIn) GhostButton("Sign in", Icons.Rounded.Link, onLogin)
                LinkField(ytText, { ytText = it }, "music.youtube.com/playlist?list=…") {
                    vm.ytLink(ytText); ytText = ""
                }
            }
        }

        if (signedIn) when (val y = yt) {
            is YtLibraryUi.Loading -> item(key = "ytl") {
                Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = ChordTheme.text, strokeWidth = 2.dp, modifier = Modifier.size(24.dp))
                }
            }
            is YtLibraryUi.Error -> item(key = "yte") {
                Text(y.message, color = ChordTheme.textDim, fontSize = 13.sp)
                GhostButton("Retry", Icons.Rounded.Link, vm::loadYtLibrary)
            }
            is YtLibraryUi.Ready -> {
                item(key = "ythead") {
                    Text("YOUR LIBRARY", style = ChordType.Eyebrow, color = ChordTheme.textDim,
                        modifier = Modifier.padding(top = 6.dp, start = 4.dp))
                }
                items(y.items.size, key = { "yt:$it" }) { i ->
                    val item = y.items[i]
                    val busy = ui is ImportUi.Working
                    Row(
                        Modifier.fillMaxWidth().chordCard(14.dp)
                            .hapticClickable(Haptic.Tap, enabled = !busy) { vm.ytItem(item) }.padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CoverImage(item.thumbnailUrl, 44.dp, RoundedCornerShape(8.dp))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(item.title, color = ChordTheme.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (item.subtitle.isNotBlank()) Text(item.subtitle, color = ChordTheme.textDim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        val mine = (ui as? ImportUi.Working)?.label == "Reading ${item.title}"
                        if (mine) CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(20.dp).padding(end = 0.dp))
                        else Text("Import", style = ChordType.Label, color = if (busy) ChordTheme.textDim else ChordTheme.text, modifier = Modifier.border(0.5.dp, ChordTheme.hairline, RoundedCornerShape(50)).padding(horizontal = 12.dp, vertical = 6.dp))
                    }
                }
            }
            else -> Unit
        }
    }
}

@Composable
private fun SourceCard(dot: Color, title: String, hint: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().chordCard(20.dp).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(dot))
            Spacer(Modifier.width(8.dp))
            Text(title, style = ChordType.Section, color = ChordTheme.text, fontSize = 18.sp)
        }
        Text(hint, style = ChordType.CardSub, color = ChordTheme.textDim, lineHeight = 17.sp)
        content()
    }
}

/** Pill field with the action inside it. Hairline border, no heavy fill. */
@Composable
private fun LinkField(value: String, onValue: (String) -> Unit, placeholder: String, onGo: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Row(
        Modifier.fillMaxWidth().height(44.dp).clip(shape).background(ChordTheme.surface).border(0.5.dp, ChordTheme.hairline, shape)
            .padding(start = 16.dp, end = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (value.isEmpty()) Text(placeholder, style = ChordType.CardSub, color = ChordTheme.textDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
            androidx.compose.foundation.text.BasicTextField(
                value = value, onValueChange = onValue, singleLine = true,
                textStyle = ChordType.CardTitle.copy(color = ChordTheme.text),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(ChordTheme.text),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Text(
            "Import", style = ChordType.Label, color = ChordTheme.onAccent,
            modifier = Modifier.clip(shape).background(if (value.isBlank()) ChordTheme.surfaceHigh else ChordTheme.text)
                .hapticClickable(Haptic.Confirm, enabled = value.isNotBlank(), onClick = onGo)
                .padding(horizontal = 16.dp, vertical = 9.dp),
        )
    }
}

@Composable
private fun GhostButton(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    Row(
        Modifier.hapticClickable(Haptic.Tap, onClick = onClick).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = ChordTheme.textDim, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, style = ChordType.Label, color = ChordTheme.text)
    }
}

private val BarFill = Color(0xFF1E1E22)
private val Good = Color(0xFF3DDC84)
private val Bad = Color(0xFFFF6B6B)

/** One solid bar. White on dark, green only for done, red only for error. No yellow accent. */
@Composable
private fun StatusCard(ui: ImportUi, onCancel: () -> Unit, onDismiss: () -> Unit, onOpen: (Long) -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    val edge = when (ui) { is ImportUi.Done -> Good.copy(alpha = 0.5f); is ImportUi.Failed -> Bad.copy(alpha = 0.5f); else -> Color.White.copy(alpha = 0.16f) }
    Column(Modifier.fillMaxWidth().clip(shape).background(BarFill).border(1.dp, edge, shape).padding(14.dp)) {
        when (ui) {
            is ImportUi.Working -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Importing…", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            if (ui.total > 0) "${ui.label} · ${ui.done} of ${ui.total}" else ui.label,
                            color = ChordTheme.textDim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Text(
                        "Cancel", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.12f))
                            .hapticClickable(Haptic.Tap, onClick = onCancel).padding(horizontal = 14.dp, vertical = 7.dp),
                    )
                }
                Spacer(Modifier.height(12.dp))
                if (ui.total > 0) androidx.compose.material3.LinearProgressIndicator(
                    progress = { ui.done / ui.total.toFloat() },
                    modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(50)),
                    color = Color.White, trackColor = Color.White.copy(alpha = 0.15f),
                ) else androidx.compose.material3.LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(50)),
                    color = Color.White, trackColor = Color.White.copy(alpha = 0.15f),
                )
            }
            is ImportUi.Done -> {
                var showMissed by remember(ui) { mutableStateOf(false) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.CheckCircle, null, tint = Good, modifier = Modifier.size(26.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Imported \"${ui.name}\"", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Row {
                            Text("${ui.added} songs", color = ChordTheme.textDim, fontSize = 12.sp)
                            if (ui.missed > 0) Text(
                                " · ${ui.missed} not found ${if (showMissed) "▴" else "▾"}", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium,
                                modifier = Modifier.hapticClickable(Haptic.Tap) { showMissed = !showMissed },
                            )
                        }
                    }
                    Text(
                        "Open", color = Color.Black, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clip(RoundedCornerShape(50)).background(Color.White)
                            .hapticClickable(Haptic.Tap) { onOpen(ui.playlistId) }.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                    Text(
                        "✕", color = ChordTheme.textDim, fontSize = 16.sp,
                        modifier = Modifier.hapticClickable(Haptic.Tap, onClick = onDismiss).padding(start = 10.dp, top = 6.dp, bottom = 6.dp, end = 2.dp),
                    )
                }
                if (showMissed) Column(Modifier.padding(top = 10.dp).heightIn(max = 160.dp).verticalScroll(rememberScrollState())) {
                    ui.missedTitles.forEach { Text("• $it", color = ChordTheme.textDim, fontSize = 12.sp, lineHeight = 17.sp) }
                }
            }
            is ImportUi.Failed -> Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.ErrorOutline, null, tint = Bad, modifier = Modifier.size(26.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Import failed", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Text(ui.message, color = ChordTheme.textDim, fontSize = 12.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
                Text(
                    "OK", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.12f))
                        .hapticClickable(Haptic.Tap, onClick = onDismiss).padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            ImportUi.Idle -> Unit
        }
    }
}
