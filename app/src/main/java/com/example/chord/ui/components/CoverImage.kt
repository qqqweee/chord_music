
package com.example.chord.ui.components

import android.graphics.Bitmap
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.transform.Transformation

private val CoverPlaceholder = Color(0xFF1E1E1E)

private val YtThumb = Regex(
    """^(https?://i\d*\.ytimg\.com/vi(?:_webp)?/[^/]+/)(?:default|mqdefault|hqdefault|sddefault|hq720|maxresdefault)\.(jpg|jpeg|webp)(\?.*)?$""",
    RegexOption.IGNORE_CASE,
)

private val GoogleSize =
    Regex("""=(?:w\d+-h\d+|s\d+)[^/?#]*""", RegexOption.IGNORE_CASE)

internal fun coverUrls(url: String?, px: Int): List<String> {
    val u = url?.trim()?.takeIf { it.isNotEmpty() } ?: return emptyList()

    YtThumb.matchEntire(u)?.let { match ->
        return listOf(
            "${match.groupValues[1]}hq720.${match.groupValues[2]}",
            u,
        ).distinct()
    }

    if (GoogleSize.containsMatchIn(u)) {
        val size = px.coerceIn(120, 1600)
        return listOf(
            GoogleSize.replace(u, "=w$size-h$size-l90-rj"),
            u,
        ).distinct()
    }

    return listOf(u)
}

private class TrimBars : Transformation {
    override val cacheKey = "TrimBars-v4"

    override suspend fun transform(
        input: Bitmap,
        size: coil.size.Size,
    ): Bitmap {
        val width = input.width
        val height = input.height

        if (width < 16 || height < 16) return input
        if (width.toFloat() / height <= 1.15f) return input

        val pixels = IntArray(width)

        fun isDark(color: Int): Boolean {
            val r = (color shr 16) and 255
            val g = (color shr 8) and 255
            val b = color and 255
            return r <= 28 && g <= 28 && b <= 28
        }

        fun darkRow(y: Int): Boolean {
            input.getPixels(pixels, 0, width, 0, y, width, 1)
            for (x in 0 until width step 6) {
                if (!isDark(pixels[x])) return false
            }
            return true
        }

        val limit = (height * 0.20f).toInt()
        var top = 0
        var bottom = 0

        while (top < limit && darkRow(top)) top++
        while (bottom < limit && darkRow(height - 1 - bottom)) bottom++

        if (top + bottom < height * 0.04f) return input

        val croppedHeight = height - top - bottom
        val cropWidth = minOf(width, croppedHeight)

        if (cropWidth <= 0 || croppedHeight <= 0) return input

        val left = (width - cropWidth) / 2

        return Bitmap.createBitmap(
            input,
            left,
            top,
            cropWidth,
            croppedHeight,
        )
    }
}

@Composable
fun CoverArt(
    url: String?,
    px: Int,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val urls = remember(url, px) { coverUrls(url, px) }

    var index by remember(urls) {
        mutableIntStateOf(0)
    }

    val currentUrl = urls.getOrNull(index)
    val isYouTube = remember(url) {
        url?.contains("ytimg.com", ignoreCase = true) == true
    }

    val request = remember(currentUrl, px, isYouTube) {
        ImageRequest.Builder(context)
            .data(currentUrl)
            .size(px.coerceIn(120, 1600))
            .crossfade(false)
            .memoryCachePolicy(CachePolicy.ENABLED)
            .diskCachePolicy(CachePolicy.ENABLED)
            .apply {
                if (isYouTube) transformations(TrimBars())
            }
            .build()
    }

    AsyncImage(
        model = request,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        onError = {
            if (index < urls.lastIndex) index++
        },
        modifier = modifier,
    )
}

@Composable
fun CoverImage(
    url: String?,
    size: Dp,
    shape: Shape,
    modifier: Modifier = Modifier,
) {
    val px = with(LocalDensity.current) { size.roundToPx() }

    CoverArt(
        url = url,
        px = px,
        modifier = modifier
            .size(size)
            .clip(shape)
            .background(CoverPlaceholder),
    )
}

@Composable
fun NowPlayingOverlay(
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.background(Color.Black.copy(alpha = 0.45f)),
        contentAlignment = Alignment.Center,
    ) {
        if (isPlaying) {
            val transition = rememberInfiniteTransition(label = "playingBars")

            val a by transition.animateFloat(
                0.25f, 1f,
                infiniteRepeatable(
                    tween(520, easing = LinearEasing),
                    RepeatMode.Reverse,
                ),
                label = "barA",
            )

            val b by transition.animateFloat(
                0.25f, 1f,
                infiniteRepeatable(
                    tween(640, easing = LinearEasing),
                    RepeatMode.Reverse,
                ),
                label = "barB",
            )

            val c by transition.animateFloat(
                0.25f, 1f,
                infiniteRepeatable(
                    tween(450, easing = LinearEasing),
                    RepeatMode.Reverse,
                ),
                label = "barC",
            )

            EqualizerBars(listOf(a, b, c))
        } else {
            EqualizerBars(listOf(0.3f, 0.3f, 0.3f))
        }
    }
}

@Composable
private fun EqualizerBars(fractions: List<Float>) {
    Canvas(Modifier.size(16.dp)) {
        val barWidth = size.width / 5f
        val gap = barWidth / 2f

        fractions.forEachIndexed { index, fraction ->
            val height = size.height * fraction

            drawRoundRect(
                color = Color.White,
                topLeft = Offset(
                    index * (barWidth + gap),
                    size.height - height,
                ),
                size = Size(barWidth, height),
                cornerRadius = CornerRadius(barWidth / 2f),
            )
        }
    }
}
