package com.example.chord.ui.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.unit.dp

internal enum class PlayerIcon {
    Back, More, Heart, Previous, Play, Pause, Next, Lyrics, Headphones, Queue, Volume, VolumeLow
}

@Composable
internal fun PlayerGlyph(
    icon: PlayerIcon,
    modifier: Modifier = Modifier.size(24.dp),
    alpha: Float = 1f,
) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val c = Color.White.copy(alpha = alpha)
        val stroke = Stroke(
            width = (size.minDimension * 0.085f).coerceAtLeast(1.5f),
            cap = StrokeCap.Round,
            join = StrokeJoin.Round
        )

        when (icon) {
            PlayerIcon.Back -> {
                val p = Path().apply {
                    moveTo(w * .7f, h * .25f)
                    lineTo(w * .3f, h * .5f)
                    lineTo(w * .7f, h * .75f)
                }
                drawPath(p, c, style = stroke)
            }
            PlayerIcon.More -> listOf(.2f, .5f, .8f).forEach {
                drawCircle(c, w * .07f, Offset(w * it, h * .5f))
            }
            PlayerIcon.Heart -> {
                val p = Path().apply {
                    moveTo(w * .5f, h * .85f)
                    cubicTo(w * .38f, h * .72f, w * .08f, h * .48f, w * .12f, h * .29f)
                    cubicTo(w * .16f, h * .06f, w * .40f, h * .10f, w * .5f, h * .28f)
                    cubicTo(w * .60f, h * .10f, w * .84f, h * .06f, w * .88f, h * .29f)
                    cubicTo(w * .92f, h * .48f, w * .62f, h * .72f, w * .5f, h * .85f)
                    close()
                }
                drawPath(p, c, style = stroke)
            }
            PlayerIcon.Previous, PlayerIcon.Next -> {
                val reverse = icon == PlayerIcon.Previous
                for (i in 0..1) {
                    val x = if (reverse) .72f - i * .34f else .28f + i * .34f
                    val p = Path().apply {
                        if (reverse) {
                            moveTo(w * x, h * .15f)
                            lineTo(w * (x - .25f), h * .5f)
                            lineTo(w * x, h * .85f)
                        } else {
                            moveTo(w * x, h * .15f)
                            lineTo(w * (x + .25f), h * .5f)
                            lineTo(w * x, h * .85f)
                        }
                        close()
                    }
                    drawPath(p, c)
                }
            }
            PlayerIcon.Play -> {
                val p = Path().apply {
                    moveTo(w * .22f, h * .10f)
                    lineTo(w * .86f, h * .5f)
                    lineTo(w * .22f, h * .90f)
                    close()
                }
                drawPath(p, c)
            }
            PlayerIcon.Pause -> {
                drawRoundRect(c, Offset(w * .24f, h * .15f), Size(w * .17f, h * .7f), CornerRadius(w * .04f))
                drawRoundRect(c, Offset(w * .59f, h * .15f), Size(w * .17f, h * .7f), CornerRadius(w * .04f))
            }
            PlayerIcon.Lyrics -> {
                drawRoundRect(c, Offset(w * .12f, h * .12f), Size(w * .76f, h * .68f), CornerRadius(w * .12f), style = stroke)
                drawLine(c, Offset(w * .30f, h * .34f), Offset(w * .70f, h * .34f), stroke.width, StrokeCap.Round)
                drawLine(c, Offset(w * .30f, h * .52f), Offset(w * .62f, h * .52f), stroke.width, StrokeCap.Round)
            }
            PlayerIcon.Headphones -> {
                drawArc(c, 180f, 180f, false, Offset(w * .15f, h * .12f), Size(w * .7f, h * .72f), style = stroke)
                drawRoundRect(c, Offset(w * .12f, h * .48f), Size(w * .20f, h * .36f), CornerRadius(w * .06f))
                drawRoundRect(c, Offset(w * .68f, h * .48f), Size(w * .20f, h * .36f), CornerRadius(w * .06f))
            }
            PlayerIcon.Queue -> {
                for (i in 0..2) {
                    val y = h * (.25f + i * .25f)
                    drawCircle(c, w * .055f, Offset(w * .13f, y))
                    drawLine(c, Offset(w * .29f, y), Offset(w * .88f, y), stroke.width, StrokeCap.Round)
                }
            }
            PlayerIcon.VolumeLow, PlayerIcon.Volume -> {
                val p = Path().apply {
                    moveTo(w * .10f, h * .40f)
                    lineTo(w * .31f, h * .40f)
                    lineTo(w * .53f, h * .20f)
                    lineTo(w * .53f, h * .80f)
                    lineTo(w * .31f, h * .60f)
                    lineTo(w * .10f, h * .60f)
                    close()
                }
                drawPath(p, c)
                if (icon == PlayerIcon.Volume) {
                    drawArc(c, -48f, 96f, false, Offset(w * .35f, h * .29f), Size(w * .48f, h * .42f), style = stroke)
                    drawArc(c, -48f, 96f, false, Offset(w * .25f, h * .14f), Size(w * .70f, h * .72f), style = stroke)
                } else {
                    drawArc(c, -48f, 96f, false, Offset(w * .35f, h * .29f), Size(w * .48f, h * .42f), style = stroke)
                }
            }
        }
    }
}

internal fun formatPlayerTime(milliseconds: Long): String {
    val seconds = milliseconds.coerceAtLeast(0L) / 1000L
    return "%d:%02d".format(seconds / 60L, seconds % 60L)
}
