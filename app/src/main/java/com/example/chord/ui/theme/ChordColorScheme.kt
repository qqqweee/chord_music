package com.example.chord.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

/**
 * Material colours built from ChordTheme tokens. Menus, dialogs, sheets and text fields
 * take their colours from here, so they follow the app theme (no more white menus).
 * Light theme later: switch to lightColorScheme here, nothing else.
 */
@Composable
fun chordColorScheme(): ColorScheme = darkColorScheme(
    primary = ChordTheme.text, onPrimary = ChordTheme.onAccent, // neutral, no yellow in dialogs/fields
    background = ChordTheme.bg, onBackground = ChordTheme.text,
    surface = ChordTheme.sheet, onSurface = ChordTheme.text,
    surfaceVariant = ChordTheme.surfaceHigh, onSurfaceVariant = ChordTheme.textDim,
    surfaceContainer = ChordTheme.sheet, surfaceContainerHigh = ChordTheme.sheet,
    surfaceContainerHighest = ChordTheme.sheet, surfaceContainerLow = ChordTheme.sheet,
    outline = ChordTheme.hairline, outlineVariant = ChordTheme.hairline,
)

/** Menus (extraSmall) get soft 16dp corners. */
val ChordShapes = Shapes(extraSmall = RoundedCornerShape(16.dp), small = RoundedCornerShape(16.dp))
