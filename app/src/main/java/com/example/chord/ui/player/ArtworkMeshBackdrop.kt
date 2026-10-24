package com.example.chord.ui.player

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.random.Random

private const val GRID_SIZE = 6
private const val TEXTURE_SIZE = 32
private const val SOURCE_SIZE = 120

private val fallbackColor = Color(0xFF111114)

/**
 * Reusable artwork-derived background inspired by BitChord's mesh backdrop.
 *
 * It samples the cover into a small colour grid, flips the grid so its first
 * row represents the cover's bottom edge, and smooths it into a tiny texture.
 * The original artwork remains a separate, sharp foreground image.
 *
 * [seam] is the vertical position of the artwork's bottom edge in this
 * composable's coordinate space. Keep it at 0.dp when there is no cover above
 * this background.
 */
@Composable
fun ArtworkMeshBackdrop(
    artworkUrl: String?,
    modifier: Modifier = Modifier,
    seam: Dp = 0.dp,
    blurRadius: Dp = 18.dp,
    darken: Boolean = true,
) {
    val context = LocalContext.current
    var mesh by remember(artworkUrl) { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(artworkUrl) {
        mesh = null
        val url = artworkUrl?.takeIf(String::isNotBlank) ?: return@LaunchedEffect
        val decoded = withContext(Dispatchers.IO) {
            runCatching {
                val request = ImageRequest.Builder(context)
                    .data(url)
                    .size(SOURCE_SIZE, SOURCE_SIZE)
                    .allowHardware(false)
                    .build()
                val result = context.imageLoader.execute(request)
                val drawable = (result as? SuccessResult)?.drawable ?: return@runCatching null
                drawable.toBitmap(SOURCE_SIZE, SOURCE_SIZE).asImageBitmap()
            }.getOrNull()
        } ?: return@LaunchedEffect

        mesh = withContext(Dispatchers.Default) { buildMesh(decoded) }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(fallbackColor)
    ) {
        Canvas(
            Modifier
                .fillMaxSize()
                .then(if (blurRadius > 0.dp) Modifier.blur(blurRadius) else Modifier)
        ) {
            val current = mesh
            if (current != null) {
                drawAnchoredMesh(current, seam.toPx())
            }
        }

        if (darken) {
            Canvas(Modifier.fillMaxSize()) {
                drawRect(
                    Brush.verticalGradient(
                        0f to Color.Black.copy(alpha = 0.06f),
                        0.52f to Color.Black.copy(alpha = 0.24f),
                        1f to Color.Black.copy(alpha = 0.48f)
                    )
                )
            }
        }
    }
}

private fun DrawScope.drawAnchoredMesh(image: ImageBitmap, seamY: Float) {
    val width = size.width.roundToInt().coerceAtLeast(1)
    val height = size.height.roundToInt().coerceAtLeast(1)
    val seam = seamY.roundToInt().coerceIn(0, height)

    if (seam > 0) {
        drawImage(
            image = image,
            srcOffset = IntOffset(0, 0),
            srcSize = IntSize(image.width, 1),
            dstOffset = IntOffset(0, 0),
            dstSize = IntSize(width, seam),
            filterQuality = FilterQuality.Medium
        )
    }
    if (seam < height) {
        drawImage(
            image = image,
            srcOffset = IntOffset(0, 0),
            srcSize = IntSize(image.width, image.height),
            dstOffset = IntOffset(0, seam),
            dstSize = IntSize(width, height - seam),
            filterQuality = FilterQuality.Medium
        )
    }
}

private fun buildMesh(source: ImageBitmap): ImageBitmap {
    val width = source.width.coerceAtLeast(1)
    val height = source.height.coerceAtLeast(1)
    val pixels = IntArray(width * height)
    source.readPixels(pixels, 0, 0, width, height)

    val grid = IntArray(GRID_SIZE * GRID_SIZE)
    for (gy in 0 until GRID_SIZE) {
        for (gx in 0 until GRID_SIZE) {
            val left = gx * width / GRID_SIZE
            val right = ((gx + 1) * width / GRID_SIZE).coerceAtLeast(left + 1).coerceAtMost(width)
            val top = gy * height / GRID_SIZE
            val bottom = ((gy + 1) * height / GRID_SIZE).coerceAtLeast(top + 1).coerceAtMost(height)
            var red = 0L
            var green = 0L
            var blue = 0L
            var count = 0L

            for (y in top until bottom) {
                for (x in left until right) {
                    val pixel = pixels[y * width + x]
                    red += (pixel ushr 16) and 0xFF
                    green += (pixel ushr 8) and 0xFF
                    blue += pixel and 0xFF
                    count++
                }
            }

            grid[gy * GRID_SIZE + gx] = android.graphics.Color.rgb(
                (red / count.coerceAtLeast(1)).toInt(),
                (green / count.coerceAtLeast(1)).toInt(),
                (blue / count.coerceAtLeast(1)).toInt()
            )
        }
    }

    // Put the source artwork's bottom edge at row zero.
    val flipped = IntArray(grid.size)
    for (y in 0 until GRID_SIZE) {
        for (x in 0 until GRID_SIZE) {
            flipped[y * GRID_SIZE + x] = grid[(GRID_SIZE - 1 - y) * GRID_SIZE + x]
        }
    }

    // Keep the seam row untouched; shift the rows below it to avoid a mirror effect.
    val transformed = flipped.copyOf()
    val random = Random(width * 31 + height)
    val shift = random.nextInt(GRID_SIZE)
    val mirror = random.nextBoolean()
    for (y in 1 until GRID_SIZE) {
        for (x in 0 until GRID_SIZE) {
            val sourceX = if (mirror) GRID_SIZE - 1 - x else x
            transformed[y * GRID_SIZE + x] =
                flipped[y * GRID_SIZE + (sourceX + shift) % GRID_SIZE]
        }
    }

    val texture = IntArray(TEXTURE_SIZE * TEXTURE_SIZE)
    for (y in 0 until TEXTURE_SIZE) {
        val fy = (y + 0.5f) / TEXTURE_SIZE * GRID_SIZE - 0.5f
        val y0 = floor(fy).toInt().coerceIn(0, GRID_SIZE - 1)
        val y1 = (y0 + 1).coerceAtMost(GRID_SIZE - 1)
        val wy = smoothStep(fy - y0)

        for (x in 0 until TEXTURE_SIZE) {
            val fx = (x + 0.5f) / TEXTURE_SIZE * GRID_SIZE - 0.5f
            val x0 = floor(fx).toInt().coerceIn(0, GRID_SIZE - 1)
            val x1 = (x0 + 1).coerceAtMost(GRID_SIZE - 1)
            val wx = smoothStep(fx - x0)
            val top = mixArgb(
                transformed[y0 * GRID_SIZE + x0],
                transformed[y0 * GRID_SIZE + x1],
                wx
            )
            val bottom = mixArgb(
                transformed[y1 * GRID_SIZE + x0],
                transformed[y1 * GRID_SIZE + x1],
                wx
            )
            texture[y * TEXTURE_SIZE + x] = mixArgb(top, bottom, wy)
        }
    }

    return Bitmap.createBitmap(
        texture,
        TEXTURE_SIZE,
        TEXTURE_SIZE,
        Bitmap.Config.ARGB_8888
    ).asImageBitmap()
}

private fun smoothStep(value: Float): Float {
    val t = value.coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

private fun mixArgb(from: Int, to: Int, fraction: Float): Int {
    val t = fraction.coerceIn(0f, 1f)
    fun channel(shift: Int): Int {
        val a = (from ushr shift) and 0xFF
        val b = (to ushr shift) and 0xFF
        return (a + (b - a) * t).roundToInt().coerceIn(0, 255)
    }
    return android.graphics.Color.rgb(channel(16), channel(8), channel(0))
}
