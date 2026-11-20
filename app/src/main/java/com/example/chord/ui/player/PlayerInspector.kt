package com.example.chord.ui.player

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.chord.ui.theme.ChordTheme
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import kotlin.math.roundToInt

/* ════════════════════════════════════════════════════════════════════════════
 *  Player inspector  (compact glass)
 *
 *  One thin frosted strip. Top row: [Parts | Sections] switch + a scrolling row
 *  of every part. Below it, only for the picked part: name, show switch and
 *  hairline sliders in two columns. The ▾ button folds the controls away so you
 *  can see the player.
 *
 *  Glass = Haze blur of the player behind it (same Haze the nav bars use), with
 *  a light dark tint so text stays readable. No haze given = plain translucent.
 *
 *  The strip jumps to the top when the picked part sits in the lower half.
 * ════════════════════════════════════════════════════════════════════════════ */

private val PanelShape = RoundedCornerShape(20.dp)
private val InkHi = Color.White.copy(alpha = 0.92f)
private val InkMid = Color.White.copy(alpha = 0.62f)
private val InkLow = Color.White.copy(alpha = 0.40f)

@Composable
fun PlayerInspector(
    layout: PlayerLayoutState,
    statusTop: Dp,
    modifier: Modifier = Modifier,
    haze: HazeState? = null,
) {
    val sel = layout.selected
    val selSec = layout.selectedSection
    val sections = layout.sectionMode
    var open by remember { mutableStateOf(true) }
    val atTop = remember(sel, selSec, sections) {
        val y = if (sections) selSec?.let { layout.sectionRestRect(it)?.center?.y }
        else sel?.let { layout.state(it).restRect.center.y }
        y?.let { it - layout.origin.y > layout.areaHeight / 2f } ?: false
    }
    val accent = ChordTheme.accent
    val picked = if (sections) selSec != null else sel != null

    val glass = if (haze != null) {
        Modifier.hazeEffect(state = haze) {
            blurRadius = 26.dp
            noiseFactor = 0.03f
            backgroundColor = Color.Transparent
            tints = listOf(HazeTint(Color.Black.copy(alpha = 0.26f)))
            fallbackTint = HazeTint(Color(0xCC141414))
        }
    } else Modifier.background(Color(0x99141414))

    Box(modifier) {
        Column(
            Modifier
                .align(if (atTop) Alignment.TopCenter else Alignment.BottomCenter)
                .then(
                    if (atTop) Modifier.padding(top = statusTop + 46.dp)
                    else Modifier.navigationBarsPadding().padding(bottom = 6.dp)
                )
                .padding(horizontal = 8.dp)
                .fillMaxWidth()
                .clip(PanelShape)
                .then(glass)
                .background(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.08f), Color.Transparent)))
                .border(
                    0.6.dp,
                    Brush.linearGradient(listOf(Color.White.copy(alpha = 0.30f), Color.White.copy(alpha = 0.05f), Color.White.copy(alpha = 0.14f))),
                    PanelShape,
                )
                // Blocks touches so the edit overlay below never sees them.
                .pointerInput(Unit) { detectTapGestures { } }
                .padding(vertical = 6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 8.dp)) {
                ModeSwitch(layout, accent)
                Spacer(Modifier.width(6.dp))
                Box(Modifier.weight(1f)) {
                    if (sections) SectionChips(layout, accent) else PartChips(layout, accent)
                }
            }

            if (picked && open) {
                Spacer(Modifier.height(4.dp))
                if (sections) SectionControls(layout, selSec!!, accent, onFold = { open = false })
                else PartControls(layout, sel!!, accent, onFold = { open = false })
            } else if (picked) {
                Text(
                    "▴  options",
                    color = InkMid, fontSize = 10.sp,
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .clip(RoundedCornerShape(50))
                        .clickable { open = true }
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                )
            } else {
                Text(
                    if (sections) "Drag a section · drop on another to swap"
                    else "Tap a part, or pick one above",
                    color = InkLow, fontSize = 10.sp,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp),
                )
            }
        }
    }
}

// ── Top row ──────────────────────────────────────────────────────────────────

@Composable
private fun ModeSwitch(layout: PlayerLayoutState, accent: Color) {
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(Color.White.copy(alpha = 0.08f))
            .padding(2.dp),
    ) {
        listOf(false to "Parts", true to "Sections").forEach { (mode, name) ->
            val on = layout.sectionMode == mode
            val bg by animateColorAsState(if (on) accent else Color.Transparent, tween(140), label = "mode_$name")
            Text(
                name,
                color = if (on) Color.Black else InkMid,
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(bg)
                    .clickable { if (!on) layout.switchMode(mode) }
                    .padding(horizontal = 9.dp, vertical = 4.dp),
            )
        }
    }
}

