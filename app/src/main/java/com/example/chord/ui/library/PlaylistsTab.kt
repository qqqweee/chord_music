package com.example.chord.ui.library

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.example.chord.ui.components.ChordDialog
import com.example.chord.ui.components.ChordTextField

/** Shared by create and rename. Confirm is disabled while the name is blank. */
@Composable
fun NamePlaylistDialog(
    title: String,
    initial: String,
    confirm: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by remember { mutableStateOf(initial) }
    ChordDialog(
        title = title,
        onDismiss = onDismiss,
        confirm = confirm,
        confirmEnabled = name.isNotBlank(),
        onConfirm = { onConfirm(name.trim()) },
    ) {
        ChordTextField(name, { name = it.take(60) }, "Playlist name")
    }
}
