package com.example.chord.ui.haptics

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView

/*
 * ONE FILE HAPTICS. Drop in, call, done.
 *
 *   val haptics = rememberHaptics()
 *   haptics.perform(Haptic.Tap)
 *
 *   Modifier.hapticClickable { ... }                       // tap
 *   Modifier.hapticClickable(Haptic.Select) { ... }        // tab switch
 *   Modifier.hapticCombinedClickable(onLongClick = { ... }) { ... }
 *   HapticOnChange(pageIndex)                              // tick when a value changes
 *
 * Pick a feel:
 *   Tap        normal button press
 *   Select     tab, chip, row pick (light)
 *   ToggleOn   switch / like / shuffle on
 *   ToggleOff  switch / like / shuffle off
 *   Confirm    action done: swipe next, added to queue
 *   Reject     blocked action, error
 *   LongPress  menu open, drag start
 *   Tick       slider step, pager step, list reorder step
 *   Heavy      big moment: player expand
 *   Dismiss    sheet or player close
 *
 * No permission needed. Uses View haptics, so it follows the phone's
 * "touch feedback" setting. Turn all off in app: HapticSettings.enabled = false
 */

enum class Haptic { Tap, Select, ToggleOn, ToggleOff, Confirm, Reject, LongPress, Tick, Heavy, Dismiss }

/** App-wide switch. Wire to a Settings toggle if you want one. */
object HapticSettings {
    var enabled by mutableStateOf(true)
}

@Stable
class Haptics(private val view: View) {

    fun perform(type: Haptic) {
        if (!HapticSettings.enabled) return
        view.performHapticFeedback(constantFor(type))
    }

    private fun constantFor(type: Haptic): Int {
        val sdk = Build.VERSION.SDK_INT
        return when (type) {
            Haptic.Tap -> HapticFeedbackConstants.VIRTUAL_KEY
            Haptic.Select ->
                if (sdk >= 23) HapticFeedbackConstants.CONTEXT_CLICK else HapticFeedbackConstants.VIRTUAL_KEY
            Haptic.ToggleOn ->
                if (sdk >= 34) HapticFeedbackConstants.TOGGLE_ON else HapticFeedbackConstants.CONTEXT_CLICK
            Haptic.ToggleOff ->
                if (sdk >= 34) HapticFeedbackConstants.TOGGLE_OFF else HapticFeedbackConstants.CLOCK_TICK
            Haptic.Confirm ->
                if (sdk >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY
            Haptic.Reject ->
                if (sdk >= 30) HapticFeedbackConstants.REJECT else HapticFeedbackConstants.LONG_PRESS
            Haptic.LongPress -> HapticFeedbackConstants.LONG_PRESS
            Haptic.Tick ->
                if (sdk >= 34) HapticFeedbackConstants.SEGMENT_FREQUENT_TICK else HapticFeedbackConstants.CLOCK_TICK
            Haptic.Heavy -> HapticFeedbackConstants.LONG_PRESS
            Haptic.Dismiss ->
                if (sdk >= 30) HapticFeedbackConstants.GESTURE_END else HapticFeedbackConstants.CLOCK_TICK
        }
    }
}

/** Get a Haptics for this screen. Cheap. Safe to call in any composable. */
@Composable
fun rememberHaptics(): Haptics {
    val view = LocalView.current
    return remember(view) { Haptics(view) }
}

/** Click with haptic. Same as Modifier.clickable plus feedback. */
@Composable
fun Modifier.hapticClickable(
    haptic: Haptic = Haptic.Tap,
    enabled: Boolean = true,
    onClick: () -> Unit,
): Modifier {
    val haptics = rememberHaptics()
    val latest by rememberUpdatedState(onClick)
    return clickable(enabled = enabled) {
        haptics.perform(haptic)
        latest()
    }
}

/** Click plus long click, each with its own haptic. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Modifier.hapticCombinedClickable(
    haptic: Haptic = Haptic.Tap,
    longHaptic: Haptic = Haptic.LongPress,
    enabled: Boolean = true,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
): Modifier {
    val haptics = rememberHaptics()
    val latestClick by rememberUpdatedState(onClick)
    val latestLong by rememberUpdatedState(onLongClick)
    return combinedClickable(
        enabled = enabled,
        onLongClick = latestLong?.let { { haptics.perform(longHaptic); latestLong?.invoke() } },
        onClick = { haptics.perform(haptic); latestClick() },
    )
}

/**
 * Buzz when [key] changes. Skips the first value, so no buzz on open.
 * Use for pager page, slider step, selected tab, queue index.
 */
@Composable
fun HapticOnChange(key: Any?, haptic: Haptic = Haptic.Tick) {
    val haptics = rememberHaptics()
    var first by remember { mutableStateOf(true) }
    LaunchedEffect(key) {
        if (first) first = false else haptics.perform(haptic)
    }
}