////package com.example.chord.ui.player
////
////import androidx.compose.animation.Crossfade
////import androidx.compose.animation.core.tween
////import androidx.compose.foundation.Canvas
////import androidx.compose.foundation.background
////import androidx.compose.foundation.layout.Box
////import androidx.compose.foundation.layout.fillMaxSize
////import androidx.compose.runtime.Composable
////import androidx.compose.runtime.LaunchedEffect
////import androidx.compose.runtime.getValue
////import androidx.compose.runtime.mutableStateOf
////import androidx.compose.runtime.remember
////import androidx.compose.runtime.setValue
////import androidx.compose.ui.Modifier
////import androidx.compose.ui.draw.BlurredEdgeTreatment
////import androidx.compose.ui.draw.blur
////import androidx.compose.ui.geometry.Offset
////import androidx.compose.ui.graphics.Brush
////import androidx.compose.ui.graphics.Color
////import androidx.compose.ui.graphics.ImageBitmap
////import androidx.compose.ui.graphics.asImageBitmap
////import androidx.compose.ui.platform.LocalContext
////import androidx.compose.ui.unit.Dp
////import androidx.compose.ui.unit.IntOffset
////import androidx.compose.ui.unit.IntSize
////import androidx.compose.ui.unit.dp
////import androidx.core.graphics.drawable.toBitmap
////import coil.imageLoader
////import coil.request.ImageRequest
////import coil.request.SuccessResult
////import kotlinx.coroutines.Dispatchers
////import kotlinx.coroutines.withContext
////import kotlin.math.max
////import kotlin.math.min
////
/////**
//// * Full-screen artwork-derived backdrop.
//// * A cropped copy of the same cover used by the foreground is enlarged, blurred,
//// * then darkened. The foreground cover can fade into this layer without a hard seam.
//// * This intentionally replaces the old mirrored reflection + high-alpha colour mesh.
//// */
////@Composable
////fun ArtworkMeshBackdrop(
////    artworkUrl: String?,
////    modifier: Modifier = Modifier,
////    fallbackUrls: List<String> = emptyList(),
////    seam: Dp = 0.dp, // Kept for call-site compatibility; the backdrop fills the screen.
////    blurRadius: Dp = 54.dp,
////    darken: Boolean = true,
////) {
////    val context = LocalContext.current
////    var imageBitmap by remember { mutableStateOf<ImageBitmap?>(null) }
////
////    LaunchedEffect(artworkUrl, fallbackUrls) {
////        if (artworkUrl.isNullOrBlank()) {
////            imageBitmap = null
////            return@LaunchedEffect
////        }
////        for (url in listOf(artworkUrl) + fallbackUrls) {
////            val request = ImageRequest.Builder(context)
////                .data(url)
////                .size(512, 512)
////                .allowHardware(false)
////                .build()
////            val result = withContext(Dispatchers.IO) {
////                context.imageLoader.execute(request) as? SuccessResult
////            } ?: continue
////            imageBitmap = withContext(Dispatchers.Default) {
////                result.drawable.toBitmap().asImageBitmap()
////            }
////            return@LaunchedEffect
////        }
////        imageBitmap = null
////    }
////
////    Box(modifier.fillMaxSize().background(Color(0xFF090909))) {
////        Crossfade(
////            targetState = imageBitmap,
////            modifier = Modifier.fillMaxSize(),
////            animationSpec = tween(650),
////            label = "artworkBackdropFade",
////        ) { image ->
////            if (image != null) {
////                Canvas(
////                    Modifier.fillMaxSize().blur(
////                        radius = blurRadius,
////                        edgeTreatment = BlurredEdgeTreatment.Unbounded,
////                    )
////                ) {
////                    // Center-crop the square/rectangular source to cover the whole screen.
////                    val scale = max(size.width / image.width, size.height / image.height)
////                    val srcWidth = min(image.width.toFloat(), size.width / scale)
////                    val srcHeight = min(image.height.toFloat(), size.height / scale)
////                    val srcX = ((image.width - srcWidth) / 2f).toInt()
////                    val srcY = ((image.height - srcHeight) / 2f).toInt()
////                    val srcW = srcWidth.toInt().coerceAtLeast(1)
////                    val srcH = srcHeight.toInt().coerceAtLeast(1)
////                    drawImage(
////                        image = image,
////                        srcOffset = IntOffset(srcX, srcY),
////                        srcSize = IntSize(srcW, srcH),
////                        dstOffset = IntOffset.Zero,
////                        dstSize = IntSize(size.width.toInt(), size.height.toInt()),
////                    )
////                }
////            }
////        }
////
////        if (darken) {
////            Canvas(Modifier.fillMaxSize()) {
////                drawRect(
////                    brush = Brush.verticalGradient(
////                        0f to Color.Black.copy(alpha = 0.22f),
////                        0.42f to Color.Black.copy(alpha = 0.30f),
////                        0.72f to Color.Black.copy(alpha = 0.48f),
////                        1f to Color.Black.copy(alpha = 0.72f),
////                    )
////                )
////                // A subtle neutral veil keeps vivid covers from overpowering controls.
////                drawRect(Color.Black.copy(alpha = 0.08f))
////            }
////        }
////    }
////}
//
//




package com.example.chord.ui.player

import android.graphics.Bitmap
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.Canvas
import androidx.compose.ui.graphics.graphicsLayer
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.random.Random

private const val MESH_GRID = 6
private const val MESH_TEXTURE_SIZE = 32
private const val MESH_SAMPLE = 128
private const val MESH_BLUR_RADIUS = 1
private const val MESH_BLUR_PASSES = 3
private const val MESH_FADE_MS = 900

