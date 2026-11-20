package com.example.chord.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import com.example.chord.ui.haptics.Haptic
import com.example.chord.ui.haptics.rememberHaptics

/**
 * Same look as before. Changed from Modifier.composed to a plain @Composable
 * modifier: composed{} is slow in long lists and was a scroll-jank source.
 * Call it only inside composable code (all current callers do).
 */
@Composable
fun Modifier.pressScale(
    pressedScale: Float = 0.96f,
    haptic: Boolean = true,
    feedback: Haptic = Haptic.Tap,
    onClick: () -> Unit,
): Modifier {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) pressedScale else 1f,
        animationSpec = spring(dampingRatio = 0.62f, stiffness = Spring.StiffnessMedium),
        label = "pressScale",
    )
    val haptics = rememberHaptics()
    val latest by rememberUpdatedState(onClick)
    return this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .clickable(interactionSource = source, indication = null) {
            if (haptic) haptics.perform(feedback)
            latest()
        }
}