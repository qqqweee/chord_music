package com.example.chord.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.scale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.chord.playback.addon.Addon
import com.example.chord.playback.addon.AddonStore
import com.example.chord.ui.theme.ChordType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val Dim = Color.White.copy(alpha = 0.60f)

/** "Sources" block for Settings. Addons are tried top to bottom, YouTube Music last. */
@Composable
fun AddonsSection() {
    val addons by AddonStore.addons.collectAsState()
    val scope = rememberCoroutineScope()
    var showAdd by remember { mutableStateOf(false) }

    // Re-check every addon when the page opens.
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { AddonStore.addons.value.forEach { AddonStore.refresh(it.url) } }
    }

    SettingsSection("Sources") {
        addons.forEachIndexed { i, a ->
            AddonRow(
                a, index = i + 1,
                onToggle = { AddonStore.toggle(a.url, it) },
                onUp = { AddonStore.move(a.url, -1) },
                onDown = { AddonStore.move(a.url, 1) },
                onRemove = { AddonStore.remove(a.url) },
            )
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("YouTube Music", style = ChordType.CardTitle.copy(fontSize = 14.sp), color = Color.White)
                Text("Lossy · Full catalogue · Radio", style = ChordType.CardSub.copy(fontSize = 11.sp, lineHeight = 14.sp), color = Dim)
            }
            Text("Always on", style = ChordType.CardSub.copy(fontSize = 11.sp, lineHeight = 14.sp), color = Dim)
        }
        Column(Modifier.fillMaxWidth().clickable { showAdd = true }.padding(horizontal = 16.dp, vertical = 9.dp)) {
            Text("Add an addon", style = ChordType.CardTitle.copy(fontSize = 14.sp), color = Color.White)
            Text("Paste addon server link", style = ChordType.CardSub.copy(fontSize = 11.sp, lineHeight = 14.sp), color = Dim)
        }
    }
    Text(
        "If a source has no track or can't be reached, the next one is tried. YouTube Music is the last fallback.",
        style = ChordType.CardSub, color = Dim, modifier = Modifier.padding(horizontal = 22.dp, vertical = 8.dp),
    )

    if (showAdd) {
        var text by remember { mutableStateOf("") }
        var msg by remember { mutableStateOf("") }
        var busy by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { showAdd = false },
            title = { Text("Add an addon") },
            text = {
                Column {
                    Text("Paste the addon's URL, either its root or its manifest.json.")
                    OutlinedTextField(
                        value = text, onValueChange = { text = it; msg = "" }, singleLine = true,
                        placeholder = { Text("https://my-addon.example.com") },
                        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                    )
                    if (msg.isNotBlank()) Text(msg, modifier = Modifier.padding(top = 8.dp))
                }
            },
            confirmButton = {
                Row {
                    TextButton(enabled = !busy && text.isNotBlank(), onClick = {
                        busy = true; msg = "Testing…"
                        scope.launch {
                            msg = withContext(Dispatchers.IO) {
                                runCatching { AddonStore.test(text) }.fold({ "OK: ${it.name} ${it.summary}" }, { it.message ?: "Failed" })
                            }
                            busy = false
                        }
                    }) { Text("Test") }
                    TextButton(enabled = !busy && text.isNotBlank(), onClick = {
                        busy = true; msg = "Saving…"
                        scope.launch {
                            val err = withContext(Dispatchers.IO) { runCatching { AddonStore.add(text) }.exceptionOrNull() }
                            busy = false
                            if (err == null) showAdd = false else msg = err.message ?: "Failed"
                        }
                    }) { Text("Save") }
                    TextButton(onClick = { showAdd = false }) { Text("Cancel") }
                }
            },
        )
    }
}

@Composable
private fun AddonRow(
    a: Addon, index: Int,
    onToggle: (Boolean) -> Unit, onUp: () -> Unit, onDown: () -> Unit, onRemove: () -> Unit,
) {
    val hint = if (a.officialOnly) "Needs the official BitChord app. Chord can't use this addon."
        else if (a.status.isNotBlank()) a.status
        else listOf(a.summary, if (a.downloads) "Downloads allowed" else "No downloads").filter { it.isNotBlank() }.joinToString(" · ")
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("$index  ${a.name}", style = ChordType.CardTitle.copy(fontSize = 14.sp), color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(hint, style = ChordType.CardSub, color = Dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Icon(Icons.Rounded.KeyboardArrowUp, "Move up", tint = Dim, modifier = Modifier.size(26.dp).clickable(onClick = onUp).padding(3.dp))
        Icon(Icons.Rounded.KeyboardArrowDown, "Move down", tint = Dim, modifier = Modifier.size(26.dp).clickable(onClick = onDown).padding(3.dp))
        Icon(Icons.Rounded.Close, "Remove", tint = Dim, modifier = Modifier.size(26.dp).clickable(onClick = onRemove).padding(5.dp))
        Switch(
            modifier = Modifier.scale(0.75f),
            checked = a.enabled, onCheckedChange = onToggle,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.Black, checkedTrackColor = Color.White,
                uncheckedThumbColor = Color.White.copy(alpha = 0.6f), uncheckedTrackColor = Color.White.copy(alpha = 0.14f),
                uncheckedBorderColor = Color.Transparent,
            ),
        )
    }
}