private val meshCache =
    object : LinkedHashMap<String, ImageBitmap>(0, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: Map.Entry<String, ImageBitmap>
        ): Boolean = size > 64
    }

private val accentCache = LinkedHashMap<String, Color>(64)

/**
 * Artwork-derived mesh backdrop.
 *
 * The artwork is averaged into a 6x6 grid, flipped vertically,
 * rotated below its seam, smoothly interpolated and lightly blurred.
 */
@Composable
fun ArtworkMeshBackdrop(
    artworkUrl: String?,
    modifier: Modifier = Modifier,
    fallbackUrls: List<String> = emptyList(),
    seam: Dp = 0.dp,
    blurRadius: Dp = 54.dp,
    darken: Boolean = true,
    onAccentColor: (Color) -> Unit = {},
    /** 0 = stretch mesh under the hero cover (player page). 1 = cover-crop, no stretch (queue / lyrics). */
    fill: () -> Float = { 0f },
) {
    val context = LocalContext.current
    // Slow drift on queue / lyrics. Read inside graphicsLayer only, so no recomposition.
    val driftT = androidx.compose.animation.core.rememberInfiniteTransition(label = "meshDrift")
    val drift = driftT.animateFloat(
        0f, 1f,
        androidx.compose.animation.core.infiniteRepeatable(
            androidx.compose.animation.core.tween(16000, easing = androidx.compose.animation.core.LinearEasing),
            androidx.compose.animation.core.RepeatMode.Reverse,
        ),
        label = "drift",
    )

    var meshBitmap by remember {
        mutableStateOf<ImageBitmap?>(null)
    }

    LaunchedEffect(artworkUrl, fallbackUrls) {
        if (artworkUrl.isNullOrBlank()) {
            meshBitmap = null
            return@LaunchedEffect
        }

        val urls = (listOf(artworkUrl) + fallbackUrls)
            .filter { it.isNotBlank() }
            .distinct()

        for (url in urls) {
            meshCache[url]?.let { cached ->
                meshBitmap = cached
                accentCache[url]?.let(onAccentColor)
                return@LaunchedEffect
            }

            val request = ImageRequest.Builder(context)
                .data(url)
                .size(512, 512)
                .allowHardware(false)
                .build()

            val result = withContext(Dispatchers.IO) {
                context.imageLoader.execute(request) as? SuccessResult
            } ?: continue

            val sourceBitmap = withContext(Dispatchers.Default) {
                result.drawable.toBitmap()
            }

            val (generatedMesh, accent) = withContext(Dispatchers.Default) {
                createArtworkMesh(sourceBitmap, url.hashCode()) to
                        extractArtworkAccent(sourceBitmap)
            }

            meshCache[url] = generatedMesh
            accentCache[url] = accent

            meshBitmap = generatedMesh
            onAccentColor(accent)

            return@LaunchedEffect
        }

        meshBitmap = null
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF121212))
    ) {
        Crossfade(
            targetState = meshBitmap,
            modifier = Modifier.fillMaxSize(),
            animationSpec = tween(
                durationMillis = MESH_FADE_MS,
                easing = FastOutSlowInEasing
            ),
            label = "artworkMeshFade"
        ) { image ->
            if (image != null) {
                Canvas(
                    Modifier.fillMaxSize().graphicsLayer {
                        val k = fill().coerceIn(0f, 1f)
                        val sc = 1f + 0.12f * k * drift.value
                        scaleX = sc; scaleY = sc
                        translationX = size.width * 0.03f * k * (drift.value - 0.5f)
                        translationY = size.height * 0.02f * k * (0.5f - drift.value)
                    }
                ) {
                    val k = fill().coerceIn(0f, 1f)
                    val w = size.width
                    val h = size.height
                    val seamY = seam.toPx().coerceIn(0f, h)

                    // Hold the artwork's bottom edge above the seam (player page only).
                    if (seamY > 0.5f && k < 1f) {
                        drawImage(
                            image = image,
                            srcOffset = IntOffset.Zero,
                            srcSize = IntSize(image.width, 1),
                            dstOffset = IntOffset.Zero,
                            dstSize = IntSize(w.roundToInt(), seamY.roundToInt()),
                            alpha = 1f - k,
                            filterQuality = FilterQuality.Low
                        )
                    }

                    // Stretched rect (under cover) and cover-crop rect (queue / lyrics). Blend between.
                    val sy = seamY * (1f - k)
                    val sh = (h - sy).coerceAtLeast(1f)
                    val scale = maxOf(w / image.width, h / image.height)
                    val cw = image.width * scale
                    val ch = image.height * scale
                    val dx = ((w - cw) / 2f) * k
                    val dy = sy + (((h - ch) / 2f) - sy) * k
                    val dw = w + (cw - w) * k
                    val dh = sh + (ch - sh) * k

                    drawImage(
                        image = image,
                        srcOffset = IntOffset.Zero,
                        srcSize = IntSize(image.width, image.height),
                        dstOffset = IntOffset(dx.roundToInt(), dy.roundToInt()),
                        dstSize = IntSize(dw.roundToInt().coerceAtLeast(1), dh.roundToInt().coerceAtLeast(1)),
                        filterQuality = FilterQuality.Low
                    )
                }
            }
        }

        if (darken) {
            Canvas(Modifier.fillMaxSize()) {
                drawRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color.Black.copy(alpha = 0.06f + 0.22f * fill()),
                            Color.Black.copy(alpha = 0.30f + 0.28f * fill())
                        )
                    )
                )
            }
        }
    }
}

/**
 * Converts the artwork into a small colour mesh.
 */
