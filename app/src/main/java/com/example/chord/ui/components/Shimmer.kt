package com.example.chord.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

private const val SHIMMER_PERIOD_MS = 1400
private val ShimmerBase = Color(0xFF1E1E1E)
private val ShimmerHighlight = Color(0xFF2C2C2C)
private val DefaultShape = RoundedCornerShape(8.dp)

/**
 * Grey block with a light band sweeping across it.
 * Sweep is read in the draw phase only, so no recomposition per frame.
 */
@Composable
fun ShimmerBox(modifier: Modifier = Modifier, shape: Shape = DefaultShape) {
    val sweep = rememberInfiniteTransition(label = "shimmer").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(SHIMMER_PERIOD_MS, easing = LinearEasing)),
        label = "sweep",
    )
    Box(
        modifier
            .clip(shape)
            .drawBehind {
                val band = size.width * 0.6f
                val startX = -band + sweep.value * (size.width + band * 2f)
                drawRect(
                    Brush.horizontalGradient(
                        colors = listOf(ShimmerBase, ShimmerHighlight, ShimmerBase),
                        startX = startX,
                        endX = startX + band,
                    ),
                )
            },
    )
}
