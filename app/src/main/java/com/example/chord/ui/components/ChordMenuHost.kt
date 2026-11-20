package com.example.chord.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect

/** One open menu: where its button is, what to draw, how to close it. */
class MenuRequest(
    val id: Any,
    val anchor: Rect,
    val onDismiss: () -> Unit,
    val content: @Composable ColumnScope.() -> Unit,
)

/** Holds the one menu that is open. [ChordMenu] writes it, [ChordMenuHost] draws it. */
@Stable
class MenuHostState {
    var current by mutableStateOf<MenuRequest?>(null)
        private set

    fun show(request: MenuRequest) { current = request }
    fun hide(id: Any) { if (current?.id === id) current = null }
}

val LocalMenuHost = staticCompositionLocalOf<MenuHostState?> { null }

/** Haze state whose source is the whole app. Menu glass blurs what is behind it. */
val LocalMenuHaze = staticCompositionLocalOf<HazeState?> { null }

private val GlassShape = RoundedCornerShape(20.dp)
private val GlassBase = Color(0xFF111114)
private val GlassTintColor = Color(0x8C16161A)

/**
 * Put this last inside the root Box, after everything the menu should blur.
 * A Popup is its own window, so Haze cannot see through it. This draws in the same window.
 */
@Composable
fun ChordMenuHost(state: MenuHostState, haze: HazeState, modifier: Modifier = Modifier) {
    val request = state.current
    var last by remember { mutableStateOf<MenuRequest?>(null) }
    if (request != null) last = request

    BackHandler(enabled = request != null) { request?.onDismiss?.invoke() }

    AnimatedVisibility(
        visible = request != null,
        modifier = modifier.fillMaxSize(),
        enter = fadeIn(tween(140)) + scaleIn(tween(180), initialScale = 0.92f),
        exit = fadeOut(tween(110)) + scaleOut(tween(110), targetScale = 0.96f),
    ) {
        val r = last
        if (r != null) MenuLayer(r, haze)
    }
}

@Composable
private fun MenuLayer(r: MenuRequest, haze: HazeState) {
    Box(
        Modifier.fillMaxSize()
            .background(Color.Black.copy(alpha = 0.28f))
            .pointerInput(r.id) { detectTapGestures { r.onDismiss() } },
    ) {
        Column(
            Modifier
                .layout { measurable, c ->
                    val margin = 12.dp.roundToPx()
                    val gap = 4.dp.roundToPx()
                    val maxW = minOf(c.maxWidth - 2 * margin, 230.dp.roundToPx())
                    val minW = minOf(maxW, 190.dp.roundToPx())
                    val maxH = c.maxHeight - 2 * margin
                    val p = measurable.measure(Constraints(minWidth = minW, maxWidth = maxW, maxHeight = maxH))
                    val a = r.anchor
                    val alignRight = a.center.x > c.maxWidth / 2f

                    val x = (if (alignRight) a.right.toInt() - p.width else a.left.toInt())
                        .coerceIn(margin, maxOf(margin, c.maxWidth - p.width - margin))


                    val below = a.bottom.toInt() + gap
                    val above = a.top.toInt() - gap - p.height

                    val y = when {
                        below + p.height <= c.maxHeight - margin -> below
                        above >= margin -> above
                        else -> (a.center.y.toInt() - p.height / 2)
                            .coerceIn(margin, maxOf(margin, c.maxHeight - p.height - margin))
                    }

                    layout(c.maxWidth, c.maxHeight) { p.place(x, y) }
                }
                .clip(GlassShape)
                .hazeEffect(
                    state = haze,
                    style = HazeStyle(
                        backgroundColor = GlassBase,
                        tint = HazeTint(GlassTintColor),
                        blurRadius = 28.dp,
                        noiseFactor = 0.04f,
                    ),
                )
                .border(
                    0.7.dp,
                    Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.26f), Color.White.copy(alpha = 0.05f))),
                    GlassShape,
                )
                // Taps on the card must not reach the scrim.
                .pointerInput(Unit) { detectTapGestures { } }
                .verticalScroll(rememberScrollState())
                .padding(vertical = 6.dp),
            content = r.content,
        )
    }
}
