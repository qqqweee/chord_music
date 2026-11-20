package com.example.chord.ui.theme

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Home type scale. Tight, compact, SemiBold for titles, Medium for small text. No bold.
 *
 * Font comes from `ChordFont`, which already exists in your theme package.
 * Change the font there and every Home text follows.
 */
object ChordType {
    /** Greeting at the top of Home. */
    val Display = TextStyle(fontFamily = ChordFont, fontSize = 30.sp, lineHeight = 34.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.9).sp)
    /** Title on the big hero card. */
    val Hero = TextStyle(fontFamily = ChordFont, fontSize = 26.sp, lineHeight = 29.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.8).sp)
    /** Shelf title. */
    val Section = TextStyle(fontFamily = ChordFont, fontSize = 21.sp, lineHeight = 25.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp)
    /** Small line above a shelf title. Use with .uppercase(). */
    val Eyebrow = TextStyle(fontFamily = ChordFont, fontSize = 10.5.sp, lineHeight = 14.sp, fontWeight = FontWeight.Medium, letterSpacing = 1.0.sp)
    /** Card and tile title. */
    val CardTitle = TextStyle(fontFamily = ChordFont, fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium, letterSpacing = (-0.15).sp)
    /** Card subtitle. */
    val CardSub = TextStyle(fontFamily = ChordFont, fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.sp)
    /** Buttons and pills. */
    val Label = TextStyle(fontFamily = ChordFont, fontSize = 13.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.1.sp)
}