private fun createArtworkMesh(
    source: Bitmap,
    seed: Int
): ImageBitmap {
    val width = source.width
    val height = source.height

    val cols = MESH_GRID.coerceAtMost(width)
    val rows = MESH_GRID.coerceAtMost(height)

    val rowStep = (height / MESH_SAMPLE).coerceAtLeast(1)
    val colStep = (width / MESH_SAMPLE).coerceAtLeast(1)

    val cells = rows * cols

    val red = LongArray(cells)
    val green = LongArray(cells)
    val blue = LongArray(cells)
    val count = IntArray(cells)

    val line = IntArray(width)

    var y = 0

    while (y < height) {
        source.getPixels(
            line,
            0,
            width,
            0,
            y,
            width,
            1
        )

        // Flip vertically so the first mesh row is the cover's bottom.
        val rowBase =
            ((height - 1 - y) * rows / height) * cols

        var x = 0

        while (x < width) {
            val cell = rowBase + x * cols / width
            val pixel = line[x]

            red[cell] += (pixel shr 16) and 0xFF
            green[cell] += (pixel shr 8) and 0xFF
            blue[cell] += pixel and 0xFF
            count[cell]++

            x += colStep
        }

        y += rowStep
    }

    val grid = IntArray(cells) { cell ->
        val n = count[cell].coerceAtLeast(1)

        argb(
            (red[cell] / n).toInt(),
            (green[cell] / n).toInt(),
            (blue[cell] / n).toInt()
        ).lifted()
    }

    val rotated = rotateBelowSeam(
        grid = grid,
        cols = cols,
        rows = rows,
        seed = seed
    )

    val texture = resampleMesh(
        grid = rotated,
        cols = cols,
        rows = rows,
        size = MESH_TEXTURE_SIZE
    )

    val scratch = IntArray(texture.size)

    repeat(MESH_BLUR_PASSES) {
        boxBlurPass(
            texture,
            scratch,
            MESH_TEXTURE_SIZE,
            MESH_TEXTURE_SIZE,
            MESH_BLUR_RADIUS,
            horizontal = true
        )

        boxBlurPass(
            scratch,
            texture,
            MESH_TEXTURE_SIZE,
            MESH_TEXTURE_SIZE,
            MESH_BLUR_RADIUS,
            horizontal = false
        )
    }

    return Bitmap.createBitmap(
        texture,
        MESH_TEXTURE_SIZE,
        MESH_TEXTURE_SIZE,
        Bitmap.Config.ARGB_8888
    ).asImageBitmap()
}

/**
 * Rotate and optionally mirror all rows below the seam.
 * The first row remains untouched for a seamless artwork transition.
 */
private fun rotateBelowSeam(
    grid: IntArray,
    cols: Int,
    rows: Int,
    seed: Int
): IntArray {
    if (rows <= 1) return grid

    val random = Random(seed)
    val mirror = random.nextBoolean()
    val shift = random.nextInt(cols)

    val output = grid.copyOf()

    for (row in 1 until rows) {
        val base = row * cols

        for (x in 0 until cols) {
            val sourceX = if (mirror) cols - 1 - x else x

            output[base + x] =
                grid[base + (sourceX + shift) % cols]
        }
    }

    return output
}

/**
 * Smoothly interpolate the small grid into a 32x32 texture.
 */
private fun resampleMesh(
    grid: IntArray,
    cols: Int,
    rows: Int,
    size: Int
): IntArray {
    val output = IntArray(size * size)

    for (ty in 0 until size) {
        val fy = (ty + 0.5f) / size * rows - 0.5f

        val y0 = floor(fy).toInt().coerceIn(0, rows - 1)
        val y1 = (y0 + 1).coerceAtMost(rows - 1)
        val wy = smoothstep(fy - y0)

        for (tx in 0 until size) {
            val fx = (tx + 0.5f) / size * cols - 0.5f

            val x0 = floor(fx).toInt().coerceIn(0, cols - 1)
            val x1 = (x0 + 1).coerceAtMost(cols - 1)
            val wx = smoothstep(fx - x0)

            val top = lerpArgb(
                grid[y0 * cols + x0],
                grid[y0 * cols + x1],
                wx
            )

            val bottom = lerpArgb(
                grid[y1 * cols + x0],
                grid[y1 * cols + x1],
                wx
            )

            output[ty * size + tx] =
                lerpArgb(top, bottom, wy)
        }
    }

    return output
}

private fun smoothstep(t: Float): Float {
    val x = t.coerceIn(0f, 1f)
    return x * x * (3f - 2f * x)
}

private fun lerpArgb(
    from: Int,
    to: Int,
    fraction: Float
): Int {
    if (fraction <= 0f) return from
    if (fraction >= 1f) return to

    fun channel(shift: Int): Int {
        val a = (from shr shift) and 0xFF
        val b = (to shr shift) and 0xFF

        return (
                a + ((b - a) * fraction).roundToInt()
                ).coerceIn(0, 255)
    }

    return argb(
        channel(16),
        channel(8),
        channel(0)
    )
}

private fun argb(
    red: Int,
    green: Int,
    blue: Int
): Int {
    return (0xFF shl 24) or
            (red.coerceIn(0, 255) shl 16) or
            (green.coerceIn(0, 255) shl 8) or
            blue.coerceIn(0, 255)
}

/**
 * Slightly increase saturation and lift near-black pixels,
 * without changing the artwork's colour proportions.
 */
