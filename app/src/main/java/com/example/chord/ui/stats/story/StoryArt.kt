package com.example.chord.ui.stats.story

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.chord.ui.components.AppLogo
import com.example.chord.ui.theme.ChordFont
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/** Chord logo colours. Every story colour comes from here. */
internal object StoryColors {
    val Cream = Color(0xFFEFE8D8)
    val Ink = Color(0xFF1A1916)
    val Coral = Color(0xFFC4472E)
    /** Brighter coral. For text that sits on ink. */
    val Hot = Color(0xFFE8603C)
    val Deep = Color(0xFF5A1B10)
}

/** Poster type: heavy, tilted, tight. Think handmade gig poster. */
internal val PosterStyle = TextStyle(
    fontFamily = ChordFont,
    fontWeight = FontWeight.Bold,
    fontStyle = FontStyle.Italic,
    letterSpacing = (-2).sp,
)

/* ---------- motion helpers ---------- */

/** 0 -> 1 with a springy overshoot, after [delayMs]. */
@Composable
internal fun rememberReveal(delayMs: Int = 0): State<Float> {
    val a = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(delayMs.toLong())
        a.animateTo(1f, spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessLow))
    }
    return a.asState()
}

@Composable
internal fun rememberCountUp(target: Long, delayMs: Int = 0, ms: Int = 1800): Long {
    val a = remember(target) { Animatable(0f) }
    LaunchedEffect(target) {
        delay(delayMs.toLong())
        a.animateTo(1f, tween(ms, easing = CubicBezierEasing(0.16f, 1f, 0.3f, 1f)))
    }
    return (target * a.value).toLong()
}

/** Slide up, un-tilt and fade in as [p] goes 0 -> 1. Reads [p] in the draw phase only. */
internal fun Modifier.pop(p: State<Float>, rise: Dp = 48.dp, slide: Dp = 0.dp, tilt: Float = 0f): Modifier =
    graphicsLayer {
        val v = p.value
        alpha = v.coerceIn(0f, 1f)
        translationY = (1f - v) * rise.toPx()
        translationX = (1f - v) * slide.toPx()
        rotationZ = tilt + (1f - v) * 4f
    }

/* ---------- paper ---------- */

/** Speckles and soft smudges, so flat colour feels like printed paper. */
internal fun Modifier.paperGrain(onInk: Boolean, seed: Int = 1): Modifier = drawWithCache {
    val r = Random(seed)
    val pts = List(900) { Offset(r.nextFloat() * size.width, r.nextFloat() * size.height) }
    val blots = List(16) {
        Triple(Offset(r.nextFloat() * size.width, r.nextFloat() * size.height), size.width * (0.05f + r.nextFloat() * 0.16f), r.nextFloat())
    }
    val c = if (onInk) Color.White else Color.Black
    val w = 1.5f.dp.toPx()
    onDrawBehind {
        blots.forEach { (o, rad, a) -> drawCircle(c.copy(alpha = 0.012f + a * 0.02f), rad, o) }
        drawPoints(pts, PointMode.Points, c.copy(alpha = 0.10f), w, StrokeCap.Round)
    }
}

