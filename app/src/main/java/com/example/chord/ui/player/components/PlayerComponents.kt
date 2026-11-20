package com.example.chord.ui.player.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
fun PillSlider(
    fraction: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    onValueChangeFinished: (() -> Unit)? = null,
    showThumb: Boolean,
    trackHeight: Dp,
    enabled: Boolean = true,
    activeColor: Color,
    inactiveColor: Color,
) {
    val currentChange by rememberUpdatedState(onValueChange)
    val currentFinished by rememberUpdatedState(onValueChangeFinished)

    Box(
        modifier
            .height(28.dp)
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures { offset ->
                    currentChange(
                        (offset.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f)
                    )
                    currentFinished?.invoke()
                }
            }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        currentChange(
                            (offset.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f)
                        )
                    },
                    onDragEnd = { currentFinished?.invoke() },
                    onDragCancel = { currentFinished?.invoke() },
                    onHorizontalDrag = { change, _ ->
                        change.consume()
                        currentChange(
                            (change.position.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f)
                        )
                    }
                )
            }
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val th = trackHeight.toPx()
            val cy = size.height / 2f
            val f = fraction.coerceIn(0f, 1f)
            val radius = CornerRadius(th / 2f, th / 2f)

            drawRoundRect(
                color = inactiveColor,
                topLeft = Offset(0f, cy - th / 2f),
                size = Size(size.width, th),
                cornerRadius = radius
            )

            if (showThumb) {
                val thumbR = th * 0.78f
                val cx = thumbR + (size.width - 2f * thumbR) * f

                drawRoundRect(
                    color = activeColor,
                    topLeft = Offset(0f, cy - th / 2f),
                    size = Size(cx, th),
                    cornerRadius = radius
                )
                drawCircle(Color.White, radius = thumbR, center = Offset(cx, cy))
            } else {
                val w = size.width * f

                if (w > 0f) {
                    drawRoundRect(
                        color = activeColor,
                        topLeft = Offset(0f, cy - th / 2f),
                        size = Size(w.coerceAtLeast(th), th),
                        cornerRadius = radius
                    )
                }
            }
        }
    }
}

@Composable
fun ChipButton(onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.16f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}

@Composable
fun MoreGlyph(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val r = size.width * 0.085f
        val cy = size.height / 2f
        listOf(0.18f, 0.5f, 0.82f).forEach { x ->
            drawCircle(Color.White, radius = r, center = Offset(size.width * x, cy))
        }
    }
}

@Composable
fun PersonGlyph(modifier: Modifier = Modifier, alpha: Float = 1f) {
    Canvas(modifier) {
        val c = Color.White.copy(alpha = alpha)
        drawCircle(
            c,
            radius = size.width * 0.22f,
            center = Offset(size.width / 2f, size.height * 0.30f)
        )
        drawRoundRect(
            color = c,
            topLeft = Offset(size.width * 0.16f, size.height * 0.58f),
            size = Size(size.width * 0.68f, size.height * 0.34f),
            cornerRadius = CornerRadius(size.width * 0.2f, size.width * 0.2f)
        )
    }
}