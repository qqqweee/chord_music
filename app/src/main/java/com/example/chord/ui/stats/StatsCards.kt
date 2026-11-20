package com.example.chord.ui.stats

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.chord.data.home.HomeItem
import com.example.chord.data.home.HomeItemType
import com.example.chord.data.stats.ListenStats
import com.example.chord.data.stats.formatListenTime
import com.example.chord.ui.components.CoverImage
import com.example.chord.ui.components.pressScale
import com.example.chord.ui.haptics.Haptic
import com.example.chord.ui.haptics.hapticClickable
import com.example.chord.ui.haptics.rememberHaptics
import com.example.chord.ui.theme.ChordType
import kotlinx.coroutines.launch

private val Glass = Color.White.copy(alpha = 0.14f)

/**
 * Explore header. One wide "listening time" card (with Share) and two cover cards.
 * Share draws a poster image of the stats and opens the system share sheet.
 */
@Composable
fun StatsCards(stats: ListenStats, onItem: (HomeItem) -> Unit, modifier: Modifier = Modifier) {
    StatCardDeck(stats, onItem, modifier)
}

@Composable
private fun TimeCard(s: ListenStats, onShare: () -> Unit) {
    val shape = RoundedCornerShape(24.dp)
    Box(
        Modifier.fillMaxWidth().clip(shape)
            .background(Brush.linearGradient(listOf(Color(0xFF2B2160), Color(0xFF12121C))))
            .border(0.5.dp, Color.White.copy(alpha = 0.12f), shape),
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("LISTENING TIME", style = ChordType.Eyebrow, color = Color.White.copy(alpha = 0.6f), modifier = Modifier.weight(1f))
                Box(
                    Modifier.size(34.dp).clip(CircleShape).border(0.5.dp, Glass, CircleShape)
                        .background(Color.White.copy(alpha = 0.06f)).hapticClickable(Haptic.Confirm, onClick = onShare),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Rounded.Share, "Share", tint = Color.White, modifier = Modifier.size(16.dp)) }
            }
            Text(
                formatListenTime(s.totalMs), color = Color.White, fontSize = 44.sp, fontWeight = FontWeight.SemiBold,
                letterSpacing = (-1.5).sp, modifier = Modifier.padding(top = 10.dp),
            )
            Text("all time", style = ChordType.CardSub, color = Color.White.copy(alpha = 0.55f))
            Spacer(Modifier.height(18.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Mini("Today", formatListenTime(s.todayMs), Modifier.weight(1f))
                Divider()
                Mini("7 days", formatListenTime(s.weekMs), Modifier.weight(1f))
                Divider()
                Mini("Streak", "${s.streakDays} d", Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun Divider() = Box(Modifier.width(0.5.dp).height(28.dp).background(Color.White.copy(alpha = 0.14f)))

@Composable
private fun Mini(label: String, value: String, modifier: Modifier) {
    Column(modifier.padding(start = 12.dp)) {
        Text(value, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Text(label, style = ChordType.CardSub, color = Color.White.copy(alpha = 0.55f))
    }
}

/** Full-bleed cover with a soft bottom fade. Text sits on the image. */
@Composable
private fun PosterCard(
    label: String,
    title: String,
    sub: String,
    thumb: String?,
    side: androidx.compose.ui.unit.Dp,
    onClick: (() -> Unit)?,
) {
    val shape = RoundedCornerShape(22.dp)
    Box(
        Modifier.size(side).clip(shape)
            .background(Brush.linearGradient(listOf(Color(0xFF3A3A4A), Color(0xFF1A1A22))))
            .border(0.5.dp, Color.White.copy(alpha = 0.12f), shape)
            .then(if (onClick != null) Modifier.pressScale(0.97f, onClick = onClick) else Modifier),
    ) {
        if (thumb != null) CoverImage(thumb, side, RoundedCornerShape(0.dp))
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.35f), 0.45f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.82f))
            )
        )
        Text(label.uppercase(), style = ChordType.Eyebrow, color = Color.White.copy(alpha = 0.85f), modifier = Modifier.align(Alignment.TopStart).padding(14.dp))
        Column(Modifier.align(Alignment.BottomStart).padding(14.dp)) {
            Text(title, style = ChordType.CardTitle, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(sub, style = ChordType.CardSub, color = Color.White.copy(alpha = 0.7f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
