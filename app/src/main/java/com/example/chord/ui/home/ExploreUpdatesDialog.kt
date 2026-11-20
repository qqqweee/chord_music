package com.example.chord.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.chord.data.home.ExploreUpdate
import com.example.chord.ui.components.pressScale
import com.example.chord.ui.theme.ChordTheme
import java.text.DateFormat
import java.util.Date

@Composable
fun ExploreUpdatesDialog(
    updates: List<ExploreUpdate>,
    onDismiss: () -> Unit,
    onOpen: (ExploreUpdate) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = ChordTheme.sheet,
        shape = RoundedCornerShape(28.dp),
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("What's new", color = ChordTheme.text, fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
                    Text("New music found in Explore", color = ChordTheme.textDim, fontSize = 13.sp)
                }
                Box(Modifier.size(36.dp).clip(RoundedCornerShape(50)).pressScale(onClick = onDismiss), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Close, "Close", tint = ChordTheme.textDim)
                }
            }
        },
        text = {
            if (updates.isEmpty()) {
                Text("You're all caught up. New albums and playlists will appear here when Explore refreshes.", color = ChordTheme.textDim, fontSize = 14.sp)
            } else {
                LazyColumn(Modifier.heightIn(max = 440.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(updates, key = { it.id }) { item ->
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                                .background(Color.White.copy(alpha = if (item.read) 0.035f else 0.075f))
                                .pressScale(pressedScale = 0.98f, onClick = { onOpen(item) })
                                .padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            AsyncImage(item.thumbnailUrl, null, Modifier.size(54.dp).clip(RoundedCornerShape(10.dp)))
                            Column(Modifier.weight(1f).padding(start = 11.dp)) {
                                Text(item.section, color = ChordTheme.textDim, fontSize = 11.sp, maxLines = 1)
                                Text(item.title, color = ChordTheme.text, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(item.detectedAt)), color = ChordTheme.textDim, fontSize = 11.sp)
                            }
                            if (!item.read) Box(Modifier.padding(start = 8.dp).size(7.dp).clip(RoundedCornerShape(50)).background(Color(0xFFFF6B5E)))
                        }
                    }
                }
            }
        },
        confirmButton = {
            Text("Done", color = ChordTheme.text, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clip(RoundedCornerShape(50)).pressScale(onClick = onDismiss).padding(horizontal = 18.dp, vertical = 9.dp))
        },
    )
}
