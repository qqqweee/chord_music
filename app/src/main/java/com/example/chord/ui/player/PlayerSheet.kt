package com.example.chord.ui.player

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.util.lerp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@Stable
class PlayerSheetState internal constructor(
    initial: Float,
    private val scope: CoroutineScope,
) {
    private val anim = Animatable(initial).apply {
        updateBounds(0f, 1f)
    }

    /**
     * 0f = mini player
     * 1f = full player
     */
    val progress: Float
        get() = anim.value

    /**
     * Y position of the mini-player (top edge) measured from the root.
     */
    internal var travelPx by mutableFloatStateOf(1f)

    /** Height of the mini-player in px. The sheet grows out of this box. */
    internal var miniHeightPx by mutableFloatStateOf(0f)

    /** Where the mini-player cover sits in the root. The big cover flies from here. */
    internal var coverRect by mutableStateOf(Rect.Zero)

    val isExpanded: Boolean
        get() = anim.targetValue > 0.5f

    /**
     * Positive dy = downward movement.
     * Negative dy = upward movement.
     */
    fun dragBy(dyPx: Float) {
        val travel = travelPx
        if (travel <= 1f) return

        scope.launch {
            anim.snapTo(
                (anim.value - dyPx / travel).coerceIn(0f, 1f)
            )
        }
    }

    /**
     * Settles the sheet after the user's finger is released.
     */
    fun settle(
        velocityPxPerSec: Float,
        keepOpenAbove: Float = 0.5f,
    ) {
        val target = when {
            velocityPxPerSec < -700f -> 1f
            velocityPxPerSec > 700f -> 0f
            anim.value >= keepOpenAbove -> 1f
            else -> 0f
        }

        val normalizedVelocity =
            -velocityPxPerSec / travelPx.coerceAtLeast(1f)

        animateTo(target, normalizedVelocity)
    }

    fun expand() {
        animateTo(1f)
    }

    fun collapse() {
        animateTo(0f)
    }

    private fun animateTo(
        target: Float,
        velocity: Float = 0f,
    ) {
        scope.launch {
            anim.animateTo(
                targetValue = target,
                animationSpec = spring(
                    dampingRatio = 1f,
                    stiffness = Spring.StiffnessMediumLow + 120f,
                ),
                initialVelocity = velocity,
            )
        }
    }
}

@Composable
fun rememberPlayerSheetState(
    initialExpanded: Boolean = false,
): PlayerSheetState {
    val scope = rememberCoroutineScope()

    return remember {
        PlayerSheetState(
            initial = if (initialExpanded) 1f else 0f,
            scope = scope,
        )
    }
}

/**
 * The full player GROWS out of the mini-player box.
 * Top edge, bottom edge, side inset and corner radius all follow progress,
 * so the sheet is a bigger and bigger window. Nothing slides in from outside.
 */
fun Modifier.playerSheetLayer(
    state: PlayerSheetState,
    miniInsetPx: Float,
    miniRadiusPx: Float,
): Modifier = graphicsLayer {
    val p = state.progress.coerceIn(0f, 1f)
    // Bottom edge runs a bit ahead, so the sheet reaches the screen bottom early.
    val pb = (p * 1.3f).coerceAtMost(1f)
    val miniTop = state.travelPx
    val miniBottom = miniTop + state.miniHeightPx

    shape = PlayerSheetShape(
        inset = lerp(miniInsetPx, 0f, p),
        top = lerp(miniTop, 0f, p),
        bottom = lerp(miniBottom, size.height, pb),
        radius = lerp(miniRadiusPx, 0f, p),
    )
    clip = true
}

/** Dark backdrop of the full player. Fades in while the sheet opens. */
fun Modifier.sheetBackdrop(
    state: PlayerSheetState?,
    color: Color,
): Modifier = drawBehind {
    val p = (state?.progress ?: 1f).coerceIn(0f, 1f)
    drawRect(color.copy(alpha = (p / 0.25f).coerceIn(0f, 1f)))
}

/**
 * Fades + lifts content in as the sheet opens, from [start] to [end] progress.
 * Closing runs the same thing backwards.
 */
fun Modifier.sheetStage(
    state: PlayerSheetState?,
    start: Float,
    end: Float = 1f,
    risePx: Float = 0f,
): Modifier = graphicsLayer {
    val p = (state?.progress ?: 1f).coerceIn(0f, 1f)
    val t = ((p - start) / (end - start)).coerceIn(0f, 1f)
    alpha = t
    translationY = (1f - t) * risePx
}

/**
 * Shared-element flight of the cover.
 *
 * At progress 0 the hero artwork is shrunk and clipped to exactly sit on the
 * mini-player cover. As progress grows it scales up, moves, and the clip window
 * opens from the small rounded square to the full hero area.
 */
fun Modifier.coverFlight(
    state: PlayerSheetState?,
    coverRadiusPx: Float,
): Modifier = graphicsLayer {
    val p = (state?.progress ?: 1f).coerceIn(0f, 1f)
    val cover = state?.coverRect ?: Rect.Zero
    if (p >= 0.999f || cover.width <= 1f) return@graphicsLayer

    val e = p
    val w = size.width
    val h = size.height
    val m = minOf(w, h)

    // Uniform scale, so the picture is never stretched.
    val s = lerp(cover.width / m, 1f, e)
    val cx = lerp(cover.center.x, w / 2f, e)
    val cy = lerp(cover.center.y, h / 2f, e)
    translationX = cx - w / 2f
    translationY = cy - h / 2f
    scaleX = s
    scaleY = s

    // Clip window in screen space, then mapped back to the layer's own space.
    val winL = lerp(cover.left, 0f, e)
    val winT = lerp(cover.top, 0f, e)
    val winR = lerp(cover.right, w, e)
    val winB = lerp(cover.bottom, h, e)
    shape = RevealWindowShape(
        left = w / 2f + (winL - cx) / s,
        top = h / 2f + (winT - cy) / s,
        right = w / 2f + (winR - cx) / s,
        bottom = h / 2f + (winB - cy) / s,
        radius = lerp(coverRadiusPx, 0f, e) / s,
    )
    clip = true
}

private class RevealWindowShape(
    private val left: Float,
    private val top: Float,
    private val right: Float,
    private val bottom: Float,
    private val radius: Float,
) : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline = Outline.Rounded(
        RoundRect(left, top, right, bottom, CornerRadius(radius, radius))
    )
}

private class PlayerSheetShape(
    private val inset: Float,
    private val top: Float,
    private val bottom: Float,
    private val radius: Float,
) : Shape {

    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline {
        return Outline.Rounded(
            RoundRect(
                left = inset,
                top = top,
                right = size.width - inset,
                bottom = bottom,
                cornerRadius = CornerRadius(radius, radius),
            )
        )
    }
}