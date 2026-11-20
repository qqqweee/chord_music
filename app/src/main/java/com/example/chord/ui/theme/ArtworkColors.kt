package com.example.chord.ui.theme

import android.util.LruCache
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.drawable.toBitmap
import androidx.palette.graphics.Palette
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.min

/**
 * Colours pulled from a cover. Fields are State so you read them in draw or
 * layer blocks (drawBehind, graphicsLayer) and a colour fade never recomposes.
 *
 *  top    rich, dark: top of a page wash
 *  mid    deeper: middle of a wash, glow behind text
 *  accent bright: buttons, sliders, small highlights
 */
@Stable
class ArtColors(
    val top: State<Color>,
    val mid: State<Color>,
    val accent: State<Color>,
)

private val FallbackTop = Color(0xFF2A2A2E)
private val FallbackMid = Color(0xFF151517)
private val FallbackAccent = Color.White

/** url -> [top, mid, accent] as ARGB ints. Survives scrolling and re-opening a page. */
private val cache = LruCache<String, IntArray>(96)

/**
 * Use on any screen that has a cover:
 *
 *     val colors = rememberArtColors(coverUrl)
 *     Box(Modifier.artGradient(colors, Color(0xFF0B0B0B)))
 *
 * Needs  implementation("androidx.palette:palette-ktx:1.0.0").
 */
@Composable
fun rememberArtColors(url: String?): ArtColors {
    val context = LocalContext.current
    var target by remember(url) { mutableStateOf(url?.let { cache.get(it) }) }

    LaunchedEffect(url) {
        if (url == null || target != null) return@LaunchedEffect
        target = withContext(Dispatchers.Default) { extract(context, url) }
            ?.also { cache.put(url, it) }
    }

    val spec = tween<Color>(500)
    val top = animateColorAsState(target?.let { Color(it[0]) } ?: FallbackTop, spec, label = "artTop")
    val mid = animateColorAsState(target?.let { Color(it[1]) } ?: FallbackMid, spec, label = "artMid")
    val accent = animateColorAsState(target?.let { Color(it[2]) } ?: FallbackAccent, spec, label = "artAccent")
    return remember { ArtColors(top, mid, accent) }
}

/** Vertical wash: top colour, then mid, then [bg]. Colour is read at draw time. */
fun Modifier.artGradient(colors: ArtColors, bg: Color): Modifier = drawBehind {
    drawRect(
        Brush.verticalGradient(
            0f to colors.top.value,
            0.55f to colors.mid.value,
            1f to bg,
        )
    )
}

private suspend fun extract(context: android.content.Context, url: String): IntArray? = runCatching {
    val request = ImageRequest.Builder(context)
        .data(url)
        .size(96)
        .allowHardware(false) // Palette needs a software bitmap
        .build()
    val result = context.imageLoader.execute(request) as? SuccessResult ?: return@runCatching null
    val bitmap = result.drawable.toBitmap(96, 96)
    val palette = Palette.from(bitmap).maximumColorCount(12).generate()
    val swatch = palette.vibrantSwatch
        ?: palette.lightVibrantSwatch
        ?: palette.dominantSwatch
        ?: palette.mutedSwatch
        ?: return@runCatching null
    val base = swatch.rgb
    intArrayOf(
        tone(base, l = 0.30f, maxS = 0.65f),
        tone(base, l = 0.14f, maxS = 0.50f),
        tone(base, l = 0.68f, maxS = 0.85f),
    )
}.getOrNull()

/** Same hue, fixed lightness, capped saturation: always looks good on a dark UI. */
private fun tone(rgb: Int, l: Float, maxS: Float): Int {
    val hsl = FloatArray(3)
    ColorUtils.colorToHSL(rgb, hsl)
    hsl[1] = min(hsl[1], maxS)
    hsl[2] = l
    return ColorUtils.HSLToColor(hsl)
}
