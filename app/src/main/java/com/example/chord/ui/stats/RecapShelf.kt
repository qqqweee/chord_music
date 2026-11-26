package com.example.chord.ui.stats

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.chord.data.stats.RecapPeriod
import com.example.chord.ui.components.pressScale
import com.example.chord.ui.stats.story.FitText
import com.example.chord.ui.stats.story.LogoDoodle
import com.example.chord.ui.stats.story.PosterStyle
import com.example.chord.ui.stats.story.StoryColors
import com.example.chord.ui.stats.story.paperGrain

/** Three poster tiles: Week, Month, Year. Tap one to open its story. Replaces the old stat cards. */
@Composable
fun RecapShelf(onOpen: (RecapPeriod) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.padding(horizontal = 18.dp, vertical = 4.dp).padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        RecapPeriod.values().forEach { p -> Tile(p, Modifier.weight(1f)) { onOpen(p) } }
    }
}

@Composable
private fun Tile(p: RecapPeriod, modifier: Modifier, onClick: () -> Unit) {
    val (bg, fg, word) = when (p) {
        RecapPeriod.Week -> Triple(StoryColors.Cream, StoryColors.Ink, StoryColors.Coral)
        RecapPeriod.Month -> Triple(StoryColors.Ink, StoryColors.Cream, StoryColors.Hot)
        RecapPeriod.Year -> Triple(StoryColors.Coral, StoryColors.Cream, StoryColors.Ink)
    }
    val onInk = bg != StoryColors.Cream
    Box(
        modifier.aspectRatio(0.72f).clip(RoundedCornerShape(20.dp)).background(bg).paperGrain(onInk, p.ordinal + 2)
            .pressScale(0.96f, onClick = onClick)
    ) {
        // Bullseye corner, same as the story.
        Canvas(Modifier.fillMaxSize()) {
            for (i in 5 downTo 1) drawCircle(if (i % 2 == 0) fg.copy(alpha = 0.9f) else bg, size.width * 0.12f * i, Offset(size.width, size.height))
        }
        LogoDoodle(fg, Modifier.padding(12.dp).size(26.dp).align(Alignment.TopStart))
        Column(Modifier.align(Alignment.BottomStart).padding(start = 12.dp, bottom = 14.dp, end = 8.dp)) {
            Text("YOUR", style = PosterStyle.copy(fontSize = 12.sp, letterSpacing = 0.sp), color = fg)
            FitText(p.word, 30f, word, minSp = 14f)
            Text("IN SOUND", style = PosterStyle.copy(fontSize = 12.sp, letterSpacing = 0.sp), color = fg)
        }
        Box(Modifier.align(Alignment.TopEnd).padding(10.dp).size(26.dp).background(fg, CircleShape), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.PlayArrow, null, tint = bg, modifier = Modifier.size(16.dp))
        }
    }
}