/** A looping hand-drawn line that draws itself as [progress] goes 0 -> 1. */
internal fun Modifier.scribble(color: Color, progress: () -> Float, seed: Int = 0): Modifier = drawWithCache {
    val w = size.width
    val h = size.height
    val path = Path()
    val x0 = w * if (seed % 2 == 0) 0.50f else 0.38f
    val x1 = w * 0.99f
    val y0 = h * if (seed % 2 == 0) 0.095f else 0.05f
    val r = h * 0.032f
    val steps = 150
    for (i in 0..steps) {
        val t = i / steps.toFloat()
        val a = t * 5f * 2f * PI.toFloat()
        val x = x0 + (x1 - x0) * t + r * 1.7f * cos(a)
        val y = y0 + r * sin(a) + h * 0.012f * sin(t * 7f)
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    val pm = PathMeasure().also { it.setPath(path, false) }
    val len = pm.length
    val stroke = Stroke(2.6f.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
    onDrawBehind {
        val seg = Path()
        pm.getSegment(0f, len * progress().coerceIn(0f, 1f), seg, true)
        drawPath(seg, color, style = stroke)
    }
}

internal enum class Decor { Arcs, Checker, Scribble, Dots }

private class Dot(val x: Float, val y: Float, val r: Float, val coral: Boolean)

private val DotSets = listOf(
    listOf(Dot(.90f, .07f, 9f, true), Dot(.78f, .045f, 5f, false), Dot(.97f, .17f, 16f, false), Dot(.86f, .36f, 8f, true), Dot(.05f, .60f, 6f, false), Dot(.93f, .58f, 11f, false)),
    listOf(Dot(.08f, .06f, 7f, false), Dot(.92f, .12f, 14f, true), Dot(.83f, .075f, 5f, false), Dot(.04f, .44f, 9f, true), Dot(.95f, .50f, 6f, false), Dot(.14f, .93f, 8f, true)),
)

/** Poster scraps behind the content: bullseye arcs, checker strip, scribble, dots. */
@Composable
internal fun DecorLayer(onInk: Boolean, kinds: List<Decor>, seed: Int) {
    val ink = if (onInk) StoryColors.Cream else StoryColors.Ink
    val bg = if (onInk) StoryColors.Ink else StoryColors.Cream
    val dotCoral = if (onInk) StoryColors.Hot else StoryColors.Coral
    val drift = rememberInfiniteTransition(label = "drift")
        .animateFloat(0f, 1f, infiniteRepeatable(tween(16000, easing = LinearEasing)), label = "drift")
    val intro = rememberReveal(200)
    val dots = DotSets[seed % DotSets.size]

    Box(Modifier.fillMaxSize()) {
        if (Decor.Scribble in kinds) Box(Modifier.fillMaxSize().scribble(ink, { intro.value }, seed))
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val e = intro.value.coerceIn(0f, 1.1f)

            if (Decor.Arcs in kinds) {
                val rings = 8
                val maxR = w * 0.72f
                for (i in rings downTo 1) {
                    val f = i / rings.toFloat()
                    drawCircle(if (i % 2 == 0) ink else bg, maxR * f * (0.35f + 0.65f * e), Offset(w * 1.02f, h * 1.02f))
                }
            }
            if (Decor.Checker in kinds) {
                val cell = w / 9f
                val shift = (drift.value * cell * 2f)
                rotate(-6f, pivot = Offset(0f, h)) {
                    for (row in 0 until 3) for (col in -3..13) {
                        if ((row + col + 40) % 2 == 0) {
                            drawRect(ink, Offset(col * cell + shift - cell * 2f, h - (3 - row) * cell * e), Size(cell, cell))
                        }
                    }
                }
            }
            if (Decor.Dots in kinds) dots.forEachIndexed { i, d ->
                val bob = sin((drift.value * 2f * PI.toFloat()) + i) * 6.dp.toPx()
                val s = (e * 1.2f - i * 0.06f).coerceIn(0f, 1f)
                drawCircle(if (d.coral) dotCoral else ink, d.r.dp.toPx() * s, Offset(d.x * w, d.y * h + bob))
            }
        }
    }
}

/** The Chord mark (flag + 3 stacked bits) as a doodle. Same shape as AppLogo. */
@Composable
internal fun LogoDoodle(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val flag = Path().apply {
            moveTo(244f, 410f); lineTo(244f, 128f)
            cubicTo(244f, 114f, 256f, 106f, 270f, 111f)
            cubicTo(330f, 130f, 376f, 170f, 372f, 238f)
            cubicTo(370f, 276f, 350f, 298f, 328f, 306f)
            cubicTo(350f, 268f, 344f, 220f, 276f, 192f)
            lineTo(276f, 410f); close()
        }
        val s = size.height / 320f
        scale(s, pivot = Offset.Zero) {
            translate(-150f, -100f) {
                drawPath(flag, color)
                listOf(348f to 380f, 268f to 300f, 188f to 220f).forEach { (y, cy) ->
                    rotate(-18f, pivot = Offset(204f, cy)) {
                        drawRoundRect(color, Offset(162f, y), Size(84f, 64f), CornerRadius(18f))
                    }
                }
            }
        }
    }
}

