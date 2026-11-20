package com.example.chord.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.chord.data.settings.AppSettings
import android.os.Build
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect

/**
 * Every knob the user can tune. Ranges = slider ranges in Settings > Look.
 * Stored in AppSettings (glassBlur, glassTint, glassSheen, glassRim, glassDepth).
 */
data class GlassSpec(
    val blur: Dp = 20.dp,          // 0..40   only real cost. Lower = less battery
    val tintAlpha: Float = 0.08f,  // 0..0.4  white veil
    val noise: Float = 0.04f,      // 0..0.2  frost grain
    val sheen: Float = 0.14f,      // 0..0.4  diagonal light streak
    val rim: Float = 0.50f,        // 0..1    bent-light edge
    val depth: Dp = 8.dp,          // 0..20   shadow under bar
    val solid: Color = Color(0xFF1B1B21), // glass OFF
    // True liquid glass only (Settings > Look > "True liquid glass"):
    val liquidBlur: Dp = 2.dp,        // 0..12   Apple glass = low blur
    val refractHeight: Dp = 14.dp,    // 0..32   width of the bent edge band
    val refractAmount: Dp = 28.dp,    // 0..60   how hard edge bends light
)

/**
 * Glass for nav pill + mini player. Three modes:
 *  True liquid glass ON (+ API 33+ + [backdrop] given): real lens shader, edge refraction.
 *  Else glass ON: hybrid below.
 *  Glass OFF: solid.
 * Hybrid glass:
 *  Glass ON : one Haze blur (the only costly part) + static gradients that fake
 *             liquid glass: diagonal sheen, top glow, bottom shade, bent-light rim, soft shadow.
 *  Glass OFF: solid surface + same rim. No blur work.
 * Switch: Settings > Look > "Glass bars" (AppSettings.glass).
 */
fun Modifier.chordGlass(
    haze: HazeState?,
    shape: Shape,
    spec: GlassSpec = AppSettings.glassSpec(),
    backdrop: LayerBackdrop? = null,
): Modifier = composed {
    val liquid = AppSettings.glass && AppSettings.trueGlass &&
            backdrop != null && Build.VERSION.SDK_INT >= 33
    val glass = AppSettings.glass && haze != null

    // Static brushes. Rebuilt only when the slider changes. No per-frame work.
    val rimBrush = remember(spec.rim) {
        Brush.linearGradient(
            listOf(
                Color.White.copy(spec.rim),          // top-left catches light
                Color.White.copy(spec.rim * 0.06f),  // dark middle
                Color.White.copy(spec.rim * 0.35f),  // bottom-right light bounce
            ),
            start = Offset.Zero,
            end = Offset.Infinite,
        )
    }
    val sheenBrush = remember(spec.sheen) {
        Brush.linearGradient(
            listOf(
                Color.White.copy(spec.sheen),
                Color.Transparent,
                Color.White.copy(spec.sheen * 0.3f),
            ),
            start = Offset.Zero,
            end = Offset.Infinite,
        )
    }
    // Glow on top edge + faint shade at bottom = glass looks thick.
    val thickBrush = remember(spec.sheen) {
        Brush.verticalGradient(
            0f to Color.White.copy(spec.sheen * 0.7f),
            0.35f to Color.Transparent,
            0.75f to Color.Transparent,
            1f to Color.Black.copy(0.14f),
        )
    }

    if (liquid) {
        // Real liquid glass. Lens shader bends what sits behind the bar.
        this.drawBackdrop(
            backdrop = backdrop!!,
            shape = { shape },
            effects = {
                vibrancy()
                if (spec.liquidBlur > 0.dp) blur(spec.liquidBlur.toPx())
                if (spec.refractAmount > 0.dp) {
                    lens(spec.refractHeight.toPx(), spec.refractAmount.toPx())
                }
            },
            onDrawSurface = { drawRect(Color.White.copy(alpha = spec.tintAlpha)) },
        )
    } else if (!glass) {
        this
            .clip(shape)
            .background(spec.solid)
            .border(0.8.dp, rimBrush, shape)
    } else {
        this
            .then(
                if (spec.depth > 0.dp) {
                    Modifier.shadow(
                        spec.depth, shape, clip = false,
                        ambientColor = Color.Black.copy(0.3f),
                        spotColor = Color.Black.copy(0.3f),
                    )
                } else Modifier
            )
            .clip(shape)
            .hazeEffect(state = haze!!) {
                blurRadius = spec.blur
                noiseFactor = spec.noise
                backgroundColor = Color.Transparent
                tints = listOf(HazeTint(Color.White.copy(spec.tintAlpha)))
                // Phones with no real blur (API < 31): plain dark glass.
                fallbackTint = HazeTint(Color(0xCC1B1B21))
            }
            .background(sheenBrush)
            .background(thickBrush)
            .border(1.dp, rimBrush, shape)
    }
}