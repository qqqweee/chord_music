package com.example.chord.ui.artist

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.chord.ui.theme.ChordTheme

private val Ease = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)

/**
 * Full screen loader shown between "tap artist" and "artist page ready".
 * Pulsing rings + spinning arc + artist name. Swallows touches so nothing behind is tappable.
 */
@Composable
fun ArtistLoadingScreen(name: String, modifier: Modifier = Modifier) {
    val t = rememberInfiniteTransition(label = "artistLoader")
    val spin by t.animateFloat(
        0f, 360f,
        infiniteRepeatable(tween(1100, easing = LinearEasing)),
        label = "spin",
    )
    val pulse by t.animateFloat(
        0f, 1f,
        infiniteRepeatable(tween(1500, easing = Ease)),
        label = "pulse",
    )
    val dots by t.animateFloat(
        0f, 1f,
        infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart),
        label = "dots",
    )

    Box(
        modifier
            .fillMaxSize()
            .background(ChordTheme.bg)
            .pointerInput(Unit) { detectTapGestures { } },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Canvas(Modifier.size(150.dp)) {
                val c = center
                val maxR = size.minDimension / 2f
                // Two rings grow out and fade, offset by half a cycle.
                for (k in 0..1) {
                    val p = (pulse + k * 0.5f) % 1f
                    drawCircle(
                        color = Color.White.copy(alpha = (1f - p) * 0.28f),
                        radius = maxR * (0.45f + 0.55f * p),
                        center = c,
                        style = Stroke(width = 2.dp.toPx()),
                    )
                }
                // Soft core.
                drawCircle(Color.White.copy(alpha = 0.07f), radius = maxR * 0.42f, center = c)
                // Spinning arc.
                val r = maxR * 0.42f
                drawArc(
                    color = Color.White.copy(alpha = 0.9f),
                    startAngle = spin,
                    sweepAngle = 90f,
                    useCenter = false,
                    topLeft = Offset(c.x - r, c.y - r),
                    size = Size(r * 2, r * 2),
                    style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round),
                )
            }
            Spacer(Modifier.height(28.dp))
            Text(
                text = name,
                color = Color.White,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 40.dp),
            )
            Spacer(Modifier.height(8.dp))
            val n = (dots * 3f).toInt().coerceIn(0, 2) + 1
            Text(
                text = "Loading artist" + ".".repeat(n),
                color = Color.White.copy(alpha = 0.55f),
                fontSize = 14.sp,
            )
        }
    }
}