private fun Int.lifted(): Int {
    val r = ((this shr 16) and 0xFF) / 255f
    val g = ((this shr 8) and 0xFF) / 255f
    val b = (this and 0xFF) / 255f

    val maxValue = max(r, max(g, b))
    val minValue = min(r, min(g, b))
    val delta = maxValue - minValue

    var h = 0f
    var s = 0f
    var l = (maxValue + minValue) / 2f

    if (delta != 0f) {
        s = delta / (1f - kotlin.math.abs(2f * l - 1f))

        h = when (maxValue) {
            r -> 60f * (((g - b) / delta) % 6f)
            g -> 60f * (((b - r) / delta) + 2f)
            else -> 60f * (((r - g) / delta) + 4f)
        }

        if (h < 0f) h += 360f
    }

    s = (s * 1.12f).coerceAtMost(1f)
    l = l.coerceAtLeast(0.045f)

    val c = (1f - kotlin.math.abs(2f * l - 1f)) * s
    val x = c * (1f - kotlin.math.abs((h / 60f) % 2f - 1f))
    val m = l - c / 2f

    val (rr, gg, bb) = when {
        h < 60f -> Triple(c, x, 0f)
        h < 120f -> Triple(x, c, 0f)
        h < 180f -> Triple(0f, c, x)
        h < 240f -> Triple(0f, x, c)
        h < 300f -> Triple(x, 0f, c)
        else -> Triple(c, 0f, x)
    }

    return argb(
        ((rr + m) * 255f).roundToInt(),
        ((gg + m) * 255f).roundToInt(),
        ((bb + m) * 255f).roundToInt()
    )
}

/**
 * Three inexpensive box-blur passes on the tiny mesh texture.
 */
private fun boxBlurPass(
    source: IntArray,
    target: IntArray,
    width: Int,
    height: Int,
    radius: Int,
    horizontal: Boolean
) {
    val major = if (horizontal) width else height
    val minor = if (horizontal) height else width
    val window = radius * 2 + 1

    repeat(minor) { fixed ->
        var red = 0
        var green = 0
        var blue = 0

        fun pixel(at: Int): Int {
            val position = at.coerceIn(0, major - 1)

            return if (horizontal) {
                source[fixed * width + position]
            } else {
                source[position * width + fixed]
            }
        }

        for (offset in -radius..radius) {
            val color = pixel(offset)

            red += (color shr 16) and 0xFF
            green += (color shr 8) and 0xFF
            blue += color and 0xFF
        }

        repeat(major) { moving ->
            val index = if (horizontal) {
                fixed * width + moving
            } else {
                moving * width + fixed
            }

            target[index] = argb(
                (red + window / 2) / window,
                (green + window / 2) / window,
                (blue + window / 2) / window
            )

            val leaving = pixel(moving - radius)
            val entering = pixel(moving + radius + 1)

            red +=
                ((entering shr 16) and 0xFF) -
                        ((leaving shr 16) and 0xFF)

            green +=
                ((entering shr 8) and 0xFF) -
                        ((leaving shr 8) and 0xFF)

            blue +=
                (entering and 0xFF) -
                        (leaving and 0xFF)
        }
    }
}

private fun extractArtworkAccent(bitmap: Bitmap): Color {
    val stepX = (bitmap.width / 32).coerceAtLeast(1)
    val stepY = (bitmap.height / 32).coerceAtLeast(1)

    var red = 0.0
    var green = 0.0
    var blue = 0.0
    var totalWeight = 0.0

    for (y in 0 until bitmap.height step stepY) {
        for (x in 0 until bitmap.width step stepX) {
            val pixel = bitmap.getPixel(x, y)

            val r = ((pixel shr 16) and 0xFF) / 255.0
            val g = ((pixel shr 8) and 0xFF) / 255.0
            val b = (pixel and 0xFF) / 255.0

            val max = maxOf(r, g, b)
            val min = minOf(r, g, b)
            val saturation = if (max == 0.0) 0.0 else (max - min) / max

            // Prefer visible, saturated artwork colours.
            val brightnessWeight = (1.0 - kotlin.math.abs(max - 0.58))
                .coerceAtLeast(0.15)

            val weight = (0.25 + saturation) * brightnessWeight

            red += r * weight
            green += g * weight
            blue += b * weight
            totalWeight += weight
        }
    }

    if (totalWeight == 0.0) return Color.White

    return Color(
        red = (red / totalWeight).toFloat().coerceIn(0f, 1f),
        green = (green / totalWeight).toFloat().coerceIn(0f, 1f),
        blue = (blue / totalWeight).toFloat().coerceIn(0f, 1f),
        alpha = 1f
    )
}








