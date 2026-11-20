package com.example.chord.ui.library

import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FileOpen
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.chord.ui.components.ChordDialog
import com.example.chord.ui.components.ChordTextField
import com.example.chord.ui.haptics.Haptic
import com.example.chord.ui.haptics.hapticClickable
import com.example.chord.ui.theme.ChordTheme

/**
 * Spotify import as one small dialog. Link or CSV, status inline. No page.
 * YouTube Music playlists need sign in, so they are not here.
 */
@Composable
fun SpotifyImportDialog(
    onDismiss: () -> Unit,
    onOpenPlaylist: (Long) -> Unit,
    vm: ImportViewModel = viewModel(),
) {
    val ui by vm.ui.collectAsState()
    val context = LocalContext.current
    var link by remember { mutableStateOf("") }
    val working = ui is ImportUi.Working
    val done = ui as? ImportUi.Done

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: "Spotify import"
            vm.spotifyCsv(uri, name)
        }
    }

    ChordDialog(
        title = "Import from Spotify",
        onDismiss = { vm.cancel(); onDismiss() },
        confirm = if (done != null) "Open" else "Import",
        confirmEnabled = done != null || (!working && link.isNotBlank()),
        onConfirm = {
            if (done != null) { vm.dismiss(); onOpenPlaylist(done.playlistId) } else vm.spotifyLink(link)
        },
        content = {
            Column {
                Text(
                    "Public playlist or album link. Songs are matched on YouTube Music.",
                    color = ChordTheme.textDim, fontSize = 13.sp, fontWeight = FontWeight.Medium, lineHeight = 17.sp,
                )
                ChordTextField(link, { link = it }, "open.spotify.com/playlist/…")
                Row(
                    Modifier.padding(top = 12.dp)
                        .hapticClickable(Haptic.Tap, enabled = !working) {
                            picker.launch(arrayOf("text/csv", "text/comma-separated-values", "application/csv", "text/plain", "application/vnd.ms-excel"))
                        },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.FileOpen, null, tint = ChordTheme.textDim, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Use a CSV file instead", color = ChordTheme.text, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                }
                when (val s = ui) {
                    is ImportUi.Working -> Row(Modifier.padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(color = ChordTheme.text, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(10.dp))
                        Text(
                            if (s.total > 0) "${s.label}  ${s.done}/${s.total}" else s.label,
                            color = ChordTheme.text, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                        )
                    }
                    is ImportUi.Done -> Text(
                        "Saved \"${s.name}\" · ${s.added} songs" + if (s.missed > 0) " · ${s.missed} not found" else "",
                        color = ChordTheme.text, fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 14.dp),
                    )
                    is ImportUi.Failed -> Text(
                        s.message, color = Color(0xFFFF6B6B), fontSize = 13.sp, fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(top = 14.dp),
                    )
                    ImportUi.Idle -> Unit
                }
            }
        },
    )
}