/* ---------- type ---------- */

/** One line of text that shrinks until it fits the width. */
@Composable
internal fun FitText(
    text: String,
    maxSp: Float,
    color: Color,
    modifier: Modifier = Modifier,
    style: TextStyle = PosterStyle,
    minSp: Float = 18f,
) {
    var sp by remember(text, maxSp) { mutableFloatStateOf(maxSp) }
    var ready by remember(text, maxSp) { mutableStateOf(false) }
    Text(
        text, color = color,
        style = style.copy(fontSize = sp.sp, lineHeight = (sp * 1.0f).sp),
        maxLines = 1, softWrap = false, overflow = TextOverflow.Clip,
        onTextLayout = { r ->
            if (r.didOverflowWidth && sp > minSp) sp = (sp * 0.93f).coerceAtLeast(minSp) else ready = true
        },
        modifier = modifier.padding(end = 6.dp).drawWithContent { if (ready) drawContent() },
    )
}

/**
 * Big poster headline. Lines stamp in one by one. The [hiLine] gets the marker strip
 * (on cream) or the hot colour (on ink).
 */
@Composable
internal fun PosterHeading(
    lines: List<String>,
    hiLine: Int,
    onInk: Boolean,
    modifier: Modifier = Modifier,
    maxSp: Float = 64f,
    startDelay: Int = 100,
) {
    Column(modifier) {
        lines.forEachIndexed { i, t ->
            val p = rememberReveal(startDelay + i * 140)
            val strip = rememberReveal(startDelay + i * 140 + 260)
            val hi = i == hiLine
            val textColor = when {
                hi && onInk -> StoryColors.Hot
                hi -> StoryColors.Cream
                onInk -> StoryColors.Cream
                else -> StoryColors.Ink
            }
            Box(
                Modifier.wrapContentWidth(Alignment.Start)
                    .pop(p, rise = 56.dp, tilt = if (i % 2 == 0) -2f else -1f)
                    .then(
                        if (hi && !onInk) Modifier.drawBehind {
                            val ww = (size.width + 28.dp.toPx()) * strip.value.coerceIn(0f, 1f)
                            rotate(-1.5f) { drawRect(StoryColors.Coral, Offset(-14.dp.toPx(), size.height * 0.14f), Size(ww, size.height * 0.76f)) }
                        } else Modifier
                    )
            ) { FitText(t, maxSp, textColor) }
        }
    }
}

/** Black label stuck on the page, slightly crooked. */
@Composable
internal fun TapeLabel(text: String, onInk: Boolean, modifier: Modifier = Modifier, tilt: Float = -1.5f, sp: Float = 26f) {
    Box(
        modifier.rotate(tilt)
            .background(if (onInk) StoryColors.Cream else StoryColors.Ink)
            .padding(horizontal = 14.dp, vertical = 6.dp)
    ) {
        Text(text, style = PosterStyle.copy(fontSize = sp.sp, letterSpacing = (-0.5).sp), color = if (onInk) StoryColors.Ink else StoryColors.Cream, maxLines = 1)
    }
}