//package com.example.chord.ui.player
//
//import androidx.compose.animation.Crossfade
//import androidx.compose.animation.core.tween
//import androidx.compose.foundation.Canvas
//import androidx.compose.foundation.background
//import androidx.compose.foundation.layout.Box
//import androidx.compose.foundation.layout.fillMaxSize
//import androidx.compose.runtime.Composable
//import androidx.compose.runtime.LaunchedEffect
//import androidx.compose.runtime.getValue
//import androidx.compose.runtime.mutableStateOf
//import androidx.compose.runtime.remember
//import androidx.compose.runtime.setValue
//import androidx.compose.ui.Modifier
//import androidx.compose.ui.draw.BlurredEdgeTreatment
//import androidx.compose.ui.draw.blur
//import androidx.compose.ui.geometry.Offset
//import androidx.compose.ui.graphics.Brush
//import androidx.compose.ui.graphics.Color
//import androidx.compose.ui.graphics.ImageBitmap
//import androidx.compose.ui.graphics.asImageBitmap
//import androidx.compose.ui.platform.LocalContext
//import androidx.compose.ui.unit.Dp
//import androidx.compose.ui.unit.IntOffset
//import androidx.compose.ui.unit.IntSize
//import androidx.compose.ui.unit.dp
//import androidx.core.graphics.drawable.toBitmap
//import coil.imageLoader
//import coil.request.ImageRequest
//import coil.request.SuccessResult
//import kotlinx.coroutines.Dispatchers
//import kotlinx.coroutines.withContext
//import kotlin.math.max
//import kotlin.math.min
//
///**
// * Full-screen artwork-derived backdrop.
// * A cropped copy of the same cover used by the foreground is enlarged, blurred,
// * then darkened. The foreground cover can fade into this layer without a hard seam.
// * This intentionally replaces the old mirrored reflection + high-alpha colour mesh.
// */
//@Composable
//fun ArtworkMeshBackdrop(
//    artworkUrl: String?,
//    modifier: Modifier = Modifier,
//    fallbackUrls: List<String> = emptyList(),
//    seam: Dp = 0.dp, // Kept for call-site compatibility; the backdrop fills the screen.
//    blurRadius: Dp = 54.dp,
//    darken: Boolean = true,
//    fill: () -> Float = { 0f },
//) {
//    val context = LocalContext.current
//    var imageBitmap by remember { mutableStateOf<ImageBitmap?>(null) }
//
//    LaunchedEffect(artworkUrl, fallbackUrls) {
//        if (artworkUrl.isNullOrBlank()) {
//            imageBitmap = null
//            return@LaunchedEffect
//        }
//        for (url in listOf(artworkUrl) + fallbackUrls) {
//            val request = ImageRequest.Builder(context)
//                .data(url)
//                .size(512, 512)
//                .allowHardware(false)
//                .build()
//            val result = withContext(Dispatchers.IO) {
//                context.imageLoader.execute(request) as? SuccessResult
//            } ?: continue
//            imageBitmap = withContext(Dispatchers.Default) {
//                result.drawable.toBitmap().asImageBitmap()
//            }
//            return@LaunchedEffect
//        }
//        imageBitmap = null
//    }
//
//    Box(modifier.fillMaxSize().background(Color(0xFF090909))) {
//        Crossfade(
//            targetState = imageBitmap,
//            modifier = Modifier.fillMaxSize(),
//            animationSpec = tween(650),
//            label = "artworkBackdropFade",
//        ) { image ->
//            if (image != null) {
//                Canvas(
//                    Modifier.fillMaxSize().blur(
//                        radius = blurRadius,
//                        edgeTreatment = BlurredEdgeTreatment.Unbounded,
//                    )
//                ) {
//                    // Center-crop the square/rectangular source to cover the whole screen.
//                    val scale = max(size.width / image.width, size.height / image.height)
//                    val srcWidth = min(image.width.toFloat(), size.width / scale)
//                    val srcHeight = min(image.height.toFloat(), size.height / scale)
//                    val srcX = ((image.width - srcWidth) / 2f).toInt()
//                    val srcY = ((image.height - srcHeight) / 2f).toInt()
//                    val srcW = srcWidth.toInt().coerceAtLeast(1)
//                    val srcH = srcHeight.toInt().coerceAtLeast(1)
//                    drawImage(
//                        image = image,
//                        srcOffset = IntOffset(srcX, srcY),
//                        srcSize = IntSize(srcW, srcH),
//                        dstOffset = IntOffset.Zero,
//                        dstSize = IntSize(size.width.toInt(), size.height.toInt()),
//                    )
//                }
//            }
//        }
//
//        if (darken) {
//            Canvas(Modifier.fillMaxSize()) {
//                drawRect(
//                    brush = Brush.verticalGradient(
//                        0f to Color.Black.copy(alpha = 0.22f),
//                        0.42f to Color.Black.copy(alpha = 0.30f),
//                        0.72f to Color.Black.copy(alpha = 0.48f),
//                        1f to Color.Black.copy(alpha = 0.72f),
//                    )
//                )
//                // A subtle neutral veil keeps vivid covers from overpowering controls.
//                drawRect(Color.Black.copy(alpha = 0.08f))
//            }
//        }
//    }
//}






