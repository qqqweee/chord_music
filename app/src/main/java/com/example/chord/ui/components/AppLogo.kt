package com.example.chord.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

private val ChordCream = Color(0xFFEFE8D8)
private val ChordInk = Color(0xFF1A1916)
private val ChordCoral = Color(0xFFC4472E)

/**
 * Chord logo, cream version.
 * Three bits stacked on one stem make a chord. Hand-cut flag on top.
 * Top bit is coral. Flat colour, no gradient.
 * Every screen that shows the logo calls this one function.
 */
@Composable
fun AppLogo(modifier: Modifier = Modifier, logoSize: Dp = 40.dp) {
    Canvas(modifier.size(logoSize)) {
        val w = size.width
        drawRoundRect(ChordCream, cornerRadius = CornerRadius(w * 0.223f))

        // Mark is drawn on a 512 grid, then scaled to fit.
        scale(w / 512f, pivot = Offset.Zero) {
            val flag = Path().apply {
                moveTo(244f, 410f)
                lineTo(244f, 128f)
                cubicTo(244f, 114f, 256f, 106f, 270f, 111f)
                cubicTo(330f, 130f, 376f, 170f, 372f, 238f)
                cubicTo(370f, 276f, 350f, 298f, 328f, 306f)
                cubicTo(350f, 268f, 344f, 220f, 276f, 192f)
                lineTo(276f, 410f)
                close()
            }
            drawPath(flag, ChordInk)

            fun bit(y: Float, cy: Float, color: Color) {
                rotate(-18f, pivot = Offset(204f, cy)) {
                    drawRoundRect(
                        color,
                        topLeft = Offset(162f, y),
                        size = Size(84f, 64f),
                        cornerRadius = CornerRadius(18f),
                    )
                }
            }
            bit(348f, 380f, ChordInk)
            bit(268f, 300f, ChordInk)
            bit(188f, 220f, ChordCoral)
        }
    }
}
