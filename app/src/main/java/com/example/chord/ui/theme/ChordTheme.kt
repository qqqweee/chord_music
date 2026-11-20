package com.example.chord.ui.theme

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color

/** Page colours the user can pick in Settings. Add a line here to add a theme. */
enum class ThemeMode(val label: String, val bg: Color) {
    Dark("Dark", Color(0xFF0B0B0B)),
    Amoled("Black", Color(0xFF000000)),
    Graphite("Graphite", Color(0xFF17171A)),
}

/**
 * Live theme values. Reading them inside a composable makes the screen redraw
 * when the user changes a setting, so use them anywhere:
 *
 *   Modifier.background(ChordTheme.bg)
 *
 * Saved by AppSettings. Do not write to these directly, call AppSettings.setXxx().
 */
object ChordTheme {
    var mode by mutableStateOf(ThemeMode.Dark)
    var coverTint by mutableStateOf(false)

    val bg: Color get() = mode.bg

    // Semantic colours. New UI must use these, never Color.White / Color.Black directly.
    // Light theme later = change only these getters.
    val text: Color get() = Color.White
    val textDim: Color get() = Color.White.copy(alpha = 0.60f)
    val surface: Color get() = Color.White.copy(alpha = 0.08f)
    val surfaceHigh: Color get() = Color.White.copy(alpha = 0.14f)
    val sheet: Color get() = Color(0xFF111010)
    val hairline: Color get() = Color.White.copy(alpha = 0.10f)
    val onAccent: Color get() = Color.Black
    val accent: Color get() = Color(0xFFECE8E8)
}