//package com.example.chord.ui.player
//
//import android.graphics.Bitmap
//import androidx.compose.animation.core.LinearEasing
//import androidx.compose.animation.core.RepeatMode
//import androidx.compose.animation.core.infiniteRepeatable
//import androidx.compose.animation.core.rememberInfiniteTransition
//import androidx.compose.animation.core.animateFloat
//import androidx.compose.ui.graphics.toPixelMap
//import kotlin.math.sin
//import kotlin.math.cos
//import androidx.compose.animation.Crossfade
//import androidx.compose.animation.core.FastOutSlowInEasing
//import androidx.compose.animation.core.tween
//import androidx.compose.foundation.Canvas
//import androidx.compose.foundation.background
//import androidx.compose.foundation.layout.Box
//import androidx.compose.foundation.layout.fillMaxSize
//import androidx.compose.runtime.Composable
//import androidx.compose.runtime.LaunchedEffect
//import androidx.compose.runtime.getValue
//import androidx.compose.runtime.mutableStateOf
//import androidx.compose.runtime.remember
//import androidx.compose.runtime.setValue
//import androidx.compose.ui.Modifier
//import androidx.compose.ui.geometry.Offset
//import androidx.compose.ui.graphics.Brush
//import androidx.compose.ui.graphics.Color
//import androidx.compose.ui.graphics.FilterQuality
//import androidx.compose.ui.graphics.ImageBitmap
//import androidx.compose.ui.graphics.asImageBitmap
//import androidx.compose.ui.draw.drawBehind
//import androidx.compose.ui.platform.LocalContext
//import androidx.compose.ui.unit.Dp
//import androidx.compose.ui.unit.IntOffset
//import androidx.compose.ui.unit.IntSize
//import androidx.compose.ui.unit.dp
//import androidx.core.graphics.drawable.toBitmap
//import coil.imageLoader
//import coil.request.ImageRequest
//import coil.request.SuccessResult
//import kotlinx.coroutines.Dispatchers
//import kotlinx.coroutines.withContext
//import kotlin.math.floor
//import kotlin.math.max
//import kotlin.math.min
//import kotlin.math.roundToInt
//import kotlin.random.Random
//
//private const val MESH_GRID = 6
//private const val MESH_TEXTURE_SIZE = 32
//private const val MESH_SAMPLE = 128
//private const val MESH_BLUR_RADIUS = 1
//private const val MESH_BLUR_PASSES = 3
//private const val MESH_FADE_MS = 900
//
//private val meshCache =
//    object : LinkedHashMap<String, ImageBitmap>(0, 0.75f, true) {
//        override fun removeEldestEntry(
//            eldest: Map.Entry<String, ImageBitmap>
//        ): Boolean = size > 64
//    }
//
//private val accentCache = LinkedHashMap<String, Color>(64)
//
///**
// * Artwork-derived mesh backdrop.
// *
// * The artwork is averaged into a 6x6 grid, flipped vertically,
// * rotated below its seam, smoothly interpolated and lightly blurred.
// */
//@Composable
//fun ArtworkMeshBackdrop(
//    artworkUrl: String?,
//    modifier: Modifier = Modifier,
//    fallbackUrls: List<String> = emptyList(),
//    seam: Dp = 0.dp,
//    blurRadius: Dp = 54.dp,
//    darken: Boolean = true,
//    onAccentColor: (Color) -> Unit = {},
//    fill: () -> Float = { 0f },
//) {
//    val context = LocalContext.current
//
//    var meshBitmap by remember {
//        mutableStateOf<ImageBitmap?>(null)
//    }
//
//    LaunchedEffect(artworkUrl, fallbackUrls) {
//        if (artworkUrl.isNullOrBlank()) {
//            meshBitmap = null
//            return@LaunchedEffect
//        }
//
//        val urls = (listOf(artworkUrl) + fallbackUrls)
//            .filter { it.isNotBlank() }
//            .distinct()
//
//        for (url in urls) {
//            meshCache[url]?.let { cached ->
//                meshBitmap = cached
//                accentCache[url]?.let(onAccentColor)
//                return@LaunchedEffect
//            }
//
//            val request = ImageRequest.Builder(context)
//                .data(url)
//                .size(512, 512)
//                .allowHardware(false)
//                .build()
//
//            val result = withContext(Dispatchers.IO) {
//                context.imageLoader.execute(request) as? SuccessResult
//            } ?: continue
//
//            val sourceBitmap = withContext(Dispatchers.Default) {
//                result.drawable.toBitmap()
//            }
//
//            val (generatedMesh, accent) = withContext(Dispatchers.Default) {
//                createArtworkMesh(sourceBitmap, url.hashCode()) to
//                        extractArtworkAccent(sourceBitmap)
//            }
//
//            meshCache[url] = generatedMesh
//            accentCache[url] = accent
//
//            meshBitmap = generatedMesh
//            onAccentColor(accent)
//
//            return@LaunchedEffect
//        }
//
//        meshBitmap = null
//    }
//
//    Box(
//        modifier = modifier
//            .fillMaxSize()
//            .background(Color(0xFF121212))
//    ) {
//        Crossfade(
//            targetState = meshBitmap,
//            modifier = Modifier.fillMaxSize(),
//            animationSpec = tween(
//                durationMillis = MESH_FADE_MS,
//                easing = FastOutSlowInEasing
//            ),
//            label = "artworkMeshFade"
//        ) { image ->
//            if (image != null) {
//                Canvas(Modifier.fillMaxSize()) {
//                    val seamY = seam.toPx().coerceIn(0f, size.height).roundToInt()
//                    val width = size.width.roundToInt()
//                    val height = size.height.roundToInt()
//
//                    // Hold the artwork's bottom edge above the seam.
//                    if (seamY > 0) {
//                        drawImage(
//                            image = image,
//                            srcOffset = IntOffset.Zero,
//                            srcSize = IntSize(image.width, 1),
//                            dstOffset = IntOffset.Zero,
//                            dstSize = IntSize(width, seamY),
//                            filterQuality = FilterQuality.Low
//                        )
//                    }
//
//                    // Mesh below the artwork. Crop to fit, never stretch.
//                    val dstW = width
//                    val dstH = (height - seamY).coerceAtLeast(1)
//                    val dstAspect = dstW.toFloat() / dstH
//                    val srcAspect = image.width.toFloat() / image.height
//                    val srcW: Int
//                    val srcH: Int
//                    if (dstAspect < srcAspect) {
//                        srcH = image.height
//                        srcW = (image.height * dstAspect).roundToInt().coerceIn(1, image.width)
//                    } else {
//                        srcW = image.width
//                        srcH = (image.width / dstAspect).roundToInt().coerceIn(1, image.height)
//                    }
//                    drawImage(
//                        image = image,
//                        srcOffset = IntOffset((image.width - srcW) / 2, (image.height - srcH) / 2),
//                        srcSize = IntSize(srcW, srcH),
//                        dstOffset = IntOffset(0, seamY),
//                        dstSize = IntSize(dstW, dstH),
//                        filterQuality = FilterQuality.Low
//                    )
//                }
//            }
//        }
//
//        if (darken) {
//            Canvas(Modifier.fillMaxSize()) {
//                drawRect(
//                    brush = Brush.verticalGradient(
//                        colors = listOf(
//                            Color.Black.copy(alpha = 0.06f),
//                            Color.Black.copy(alpha = 0.30f)
//                        )
//                    )
//                )
//            }
//        }
//    }
//}
//
///**
// * Converts the artwork into a small colour mesh.
// */
//private fun createArtworkMesh(
//    source: Bitmap,
//    seed: Int
//): ImageBitmap {
//    val width = source.width
//    val height = source.height
//
//    val cols = MESH_GRID.coerceAtMost(width)
//    val rows = MESH_GRID.coerceAtMost(height)
//
//    val rowStep = (height / MESH_SAMPLE).coerceAtLeast(1)
//    val colStep = (width / MESH_SAMPLE).coerceAtLeast(1)
//
//    val cells = rows * cols
//
//    val red = LongArray(cells)
//    val green = LongArray(cells)
//    val blue = LongArray(cells)
//    val count = IntArray(cells)
//
//    val line = IntArray(width)
//
//    var y = 0
//
//    while (y < height) {
//        source.getPixels(
//            line,
//            0,
//            width,
//            0,
//            y,
//            width,
//            1
//        )
//
//        // Flip vertically so the first mesh row is the cover's bottom.
//        val rowBase =
//            ((height - 1 - y) * rows / height) * cols
//
//        var x = 0
//
//        while (x < width) {
//            val cell = rowBase + x * cols / width
//            val pixel = line[x]
//
//            red[cell] += (pixel shr 16) and 0xFF
//            green[cell] += (pixel shr 8) and 0xFF
//            blue[cell] += pixel and 0xFF
//            count[cell]++
//
//            x += colStep
//        }
//
//        y += rowStep
//    }
//
//    val grid = IntArray(cells) { cell ->
//        val n = count[cell].coerceAtLeast(1)
//
//        argb(
//            (red[cell] / n).toInt(),
//            (green[cell] / n).toInt(),
//            (blue[cell] / n).toInt()
//        ).lifted()
//    }
//
//    val rotated = rotateBelowSeam(
//        grid = grid,
//        cols = cols,
//        rows = rows,
//        seed = seed
//    )
//
//    val texture = resampleMesh(
//        grid = rotated,
//        cols = cols,
//        rows = rows,
//        size = MESH_TEXTURE_SIZE
//    )
//
//    val scratch = IntArray(texture.size)
//
//    repeat(MESH_BLUR_PASSES) {
//        boxBlurPass(
//            texture,
//            scratch,
//            MESH_TEXTURE_SIZE,
//            MESH_TEXTURE_SIZE,
//            MESH_BLUR_RADIUS,
//            horizontal = true
//        )
//
//        boxBlurPass(
//            scratch,
//            texture,
//            MESH_TEXTURE_SIZE,
//            MESH_TEXTURE_SIZE,
//            MESH_BLUR_RADIUS,
//            horizontal = false
//        )
//    }
//
//    return Bitmap.createBitmap(
//        texture,
//        MESH_TEXTURE_SIZE,
//        MESH_TEXTURE_SIZE,
//        Bitmap.Config.ARGB_8888
//    ).asImageBitmap()
//}
//
///**
// * Rotate and optionally mirror all rows below the seam.
// * The first row remains untouched for a seamless artwork transition.
// */
//private fun rotateBelowSeam(
//    grid: IntArray,
//    cols: Int,
//    rows: Int,
//    seed: Int
//): IntArray {
//    if (rows <= 1) return grid
//
//    val random = Random(seed)
//    val mirror = random.nextBoolean()
//    val shift = random.nextInt(cols)
//
//    val output = grid.copyOf()
//
//    for (row in 1 until rows) {
//        val base = row * cols
//
//        for (x in 0 until cols) {
//            val sourceX = if (mirror) cols - 1 - x else x
//
//            output[base + x] =
//                grid[base + (sourceX + shift) % cols]
//        }
//    }
//
//    return output
//}
//
///**
// * Smoothly interpolate the small grid into a 32x32 texture.
// */
//private fun resampleMesh(
//    grid: IntArray,
//    cols: Int,
//    rows: Int,
//    size: Int
//): IntArray {
//    val output = IntArray(size * size)
//
//    for (ty in 0 until size) {
//        val fy = (ty + 0.5f) / size * rows - 0.5f
//
//        val y0 = floor(fy).toInt().coerceIn(0, rows - 1)
//        val y1 = (y0 + 1).coerceAtMost(rows - 1)
//        val wy = smoothstep(fy - y0)
//
//        for (tx in 0 until size) {
//            val fx = (tx + 0.5f) / size * cols - 0.5f
//
//            val x0 = floor(fx).toInt().coerceIn(0, cols - 1)
//            val x1 = (x0 + 1).coerceAtMost(cols - 1)
//            val wx = smoothstep(fx - x0)
//
//            val top = lerpArgb(
//                grid[y0 * cols + x0],
//                grid[y0 * cols + x1],
//                wx
//            )
//
//            val bottom = lerpArgb(
//                grid[y1 * cols + x0],
//                grid[y1 * cols + x1],
//                wx
//            )
//
//            output[ty * size + tx] =
//                lerpArgb(top, bottom, wy)
//        }
//    }
//
//    return output
//}
//
//private fun smoothstep(t: Float): Float {
//    val x = t.coerceIn(0f, 1f)
//    return x * x * (3f - 2f * x)
//}
//
//private fun lerpArgb(
//    from: Int,
//    to: Int,
//    fraction: Float
//): Int {
//    if (fraction <= 0f) return from
//    if (fraction >= 1f) return to
//
//    fun channel(shift: Int): Int {
//        val a = (from shr shift) and 0xFF
//        val b = (to shr shift) and 0xFF
//
//        return (
//                a + ((b - a) * fraction).roundToInt()
//                ).coerceIn(0, 255)
//    }
//
//    return argb(
//        channel(16),
//        channel(8),
//        channel(0)
//    )
//}
//
//private fun argb(
//    red: Int,
//    green: Int,
//    blue: Int
//): Int {
//    return (0xFF shl 24) or
//            (red.coerceIn(0, 255) shl 16) or
//            (green.coerceIn(0, 255) shl 8) or
//            blue.coerceIn(0, 255)
//}
//
///**
// * Slightly increase saturation and lift near-black pixels,
// * without changing the artwork's colour proportions.
// */
//private fun Int.lifted(): Int {
//    val r = ((this shr 16) and 0xFF) / 255f
//    val g = ((this shr 8) and 0xFF) / 255f
//    val b = (this and 0xFF) / 255f
//
//    val maxValue = max(r, max(g, b))
//    val minValue = min(r, min(g, b))
//    val delta = maxValue - minValue
//
//    var h = 0f
//    var s = 0f
//    var l = (maxValue + minValue) / 2f
//
//    if (delta != 0f) {
//        s = delta / (1f - kotlin.math.abs(2f * l - 1f))
//
//        h = when (maxValue) {
//            r -> 60f * (((g - b) / delta) % 6f)
//            g -> 60f * (((b - r) / delta) + 2f)
//            else -> 60f * (((r - g) / delta) + 4f)
//        }
//
//        if (h < 0f) h += 360f
//    }
//
//    s = (s * 1.12f).coerceAtMost(1f)
//    l = l.coerceAtLeast(0.045f)
//
//    val c = (1f - kotlin.math.abs(2f * l - 1f)) * s
//    val x = c * (1f - kotlin.math.abs((h / 60f) % 2f - 1f))
//    val m = l - c / 2f
//
//    val (rr, gg, bb) = when {
//        h < 60f -> Triple(c, x, 0f)
//        h < 120f -> Triple(x, c, 0f)
//        h < 180f -> Triple(0f, c, x)
//        h < 240f -> Triple(0f, x, c)
//        h < 300f -> Triple(x, 0f, c)
//        else -> Triple(c, 0f, x)
//    }
//
//    return argb(
//        ((rr + m) * 255f).roundToInt(),
//        ((gg + m) * 255f).roundToInt(),
//        ((bb + m) * 255f).roundToInt()
//    )
//}
//
///**
// * Three inexpensive box-blur passes on the tiny mesh texture.
// */
//private fun boxBlurPass(
//    source: IntArray,
//    target: IntArray,
//    width: Int,
//    height: Int,
//    radius: Int,
//    horizontal: Boolean
//) {
//    val major = if (horizontal) width else height
//    val minor = if (horizontal) height else width
//    val window = radius * 2 + 1
//
//    repeat(minor) { fixed ->
//        var red = 0
//        var green = 0
//        var blue = 0
//
//        fun pixel(at: Int): Int {
//            val position = at.coerceIn(0, major - 1)
//
//            return if (horizontal) {
//                source[fixed * width + position]
//            } else {
//                source[position * width + fixed]
//            }
//        }
//
//        for (offset in -radius..radius) {
//            val color = pixel(offset)
//
//            red += (color shr 16) and 0xFF
//            green += (color shr 8) and 0xFF
//            blue += color and 0xFF
//        }
//
//        repeat(major) { moving ->
//            val index = if (horizontal) {
//                fixed * width + moving
//            } else {
//                moving * width + fixed
//            }
//
//            target[index] = argb(
//                (red + window / 2) / window,
//                (green + window / 2) / window,
//                (blue + window / 2) / window
//            )
//
//            val leaving = pixel(moving - radius)
//            val entering = pixel(moving + radius + 1)
//
//            red +=
//                ((entering shr 16) and 0xFF) -
//                        ((leaving shr 16) and 0xFF)
//
//            green +=
//                ((entering shr 8) and 0xFF) -
//                        ((leaving shr 8) and 0xFF)
//
//            blue +=
//                (entering and 0xFF) -
//                        (leaving and 0xFF)
//        }
//    }
//}
//
//private fun extractArtworkAccent(bitmap: Bitmap): Color {
//    val stepX = (bitmap.width / 32).coerceAtLeast(1)
//    val stepY = (bitmap.height / 32).coerceAtLeast(1)
//
//    var red = 0.0
//    var green = 0.0
//    var blue = 0.0
//    var totalWeight = 0.0
//
//    for (y in 0 until bitmap.height step stepY) {
//        for (x in 0 until bitmap.width step stepX) {
//            val pixel = bitmap.getPixel(x, y)
//
//            val r = ((pixel shr 16) and 0xFF) / 255.0
//            val g = ((pixel shr 8) and 0xFF) / 255.0
//            val b = (pixel and 0xFF) / 255.0
//
//            val max = maxOf(r, g, b)
//            val min = minOf(r, g, b)
//            val saturation = if (max == 0.0) 0.0 else (max - min) / max
//
//            // Prefer visible, saturated artwork colours.
//            val brightnessWeight = (1.0 - kotlin.math.abs(max - 0.58))
//                .coerceAtLeast(0.15)
//
//            val weight = (0.25 + saturation) * brightnessWeight
//
//            red += r * weight
//            green += g * weight
//            blue += b * weight
//            totalWeight += weight
//        }
//    }
//
//    if (totalWeight == 0.0) return Color.White
//
//    return Color(
//        red = (red / totalWeight).toFloat().coerceIn(0f, 1f),
//        green = (green / totalWeight).toFloat().coerceIn(0f, 1f),
//        blue = (blue / totalWeight).toFloat().coerceIn(0f, 1f),
//        alpha = 1f
//    )
//}
