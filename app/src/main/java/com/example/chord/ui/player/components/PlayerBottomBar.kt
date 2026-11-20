
package com.example.chord.ui.player.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.example.chord.ui.player.PlayerGlyph
import com.example.chord.ui.player.PlayerIcon
import com.example.chord.ui.player.components.PlayerDefaults.PlayerWhite
import com.example.chord.ui.player.components.PlayerDefaults.TAB_LYRICS
import com.example.chord.ui.player.components.PlayerDefaults.TAB_PLAYER
import com.example.chord.ui.player.components.PlayerDefaults.TAB_QUEUE

@Composable
fun PlayerBottomBar(
    selectedTab: Int,
    onPlayer: () -> Unit,
    onLyrics: () -> Unit,
    onQueue: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(58.dp)
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Lyrics button
        IconButton(
            onClick = onLyrics,
            modifier = Modifier.size(40.dp)
        ) {
            PlayerGlyph(
                PlayerIcon.Lyrics,
                Modifier.size(22.dp),
                alpha = if (selectedTab == TAB_LYRICS) 1f else 0.76f
            )
        }

        // Center navigation pill
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(PlayerWhite.copy(alpha = 0.12f))
                .padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(50))
                    .clickable(onClick = onPlayer),
                contentAlignment = Alignment.Center
            ) {
                PlayerGlyph(
                    PlayerIcon.Headphones,
                    Modifier.size(21.dp),
                    alpha = if (selectedTab == TAB_PLAYER) 1f else 0.75f
                )
            }

            Box(
                modifier = Modifier
                    .width(1.dp)
                    .height(24.dp)
                    .background(PlayerWhite.copy(alpha = 0.20f))
            )

            // Person icon
            Box(
                modifier = Modifier.size(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Person,
                    contentDescription = "Person",
                    tint = PlayerWhite.copy(alpha = 0.78f),
                    modifier = Modifier.size(21.dp)
                )
            }
        }

        // Queue button
        IconButton(
            onClick = onQueue,
            modifier = Modifier.size(40.dp)
        ) {
            PlayerGlyph(
                PlayerIcon.Queue,
                Modifier.size(22.dp),
                alpha = if (selectedTab == TAB_QUEUE) 1f else 0.78f
            )
        }
    }
}