/** Types [lines] out letter by letter with a blinking cursor. [loop] false = type the first line and stay. */
@Composable
internal fun Typewriter(
    lines: List<String>,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    startDelay: Long = 400,
    charMs: Long = 38,
    holdMs: Long = 2000,
    loop: Boolean = true,
) {
    var shown by remember(lines) { mutableStateOf("") }
    LaunchedEffect(lines) {
        delay(startDelay)
        var i = 0
        while (true) {
            val full = lines[i % lines.size]
            for (n in 1..full.length) { shown = full.take(n); delay(charMs) }
            if (!loop) break
            delay(holdMs)
            for (n in full.length - 1 downTo 0) { shown = full.take(n); delay(14) }
            i++
        }
    }
    val blink = rememberInfiniteTransition(label = "cursor")
        .animateFloat(0f, 1f, infiniteRepeatable(tween(520), RepeatMode.Reverse), label = "blink")
    val text: AnnotatedString = buildAnnotatedString {
        append(shown)
        withStyle(SpanStyle(color = color.copy(alpha = if (blink.value > 0.5f) 1f else 0f))) { append("▌") }
    }
    Text(text, style = style, color = color, modifier = modifier)
}

/* ---------- backdrop ---------- */

/** Moving coral gradient. Behind the Home teaser and the story opener. */
@Composable
internal fun AuroraBackdrop(modifier: Modifier = Modifier) {
    val t = rememberInfiniteTransition(label = "aurora")
        .animateFloat(0f, 1f, infiniteRepeatable(tween(7000, easing = LinearEasing), RepeatMode.Reverse), label = "t")
    Box(
        modifier.drawBehind {
            val w = size.width
            val h = size.height
            val v = t.value
            drawRect(
                Brush.linearGradient(
                    listOf(StoryColors.Ink, StoryColors.Deep, StoryColors.Coral, StoryColors.Hot),
                    start = Offset(w * v * 0.5f, 0f),
                    end = Offset(w * (0.6f + 0.4f * v), h),
                )
            )
            val big = maxOf(w, h) * 0.7f
            val c1 = Offset(w * (0.15f + 0.7f * v), h * 0.2f)
            drawCircle(Brush.radialGradient(listOf(StoryColors.Hot.copy(alpha = 0.55f), Color.Transparent), c1, big), big, c1)
            val c2 = Offset(w * (0.9f - 0.6f * v), h * 0.95f)
            drawCircle(Brush.radialGradient(listOf(StoryColors.Cream.copy(alpha = 0.28f), Color.Transparent), c2, big * 0.8f), big * 0.8f, c2)
        }
    )
}

/* ---------- slide frame ---------- */

/** Where the top bar of the story ends. Slide content starts below this. */
internal val StoryTop = 84.dp

/**
 * Every slide sits in one of these: paper colour, grain, decor, brand mark.
 * The frame also records itself into a layer, so Share can turn the slide into a picture.
 */
@Composable
internal fun SlideFrame(
    onInk: Boolean,
    decor: List<Decor>,
    seed: Int,
    register: (GraphicsLayer) -> Unit,
    content: @Composable BoxScope.() -> Unit,
) {
    val layer = rememberGraphicsLayer()
    SideEffect { register(layer) }
    Box(
        Modifier.fillMaxSize()
            .drawWithContent { layer.record { this@drawWithContent.drawContent() }; drawLayer(layer) }
            .background(if (onInk) StoryColors.Ink else StoryColors.Cream)
            .paperGrain(onInk, seed)
    ) {
        DecorLayer(onInk, decor, seed)
        Row(
            Modifier.statusBarsPadding().padding(start = 20.dp, top = 23.dp).height(36.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(Modifier.clip(RoundedCornerShape(7.dp)).border(1.dp, (if (onInk) StoryColors.Cream else StoryColors.Ink).copy(alpha = 0.25f), RoundedCornerShape(7.dp))) {
                AppLogo(logoSize = 26.dp)
            }
            Text("Chord", style = PosterStyle.copy(fontSize = 17.sp, letterSpacing = (-0.4).sp), color = if (onInk) StoryColors.Cream else StoryColors.Ink)
        }
        content()
    }
}
