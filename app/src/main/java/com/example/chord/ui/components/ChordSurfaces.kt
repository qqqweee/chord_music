package com.example.chord.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.chord.ui.theme.ChordTheme

/** Soft glass card: faint fill + thin light border. Use for every card. */
fun Modifier.chordCard(radius: Dp = 16.dp, fill: Color = ChordTheme.surface): Modifier {
    val shape = RoundedCornerShape(radius)
    return clip(shape).background(fill).border(0.5.dp, ChordTheme.hairline, shape)
}
