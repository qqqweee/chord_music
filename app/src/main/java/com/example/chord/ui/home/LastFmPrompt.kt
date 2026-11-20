package com.example.chord.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.chord.data.lastfm.LastFmApi
import com.example.chord.ui.components.pressScale
import com.example.chord.ui.settings.LASTFM_STEPS
import com.example.chord.ui.theme.ChordType

/** Shown once on Home until a Last.fm key is added. Asks nicely, shows the steps, links to Last.fm and Settings. */
@Composable
fun LastFmPrompt(onSettings: () -> Unit, onLater: () -> Unit) {
    val uri = LocalUriHandler.current
    Dialog(onDismissRequest = onLater) {
        Column(
            Modifier.clip(RoundedCornerShape(26.dp)).background(Color(0xFF1C1C22)).padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val simple = com.example.chord.data.settings.AppSettings.DEFAULT_SIGNER.isNotBlank()
            Text(if (simple) "Connect Last.fm" else "Get smarter mixes", style = ChordType.Section, color = Color.White)
            if (simple) Text(
                "One tap turns on scrobbling, Discover Weekly, mood mixes and smarter radio. Settings > Last.fm > Connect.",
                style = ChordType.CardSub.copy(lineHeight = 17.sp), color = Color.White.copy(alpha = 0.75f),
            ) else Text(
                "Add a free Last.fm key to unlock Discover Weekly and \"If you like…\" mixes picked from what you love. " +
                    "Daily mixes and Replay already work without it.",
                style = ChordType.CardSub.copy(lineHeight = 17.sp), color = Color.White.copy(alpha = 0.75f),
            )
            if (!simple) {
                Text(LASTFM_STEPS, style = ChordType.CardSub.copy(lineHeight = 17.sp), color = Color.White.copy(alpha = 0.9f))
                Text("Paste the key in Settings > Last.fm.", style = ChordType.CardSub, color = Color.White.copy(alpha = 0.6f))
            }
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!simple) Btn("Get key", false) { uri.openUri(LastFmApi.KEY_URL) }
                Btn(if (simple) "Connect" else "Settings", true) { onSettings() }
                Btn("Not now", false) { onLater() }
            }
        }
    }
}

@Composable
private fun Btn(label: String, filled: Boolean, onClick: () -> Unit) {
    Text(
        label, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = if (filled) Color.Black else Color.White,
        modifier = Modifier.clip(RoundedCornerShape(50))
            .background(if (filled) Color.White else Color.White.copy(alpha = 0.12f))
            .pressScale(pressedScale = 0.94f, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    )
}