@Composable
private fun Chip(text: String, picked: Boolean, shown: Boolean, accent: Color, onClick: () -> Unit) {
    val bg by animateColorAsState(
        if (picked) accent else Color.White.copy(alpha = 0.08f), tween(140), label = "chip",
    )
    Text(
        text = (if (shown) "" else "○ ") + text,
        color = when {
            picked -> Color.Black
            shown -> InkHi
            else -> InkLow
        },
        fontSize = 10.5.sp,
        fontWeight = if (picked) FontWeight.SemiBold else FontWeight.Medium,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 4.dp),
    )
}

@Composable
private fun PartChips(layout: PlayerLayoutState, accent: Color) {
    LazyRow(
        contentPadding = PaddingValues(end = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items(PlayerBlock.values().toList(), key = { it.name }) { b ->
            Chip(b.label, layout.selected == b, layout.state(b).visible, accent) { layout.select(b) }
        }
    }
}

@Composable
private fun SectionChips(layout: PlayerLayoutState, accent: Color) {
    LazyRow(
        contentPadding = PaddingValues(end = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items(PlayerSection.values().toList(), key = { it.name }) { sec ->
            Chip(sec.label, layout.selectedSection == sec, layout.sectionVisible(sec), accent) { layout.selectSection(sec) }
        }
    }
}

// ── Controls ─────────────────────────────────────────────────────────────────

private class Knob(
    val label: String,
    val value: Float,
    val range: ClosedFloatingPointRange<Float>,
    val shown: String,
    val onChange: (Float) -> Unit,
    val onFinish: () -> Unit,
)

@Composable
private fun Header(
    title: String,
    sub: String?,
    visible: Boolean,
    accent: Color,
    onVisible: (Boolean) -> Unit,
    onReset: () -> Unit,
    onFold: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = InkHi, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            if (sub != null) Text(sub, color = InkLow, fontSize = 9.sp, maxLines = 1)
        }
        Text(
            "Reset", color = InkMid, fontSize = 10.sp,
            modifier = Modifier.clip(RoundedCornerShape(50)).clickable(onClick = onReset)
                .padding(horizontal = 8.dp, vertical = 4.dp),
        )
        MiniSwitch(visible, accent, onVisible)
        Text(
            "▾", color = InkMid, fontSize = 12.sp,
            modifier = Modifier.clip(CircleShape).clickable(onClick = onFold)
                .padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun KnobGrid(knobs: List<Knob>, accent: Color) {
    Column(
        Modifier
            .heightIn(max = 132.dp)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        knobs.chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                pair.forEach { k -> Box(Modifier.weight(1f)) { KnobCell(k, accent) } }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun KnobCell(k: Knob, accent: Color) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(k.label, color = InkMid, fontSize = 9.5.sp, maxLines = 1, modifier = Modifier.weight(1f))
            Text(k.shown, color = InkLow, fontSize = 9.sp, maxLines = 1)
        }
        ThinSlider(k.value, k.range, accent, k.onChange, k.onFinish)
    }
}

@Composable
private fun PartControls(layout: PlayerLayoutState, block: PlayerBlock, accent: Color, onFold: () -> Unit) {
    val s = layout.state(block)
    val finish = { layout.clampAll(); layout.save() }
    fun pct(v: Float) = "${(v * 100f).roundToInt()}%"

    val knobs = buildList {
        add(Knob("Size", s.scale, block.minScale..block.maxScale, pct(s.scale), { layout.setScale(block, it) }, finish))
        block.textLabel?.let {
            add(Knob(it, s.textScale, TEXT_SCALE_MIN..TEXT_SCALE_MAX, pct(s.textScale), { v -> s.textScale = v }, finish))
        }
        block.iconLabel?.let {
            add(Knob(it, s.iconScale, block.iconMin..block.iconMax, pct(s.iconScale), { v -> s.iconScale = v }, finish))
        }
        if (block == PlayerBlock.Artwork) {
            val auto = layout.heroHeight <= 0f
            val h = if (auto) 0.5f else layout.heroHeight
            add(Knob(if (auto) "Height · auto" else "Height", h, 0.25f..0.85f, pct(h), { v -> layout.heroHeight = v }, finish))
            add(Knob("Width", layout.heroWidth, 0.4f..1f, pct(layout.heroWidth), { v -> layout.heroWidth = v }, finish))
            add(Knob("Corners", layout.heroCorner, 0f..60f, "${layout.heroCorner.roundToInt()}dp", { v -> layout.heroCorner = v }, finish))
            add(Knob("Bottom fade", layout.heroBottomFade, 0f..1f, pct(layout.heroBottomFade), { v -> layout.heroBottomFade = v }, finish))
            add(Knob("Side fade", layout.heroSideFeather, 0f..1f, pct(layout.heroSideFeather), { v -> layout.heroSideFeather = v }, finish))
        }
    }

    Header(
        title = block.label, sub = null, visible = s.visible, accent = accent,
        onVisible = { layout.setVisible(block, it) },
        onReset = { layout.resetBlock(block) },
        onFold = onFold,
    )
    KnobGrid(knobs, accent)
}

@Composable
private fun SectionControls(layout: PlayerLayoutState, sec: PlayerSection, accent: Color, onFold: () -> Unit) {
    // Relative: 100% = as it is now. Dragging grows / shrinks the whole section
    // around its middle, then the knob snaps back to 100%.
    var factor by remember(sec) { mutableFloatStateOf(1f) }
    var last by remember(sec) { mutableFloatStateOf(1f) }

    Header(
        title = sec.label,
        sub = sec.members.joinToString(" · ") { it.label },
        visible = layout.sectionVisible(sec), accent = accent,
        onVisible = { layout.setSectionVisible(sec, it) },
        onReset = { layout.resetSection(sec) },
        onFold = onFold,
    )
    if (sec != PlayerSection.Hero) {
        KnobGrid(
            listOf(
                Knob(
                    "Grow / shrink all", factor, 0.6f..1.6f, "${(factor * 100f).roundToInt()}%",
                    { v -> layout.scaleSection(sec, v / last); last = v; factor = v },
                    { factor = 1f; last = 1f; layout.clampAll(); layout.save() },
                )
            ),
            accent,
        )
    }
}

// ── Small pieces ─────────────────────────────────────────────────────────────

/** Hairline slider: 2dp track, small thumb, 18dp tall. */
@Composable
private fun ThinSlider(
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    accent: Color,
    onChange: (Float) -> Unit,
    onFinish: () -> Unit,
) {
    val change by rememberUpdatedState(onChange)
    val finish by rememberUpdatedState(onFinish)
    val lo = range.start
    val span = (range.endInclusive - range.start).coerceAtLeast(0.0001f)
    val f = ((value - lo) / span).coerceIn(0f, 1f)

    fun at(x: Float, w: Int): Float = lo + span * (x / w.coerceAtLeast(1)).coerceIn(0f, 1f)

    Box(
        Modifier
            .fillMaxWidth()
            .height(18.dp)
            .pointerInput(lo, span) {
                detectTapGestures { o -> change(at(o.x, size.width)); finish() }
            }
            .pointerInput(lo, span) {
                detectHorizontalDragGestures(
                    onDragStart = { o -> change(at(o.x, size.width)) },
                    onDragEnd = { finish() },
                    onDragCancel = { finish() },
                    onHorizontalDrag = { c, _ -> c.consume(); change(at(c.position.x, size.width)) },
                )
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val cy = size.height / 2f
            val th = 2.dp.toPx()
            val r = 5.dp.toPx()
            val x = r + (size.width - 2f * r) * f
            drawRoundRect(Color.White.copy(alpha = 0.18f), Offset(0f, cy - th / 2f), Size(size.width, th), CornerRadius(th / 2f))
            drawRoundRect(accent, Offset(0f, cy - th / 2f), Size(x, th), CornerRadius(th / 2f))
            drawCircle(Color.Black.copy(alpha = 0.25f), r + 1.dp.toPx(), Offset(x, cy))
            drawCircle(Color.White, r, Offset(x, cy))
        }
    }
}

@Composable
private fun MiniSwitch(checked: Boolean, accent: Color, onChange: (Boolean) -> Unit) {
    val track by animateColorAsState(if (checked) accent else Color.White.copy(alpha = 0.18f), tween(140), label = "swTrack")
    val pos by androidx.compose.animation.core.animateDpAsState(if (checked) 14.dp else 2.dp, tween(140), label = "swPos")
    Box(
        Modifier
            .padding(horizontal = 4.dp)
            .size(width = 30.dp, height = 16.dp)
            .clip(RoundedCornerShape(50))
            .background(track)
            .clickable { onChange(!checked) },
    ) {
        Box(
            Modifier
                .padding(start = pos, top = 2.dp)
                .size(12.dp)
                .clip(CircleShape)
                .background(if (checked) Color.Black else Color.White.copy(alpha = 0.8f)),
        )
    }
}
