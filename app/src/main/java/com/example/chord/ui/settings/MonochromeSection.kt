package com.example.chord.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.chord.playback.addon.MonochromeResolver
import com.example.chord.ui.theme.ChordType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun MonochromeSection() {
    var showAdd by remember { mutableStateOf(false) }
    var refresh by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()
    val servers = remember(refresh) { MonochromeResolver.servers() }

    SettingsSection("Monochrome · Lossless FLAC") {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
            Text("Preferred source", style = ChordType.CardTitle.copy(fontSize = 14.sp), color = Color.White)
            Text(
                "Chord checks Monochrome for a matching lossless FLAC recording before other sources. YouTube Music remains the fallback.",
                style = ChordType.CardSub, color = Color.White.copy(alpha = .62f),
            )
        }
        servers.forEach { server ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(server.displayName, style = ChordType.CardTitle.copy(fontSize = 13.sp), color = Color.White)
                    Text(
                        if (server.builtIn) "Built-in public streamer" else "Custom ${server.kind.name.lowercase()} server",
                        style = ChordType.CardSub, color = Color.White.copy(alpha = .58f),
                    )
                }
                if (!server.builtIn) {
                    Text(
                        "Remove",
                        modifier = Modifier.clickable {
                            MonochromeResolver.removeServer(server.baseUrl)
                            refresh++
                        }.padding(8.dp),
                        color = Color.White.copy(alpha = .7f),
                    )
                }
            }
        }
        Column(Modifier.fillMaxWidth().clickable { showAdd = true }.padding(horizontal = 16.dp, vertical = 10.dp)) {
            Text("Add Monochrome server", style = ChordType.CardTitle.copy(fontSize = 14.sp), color = Color.White)
            Text("Add a compatible HTTPS streamer or relay URL", style = ChordType.CardSub, color = Color.White.copy(alpha = .58f))
        }
    }

    if (showAdd) {
        var input by remember { mutableStateOf("") }
        var message by remember { mutableStateOf("") }
        var busy by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { if (!busy) showAdd = false },
            title = { Text("Add Monochrome server") },
            text = {
                Column {
                    Text("Paste the HTTPS base URL of a Monochrome-compatible track streamer or relay.")
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it; message = "" },
                        singleLine = true,
                        placeholder = { Text("https://tracks.example.com") },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                    if (message.isNotBlank()) Text(message, modifier = Modifier.padding(top = 8.dp))
                }
            },
            confirmButton = {
                Row {
                    TextButton(enabled = input.isNotBlank() && !busy, onClick = {
                        busy = true
                        message = "Checking server…"
                        scope.launch {
                            val result = withContext(Dispatchers.IO) {
                                runCatching { MonochromeResolver.addServer(input) }
                            }
                            busy = false
                            result.fold(
                                { message = "Added ${it.displayName}"; refresh++; showAdd = false },
                                { message = it.message ?: "Unable to add server" },
                            )
                        }
                    }) { Text("Add") }
                    TextButton(enabled = !busy, onClick = { showAdd = false }) { Text("Cancel") }
                }
            },
        )
    }
}
