package com.example.chord.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import com.example.chord.ui.components.AppLogo
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.NotificationsNone
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.automirrored.rounded.Login
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.Settings
import com.example.chord.ui.components.ChordMenu
import com.example.chord.ui.components.ChordMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.example.chord.data.auth.AuthStore
import com.example.chord.ui.components.pressScale
import com.example.chord.ui.theme.ChordType
import java.util.Calendar

/** Greeting + search + account. Scrolls away with the page. */
@Composable
internal fun HomeHeader(signedIn: Boolean, onSearch: () -> Unit, onLogin: () -> Unit, onSettings: () -> Unit, hasUpdates: Boolean, onUpdates: () -> Unit) {
    val greeting = remember { greetingForNow() }
    var menu by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxWidth().statusBarsPadding()
            .padding(start = PageGutter, end = PageGutter, top = 10.dp, bottom = 18.dp),
    ) {
        // Logo slot. Chord logo lives in AppLogo.kt. Change it there, it changes everywhere.
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppLogo(logoSize = 40.dp)
            Text(
                "Chord", style = ChordType.Section, color = Color.White,
                modifier = Modifier.weight(1f).padding(start = 10.dp),
            )
            RoundIcon(Icons.Rounded.Search, "Search", filled = false, onClick = onSearch)
            Spacer(Modifier.width(8.dp))
            Box {
                RoundIcon(Icons.Rounded.NotificationsNone, "Updates", filled = false, onClick = onUpdates)
                if (hasUpdates) Box(Modifier.align(Alignment.TopEnd).padding(top = 7.dp, end = 7.dp).size(8.dp).clip(CircleShape).background(Color(0xFFFF6B5E)))
            }
            Spacer(Modifier.width(8.dp))
            Box {
                RoundIcon(Icons.Rounded.Person, if (signedIn) "Account" else "Sign in", filled = signedIn) {
                    menu = true
                }
                ChordMenu(expanded = menu, onDismiss = { menu = false }) {
                    ChordMenuItem("Settings", Icons.Rounded.Settings) { menu = false; onSettings() }
                    if (signedIn) {
                        ChordMenuItem("Sign out", Icons.AutoMirrored.Rounded.Logout) { menu = false; AuthStore.signOut() }
                    } else {
                        ChordMenuItem("Sign in", Icons.AutoMirrored.Rounded.Login) { menu = false; onLogin() }
                    }
                }
            }
        }
        Spacer(Modifier.height(22.dp))
        Text(greeting, style = ChordType.Display, color = Color.White)
        Text("What do you want to hear?", style = ChordType.CardSub, color = Color.White.copy(alpha = 0.6f))
    }
}

@Composable
private fun RoundIcon(icon: ImageVector, description: String, filled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(42.dp).clip(CircleShape)
            .background(if (filled) Color.White else Color.White.copy(alpha = 0.10f))
            .pressScale(pressedScale = 0.9f, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, description, tint = if (filled) Color.Black else Color.White, modifier = Modifier.size(22.dp)) }
}

private fun greetingForNow(): String = when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
    in 5..11 -> "Good morning"
    in 12..16 -> "Good afternoon"
    in 17..21 -> "Good evening"
    else -> "Late night"
}