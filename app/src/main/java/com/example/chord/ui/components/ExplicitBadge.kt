package com.example.chord.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Small grey "E" tile for explicit songs. */
@Composable
fun ExplicitBadge(modifier: Modifier = Modifier, size: Dp = 14.dp, tint: Color = Color.White.copy(alpha = 0.55f)) {
    Box(
        modifier.size(size).clip(RoundedCornerShape(size / 4)).background(tint),
        contentAlignment = Alignment.Center,
    ) {
        Text("E", color = Color.Black.copy(alpha = 0.85f), fontSize = (size.value * 0.68f).sp, fontWeight = FontWeight.Bold, lineHeight = (size.value * 0.68f).sp)
    }
}

/** Badge + gap, or nothing. Drop it at the start of a subtitle Row. */
@Composable
fun ExplicitMark(explicit: Boolean, gap: Dp = 5.dp) {
    if (explicit) {
        ExplicitBadge()
        Spacer(Modifier.width(gap))
    }
}
