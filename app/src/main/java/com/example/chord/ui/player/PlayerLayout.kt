package com.example.chord.ui.player

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.toSize
import com.example.chord.ui.theme.ChordTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.tanh

/* ════════════════════════════════════════════════════════════════════════════
 *  Player layout editing  (v2)
 *
 *  How it works, short version:
 *   • Every part ("block") only gets an offset + scale through graphicsLayer.
 *     Measured layout never changes, so nothing else jumps.
 *   • In edit mode ONE full-screen overlay (PlayerEditOverlay) owns all touch.
 *     It works in root coordinates, so the part moving under the finger can
 *     never feed back into the gesture (this was the main old glitch).
 *   • Limits are computed from the real screen area and the part size, so a
 *     part can never leave the screen. Pulling past an edge rubber-bands.
 *   • Drag a part over another one: the other one slides to the spot you left
 *     (live preview). Let go and both settle with a spring.
 *   • Corner dot or two-finger pinch resizes around the center.
 *   • Soft snap + guide lines: screen center, side margins, original spot,
 *     scale 100%.
 *
 *  Values are saved in dp in SharedPreferences ("player_layout_v2").
 * ════════════════════════════════════════════════════════════════════════════ */

private const val LIFT = 0.04f
private val EDIT_MARGIN = 26.dp

private val MoveSpring = spring<Offset>(
    dampingRatio = 0.74f, stiffness = 420f, visibilityThreshold = Offset.VisibilityThreshold,
)
private val ScaleSpring = spring<Float>(dampingRatio = 0.78f, stiffness = 500f)
private val LiftSpring = spring<Float>(dampingRatio = 0.70f, stiffness = 700f)

/**
 * Every part of the player is its own block. Each block can be moved, scaled,
 * hidden, and (where it makes sense) given its own text size and icon size.
 *
 * [textLabel] / [iconLabel] = name of the slider shown in the inspector.
 * null = this block has no such thing, so the slider is not shown.
 */
enum class PlayerBlock(
    val label: String,
    val minScale: Float = 0.5f,
    val maxScale: Float = 1.6f,
    val swappable: Boolean = true,
    val textLabel: String? = null,
    val iconLabel: String? = null,
    val legacyKey: String? = null,
    val iconMin: Float = 0.5f,
    val iconMax: Float = 1.8f,
) {
    Artwork("Hero art", 0.6f, 1f, swappable = false, legacyKey = "show_artwork"),
    SourceLabel("Source label", textLabel = "Text size"),
    AudioInfo("Codec info", textLabel = "Text size"),
    Title("Song title", textLabel = "Text size", legacyKey = "show_title"),
    Artist("Artist", textLabel = "Text size", legacyKey = "show_title"),
    Favorite("Star button", iconLabel = "Button size", legacyKey = "show_favorite"),
    More("More button", iconLabel = "Button size", legacyKey = "show_more"),
    Lyrics("Lyric line", textLabel = "Text size", legacyKey = "show_lyrics"),
    Seek("Seek bar", textLabel = "Time text size", iconLabel = "Bar thickness", legacyKey = "show_seek", iconMin = 0.5f, iconMax = 2f),
    Previous("Previous", iconLabel = "Icon size", legacyKey = "show_transport"),
    PlayPause("Play / pause", iconLabel = "Icon size", legacyKey = "show_transport"),
    Next("Next", iconLabel = "Icon size", legacyKey = "show_transport"),
    Volume("Volume", iconLabel = "Icon size", legacyKey = "show_volume"),
    LyricsBtn("Lyrics button", iconLabel = "Button size", legacyKey = "show_bottom_bar"),
    OutputPill("Output pill", iconLabel = "Pill size", legacyKey = "show_bottom_bar"),
    QueueBtn("Queue button", iconLabel = "Button size", legacyKey = "show_bottom_bar"),
    DeviceName("Device name", textLabel = "Text size"),
}

/**
 * A section = a group of parts that move and swap TOGETHER (Controls = previous +
 * play + next). Parts stay individually editable in "Parts" mode; in "Sections"
 * mode the whole group is one draggable unit that can swap with any other section.
 */
enum class PlayerSection(val label: String, val swappable: Boolean = true) {
    Hero("Hero art", swappable = false),
    Source("Source label"),
    Codec("Codec info"),
    Credits("Title section"),
    LyricLine("Lyric line"),
    SeekBar("Seek bar"),
    Controls("Controls"),
    VolumeBar("Volume"),
    BottomBar("Bottom bar"),
    Device("Device name");

    val members: List<PlayerBlock>
        get() = when (this) {
            Hero -> listOf(PlayerBlock.Artwork)
            Source -> listOf(PlayerBlock.SourceLabel)
            Codec -> listOf(PlayerBlock.AudioInfo)
            Credits -> listOf(PlayerBlock.Title, PlayerBlock.Artist, PlayerBlock.Favorite, PlayerBlock.More)
            LyricLine -> listOf(PlayerBlock.Lyrics)
            SeekBar -> listOf(PlayerBlock.Seek)
            Controls -> listOf(PlayerBlock.Previous, PlayerBlock.PlayPause, PlayerBlock.Next)
            VolumeBar -> listOf(PlayerBlock.Volume)
            BottomBar -> listOf(PlayerBlock.LyricsBtn, PlayerBlock.OutputPill, PlayerBlock.QueueBtn)
            Device -> listOf(PlayerBlock.DeviceName)
        }
}

const val TEXT_SCALE_MIN = 0.6f
const val TEXT_SCALE_MAX = 1.8f

/** Everything we know about one part. All pixel values are root-space px. */
@Stable
class BlockState(
    val block: PlayerBlock,
    startOffset: Offset,
    startScale: Float,
    startVisible: Boolean = true,
    startText: Float = 1f,
    startIcon: Float = 1f,
) {
    /** Shown or hidden. */
    var visible by mutableStateOf(startVisible)

    /** Own text size multiplier. Changes real layout size (not just a transform). */
    var textScale by mutableFloatStateOf(startText)

    /** Own icon / button size multiplier. Changes real layout size too. */
    var iconScale by mutableFloatStateOf(startIcon)

    val offsetAnim = Animatable(startOffset, Offset.VectorConverter)
    val scaleAnim = Animatable(startScale)
    val lift = Animatable(0f)

    // While a finger is on the part these win over the animations.
    var liveOffset by mutableStateOf(startOffset)
    var liveScale by mutableFloatStateOf(startScale)
    var gesturing by mutableStateOf(false)

    /** Where the part sits with no offset / scale (set by layout pass). */
    var home: Rect = Rect.Zero

    /** Where the part rests after animations finish. This is what we save. */
    var targetOffset: Offset = startOffset
    var targetScale: Float = startScale

    /** Bumped on every grab so an old settle cannot cancel a new gesture. */
    var gen = 0

    val offset: Offset get() = if (gesturing) liveOffset else offsetAnim.value
    val scale: Float get() = if (gesturing) liveScale else scaleAnim.value
    val displayScale: Float get() = scale * (1f + LIFT * lift.value)

    fun rectOf(o: Offset, sc: Float): Rect {
        val w = home.width * sc
        val h = home.height * sc
        val c = home.center + o
        return Rect(c.x - w / 2f, c.y - h / 2f, c.x + w / 2f, c.y + h / 2f)
    }

    val rect: Rect get() = rectOf(offset, scale)
    val displayRect: Rect get() = rectOf(offset, displayScale)
    val restRect: Rect get() = rectOf(targetOffset, targetScale)
}

class BlockHit(val block: PlayerBlock, val grip: Boolean)

@Stable
class PlayerLayoutState(
    private val prefs: SharedPreferences,
    private val density: Float,
    private val scope: CoroutineScope,
) {
    val states: Map<PlayerBlock, BlockState> = PlayerBlock.values().associateWith { b ->
        BlockState(
            b,
            Offset(prefs.getFloat("${b.name}_x", 0f) * density, prefs.getFloat("${b.name}_y", 0f) * density),
            prefs.getFloat("${b.name}_s", 1f).coerceIn(b.minScale, b.maxScale),
            // Old versions saved "show_*" flags. Use them when no new value exists.
            prefs.getBoolean("${b.name}_v", b.legacyKey?.let { prefs.getBoolean(it, true) } ?: true),
            prefs.getFloat("${b.name}_t", 1f).coerceIn(TEXT_SCALE_MIN, TEXT_SCALE_MAX),
            prefs.getFloat("${b.name}_i", 1f).coerceIn(b.iconMin, b.iconMax),
        )
    }

    // ── Hero (artwork) shape. Real layout values, not a transform. ──────────
    /** Height as a share of the screen. 0 = automatic (old behaviour). */
    var heroHeight by mutableFloatStateOf(prefs.getFloat("hero_h", 0f))
    /** Width as a share of the screen. */
    var heroWidth by mutableFloatStateOf(prefs.getFloat("hero_w", 1f))
    /** Corner radius in dp. */
    var heroCorner by mutableFloatStateOf(prefs.getFloat("hero_r", 24f))
    /** 0 = no dissolve at the bottom, 1 = full dissolve (default). */
    var heroBottomFade by mutableFloatStateOf(prefs.getFloat("hero_fade", 1f))
    /** 0 = hard side edges, 1 = soft melt into the backdrop (default). */
    var heroSideFeather by mutableFloatStateOf(prefs.getFloat("hero_feather", 1f))

    /** True while the editor is open. Hidden parts show as ghosts then. */
    var editing by mutableStateOf(false)

    // ── Edit UI state ───────────────────────────────────────────────────────
    var selected by mutableStateOf<PlayerBlock?>(null)
    var dropTarget by mutableStateOf<PlayerBlock?>(null)
    var resizingBlock by mutableStateOf<PlayerBlock?>(null)
    var guideX by mutableStateOf<Float?>(null)   // root px
    var guideY by mutableStateOf<Float?>(null)   // root px

    /** Root position of the player container. Set by PlayerScreen. */
    var origin: Offset = Offset.Zero

    /** Usable area in container coordinates. Set by PlayerScreen. */
    private var areaLocal: Rect = Rect.Zero

    fun setArea(rect: Rect) { areaLocal = rect }
    val areaHeight: Float get() = areaLocal.height
    private fun area(): Rect = areaLocal.translate(origin)

    fun state(block: PlayerBlock): BlockState = states.getValue(block)

    fun registerHome(block: PlayerBlock, rect: Rect) { state(block).home = rect }

    // ── Sections ────────────────────────────────────────────────────────────
    /** false = edit single parts, true = edit whole sections. */
    var sectionMode by mutableStateOf(false)
    var selectedSection by mutableStateOf<PlayerSection?>(null)
    var dropSection by mutableStateOf<PlayerSection?>(null)

    fun switchMode(on: Boolean) {
        clearDrop()
        selected = null
        selectedSection = null
        sectionMode = on
    }

    fun selectSection(sec: PlayerSection?) { selectedSection = sec }

    private fun live(sec: PlayerSection): List<BlockState> =
        sec.members.map { state(it) }.filter { it.home.width > 0f }

    private fun unionOf(rs: List<Rect>): Rect? =
        if (rs.isEmpty()) null
        else Rect(rs.minOf { it.left }, rs.minOf { it.top }, rs.maxOf { it.right }, rs.maxOf { it.bottom })

    /** Box around every visible part of the section, as drawn now. */
    fun sectionRect(sec: PlayerSection): Rect? = unionOf(live(sec).map { it.displayRect })
    fun sectionRestRect(sec: PlayerSection): Rect? = unionOf(live(sec).map { it.restRect })

    fun sectionVisible(sec: PlayerSection): Boolean = sec.members.all { state(it).visible }

    fun hitSection(point: Offset, pad: Float): PlayerSection? =
        PlayerSection.values()
            .mapNotNull { sec -> sectionRect(sec)?.takeIf { it.inflate(pad).contains(point) }?.let { sec to it } }
            .minByOrNull { it.second.width * it.second.height }
            ?.first

    private fun range(v: Float, lo: Float, hi: Float): Float =
        if (lo > hi) (lo + hi) / 2f else v.coerceIn(lo, hi)

    /** Slide [d] so that [r] stays inside the screen area. */
    private fun fitDelta(r: Rect, d: Offset): Offset {
        val a = area()
        return Offset(
            range(d.x, a.left - r.left, a.right - r.right),
            range(d.y, a.top - r.top, a.bottom - r.bottom),
        )
    }

    /** Pick up every part of the section. Returns where each part started. */
    fun grabSection(sec: PlayerSection): Map<PlayerBlock, Offset> {
        val ms = live(sec)
        ms.forEach { grab(it.block) }
        selected = null
        selectedSection = sec
        return ms.associate { it.block to it.liveOffset }
    }

    /** Returns true while a snap line is active. */
    fun dragSection(sec: PlayerSection, starts: Map<PlayerBlock, Offset>, total: Offset, snapPx: Float): Boolean {
        val ms = live(sec)
        val r0 = unionOf(ms.map { it.rectOf(starts.getValue(it.block), it.liveScale) }) ?: return false
        val a = area()
        var t = total
        var gx: Float? = null
        if (abs(r0.center.x + t.x - a.center.x) < snapPx) {
            t = Offset(a.center.x - r0.center.x, t.y)
            gx = a.center.x
        }
        t = Offset(
            rubber(t.x, minOf(a.left - r0.left, a.right - r0.right), maxOf(a.left - r0.left, a.right - r0.right)),
            rubber(t.y, minOf(a.top - r0.top, a.bottom - r0.bottom), maxOf(a.top - r0.top, a.bottom - r0.bottom)),
        )
        ms.forEach { it.liveOffset = starts.getValue(it.block) + t }
        guideX = gx
        guideY = null
        updateDropSection(sec, r0.center + t)
        return gx != null
    }

    /** Section under the dragged one slides to the spot you left (live preview). */
    private fun updateDropSection(sec: PlayerSection, center: Offset) {
        val next = if (!sec.swappable) null else {
            val slack = 8f * density
            PlayerSection.values()
                .filter { it != sec && it.swappable }
                .mapNotNull { o -> sectionRestRect(o)?.let { o to it } }
                .filter { it.second.inflate(slack).contains(center) }
                .minByOrNull { it.second.width * it.second.height }
                ?.first
        }
        if (next == dropSection) return
        dropSection?.let { old -> live(old).forEach { nudge(it, it.targetOffset) } }
        next?.let { t ->
            val tr = sectionRestRect(t) ?: return@let
            val from = sectionDragStartCenter ?: return@let
            val d = fitDelta(tr, from - tr.center)
            live(t).forEach { nudge(it, it.targetOffset + d) }
        }
        dropSection = next
    }

    /** Center of the dragged section when the finger went down. */
    private var sectionDragStartCenter: Offset? = null

    fun beginSectionDrag(sec: PlayerSection, starts: Map<PlayerBlock, Offset>) {
        sectionDragStartCenter = unionOf(live(sec).map { it.rectOf(starts.getValue(it.block), it.liveScale) })?.center
    }

    /** Returns true when a swap happened. */
    fun endSectionDrag(sec: PlayerSection, starts: Map<PlayerBlock, Offset>): Boolean {
        val ms = live(sec)
        val target = dropSection
        guideX = null
        guideY = null
        dropSection = null
        val sr = unionOf(ms.map { it.rectOf(starts.getValue(it.block), it.liveScale) })
        val tr = target?.let { sectionRestRect(it) }
        if (target != null && sr != null && tr != null) {
            val dS = fitDelta(sr, tr.center - sr.center)
            val dT = fitDelta(tr, sr.center - tr.center)
            ms.forEach { settle(it, starts.getValue(it.block) + dS, it.liveScale) }
            live(target).forEach { settle(it, it.targetOffset + dT, it.targetScale) }
        } else {
            val now = unionOf(ms.map { it.rect })
            val d = if (now != null) fitDelta(now, Offset.Zero) else Offset.Zero
            ms.forEach { settle(it, it.liveOffset + d, it.liveScale) }
        }
        sectionDragStartCenter = null
        save()
        return target != null
    }

    fun releaseSection(sec: PlayerSection) {
        sectionDragStartCenter = null
        live(sec).forEach { release(it.block) }
    }

    /** Grow or shrink the whole section around its middle. [factor] 1.1 = 10% bigger. */
    fun scaleSection(sec: PlayerSection, factor: Float) {
        val ms = live(sec)
        val c = unionOf(ms.map { it.restRect })?.center ?: return
        ms.forEach { s ->
            val ns = clampScale(s, s.targetScale * factor)
            val f = ns / s.targetScale
            val mc = s.restRect.center
            val o = clampOffset(s, s.targetOffset + ((c + (mc - c) * f) - mc), ns)
            s.targetScale = ns
            s.liveScale = ns
            s.targetOffset = o
            scope.launch { s.scaleAnim.snapTo(ns); s.offsetAnim.snapTo(o) }
        }
    }

    fun setSectionVisible(sec: PlayerSection, on: Boolean) {
        sec.members.forEach { state(it).visible = on }
        save()
    }

    fun resetSection(sec: PlayerSection) {
        sec.members.forEach { resetBlock(it) }
    }

    // ── Limits ──────────────────────────────────────────────────────────────

    private fun usable(s: BlockState): Boolean = s.home.width > 0f && !areaLocal.isEmpty

    /** Allowed offset range so the whole (scaled) part stays inside the area. */
    private fun limits(s: BlockState, scale: Float): Rect {
        val a = area()
        val hw = s.home.width * scale / 2f
        val hh = s.home.height * scale / 2f
        var minX = a.left + hw - s.home.center.x
        var maxX = a.right - hw - s.home.center.x
        var minY = a.top + hh - s.home.center.y
        var maxY = a.bottom - hh - s.home.center.y
        if (minX > maxX) { val m = (minX + maxX) / 2f; minX = m; maxX = m }
        if (minY > maxY) { val m = (minY + maxY) / 2f; minY = m; maxY = m }
        return Rect(minX, minY, maxX, maxY)
    }

    private fun clampOffset(s: BlockState, o: Offset, scale: Float): Offset {
        if (!usable(s)) return o
        val l = limits(s, scale)
        return Offset(o.x.coerceIn(l.left, l.right), o.y.coerceIn(l.top, l.bottom))
    }

    private fun clampScale(s: BlockState, v: Float): Float {
        val b = s.block
        if (!usable(s)) return v.coerceIn(b.minScale, b.maxScale)
        val a = area()
        val fit = minOf(a.width / s.home.width, a.height / s.home.height)
        val hi = minOf(b.maxScale, fit).coerceAtLeast(1f)
        return v.coerceIn(b.minScale, hi)
    }

    /** Over-drag past an edge is slowed down, then springs back on release. */
    private fun rubber(v: Float, lo: Float, hi: Float): Float {
        val max = 36f * density
        return when {
            v < lo -> lo - max * tanh((lo - v) / max)
            v > hi -> hi + max * tanh((v - hi) / max)
            else -> v
        }
    }

    // ── Animation ───────────────────────────────────────────────────────────

    /** Hand the part from the finger to a spring. No visual jump. */
    private fun settle(s: BlockState, offset: Offset, scale: Float) {
        s.targetOffset = offset
        s.targetScale = scale
        val g = s.gen
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            s.offsetAnim.snapTo(s.offset)
            s.scaleAnim.snapTo(s.scale)
            if (s.gen == g) s.gesturing = false
            launch { s.lift.animateTo(0f, LiftSpring) }
            launch { s.scaleAnim.animateTo(scale, ScaleSpring) }
            s.offsetAnim.animateTo(offset, MoveSpring)
        }
    }

    private fun nudge(s: BlockState, o: Offset) {
        scope.launch { s.offsetAnim.animateTo(o, MoveSpring) }
    }

    // ── Gesture API (called by the overlay) ─────────────────────────────────

    fun select(block: PlayerBlock?) { selected = block }

    fun gripCenter(s: BlockState): Offset {
        val r = s.rect
        return Offset(r.right - 10f * density, r.bottom - 10f * density)
    }

    /** Smallest part under the finger wins, so small parts on top of big ones stay reachable. */
    fun hitTest(point: Offset, pad: Float, gripRadius: Float): BlockHit? {
        selected?.let { b ->
            val s = state(b)
            if (s.home.width > 0f && (point - gripCenter(s)).getDistance() <= gripRadius) return BlockHit(b, true)
        }
        val hit = states.values
            .filter { it.home.width > 0f && it.rect.inflate(pad).contains(point) }
            .minByOrNull { it.rect.width * it.rect.height }
            ?: return null
        return BlockHit(hit.block, false)
    }

    fun grab(block: PlayerBlock) {
        val s = state(block)
        s.gen++
        s.liveOffset = s.offsetAnim.value
        s.liveScale = s.scaleAnim.value
        s.gesturing = true
        selected = block
        scope.launch { s.lift.animateTo(1f, LiftSpring) }
    }

    /** Returns true while a snap line is active. */
    fun dragTo(block: PlayerBlock, startOffset: Offset, total: Offset, snapPx: Float, marginPx: Float): Boolean {
        val s = state(block)
        var o = startOffset + total
        var gx: Float? = null
        var gy: Float? = null
        if (usable(s)) {
            val a = area()
            val hw = s.home.width * s.liveScale / 2f
            val cx = s.home.center.x
            // (offset that snaps, guide line x)
            val xs = listOf(
                0f to cx,
                (a.center.x - cx) to a.center.x,
                (a.left + marginPx + hw - cx) to (a.left + marginPx),
                (a.right - marginPx - hw - cx) to (a.right - marginPx),
            )
            xs.minByOrNull { abs(o.x - it.first) }
                ?.takeIf { abs(o.x - it.first) < snapPx }
                ?.let { o = Offset(it.first, o.y); gx = it.second }
            if (abs(o.y) < snapPx) { o = Offset(o.x, 0f); gy = s.home.center.y }
            val l = limits(s, s.liveScale)
            o = Offset(rubber(o.x, l.left, l.right), rubber(o.y, l.top, l.bottom))
        }
        s.liveOffset = o
        guideX = gx
        guideY = gy
        updateDrop(block, startOffset)
        return gx != null || gy != null
    }

    /** Finds the part under the dragged one and slides it to the vacated spot as a preview. */
    private fun updateDrop(block: PlayerBlock, startOffset: Offset) {
        val s = state(block)
        val next = if (!block.swappable) null else {
            val c = s.home.center + s.liveOffset
            val slack = 8f * density
            // Rest rects, not live rects: the preview moves the target, and a live
            // rect would make the highlight flicker on and off.
            states.values
                .filter { it.block != block && it.block.swappable && it.home.width > 0f && it.restRect.inflate(slack).contains(c) }
                .minByOrNull { it.restRect.width * it.restRect.height }
                ?.block
        }
        if (next == dropTarget) return
        dropTarget?.let { old -> state(old).let { nudge(it, it.targetOffset) } }
        next?.let { t ->
            val ts = state(t)
            nudge(ts, clampOffset(ts, s.home.center + startOffset - ts.home.center, ts.targetScale))
        }
        dropTarget = next
    }

    fun clearDrop() {
        dropSection?.let { old -> live(old).forEach { nudge(it, it.targetOffset) } }
        dropSection = null
        dropTarget?.let { old -> state(old).let { nudge(it, it.targetOffset) } }
        dropTarget = null
        guideX = null
        guideY = null
    }

    /** Returns true when a swap happened. */
    fun endDrag(block: PlayerBlock, startOffset: Offset): Boolean {
        val s = state(block)
        val target = dropTarget?.let { state(it) }
        guideX = null
        guideY = null
        dropTarget = null
        if (target != null) {
            val startCenter = s.home.center + startOffset
            val newA = clampOffset(s, target.restRect.center - s.home.center, s.liveScale)
            val newB = clampOffset(target, startCenter - target.home.center, target.targetScale)
            settle(s, newA, s.liveScale)
            settle(target, newB, target.targetScale)
        } else {
            settle(s, clampOffset(s, s.liveOffset, s.liveScale), s.liveScale)
        }
        save()
        return target != null
    }

    /** Returns true when the scale snapped to 100%. */
    fun resizeTo(block: PlayerBlock, raw: Float): Boolean {
        val s = state(block)
        var v = clampScale(s, raw)
        val snapped = abs(v - 1f) < 0.03f
        if (snapped) v = 1f
        s.liveScale = v
        s.liveOffset = clampOffset(s, s.liveOffset, v)
        resizingBlock = block
        return snapped
    }

    fun endResize(block: PlayerBlock) {
        val s = state(block)
        resizingBlock = null
        clearDrop()
        settle(s, clampOffset(s, s.liveOffset, s.liveScale), s.liveScale)
        save()
    }

    /** Finger went down and up with no move. */
    fun release(block: PlayerBlock) {
        val s = state(block)
        settle(s, s.targetOffset, s.targetScale)
    }

    /** Pull parts back inside the screen, e.g. after a size change. */
    fun clampAll() {
        states.values.forEach { s ->
            if (s.gesturing || !usable(s)) return@forEach
            val sc = clampScale(s, s.targetScale)
            val o = clampOffset(s, s.targetOffset, sc)
            if (o != s.targetOffset || sc != s.targetScale) settle(s, o, sc)
        }
    }

    fun save() {
        prefs.edit().apply {
            states.forEach { (b, s) ->
                putFloat("${b.name}_x", s.targetOffset.x / density)
                putFloat("${b.name}_y", s.targetOffset.y / density)
                putFloat("${b.name}_s", s.targetScale)
                putBoolean("${b.name}_v", s.visible)
                putFloat("${b.name}_t", s.textScale)
                putFloat("${b.name}_i", s.iconScale)
            }
            putFloat("hero_h", heroHeight)
            putFloat("hero_w", heroWidth)
            putFloat("hero_r", heroCorner)
            putFloat("hero_fade", heroBottomFade)
            putFloat("hero_feather", heroSideFeather)
        }.apply()
    }

    // ── Inspector API ───────────────────────────────────────────────────────

    /** Set size from a slider. No gesture needed. */
    fun setScale(block: PlayerBlock, v: Float) {
        val s = state(block)
        val sc = clampScale(s, v)
        s.targetScale = sc
        s.liveScale = sc
        scope.launch { s.scaleAnim.snapTo(sc) }
        s.targetOffset = clampOffset(s, s.targetOffset, sc)
        scope.launch { s.offsetAnim.snapTo(s.targetOffset) }
    }

    fun setVisible(block: PlayerBlock, on: Boolean) {
        state(block).visible = on
        save()
    }

    /** One block back to default: spot, size, text, icon, visible. */
    fun resetBlock(block: PlayerBlock) {
        val s = state(block)
        s.visible = true
        s.textScale = 1f
        s.iconScale = 1f
        settle(s, Offset.Zero, 1f)
        if (block == PlayerBlock.Artwork) resetHero()
        save()
    }

    fun resetHero() {
        heroHeight = 0f
        heroWidth = 1f
        heroCorner = 24f
        heroBottomFade = 1f
        heroSideFeather = 1f
    }

    fun reset() {
        clearDrop()
        selected = null
        resizingBlock = null
        states.values.forEach {
            it.visible = true
            it.textScale = 1f
            it.iconScale = 1f
            settle(it, Offset.Zero, 1f)
        }
        resetHero()
        save()
    }
}

@Composable
fun rememberPlayerLayout(): PlayerLayoutState {
    val context = LocalContext.current
    val density = LocalDensity.current.density
    val scope = rememberCoroutineScope()
    return remember {
        PlayerLayoutState(
            context.applicationContext.getSharedPreferences("player_layout_v2", Context.MODE_PRIVATE),
            density,
            scope,
        )
    }
}

/** Records where the part sits with no offset. Put it BEFORE any graphicsLayer on the part. */
fun Modifier.blockHome(layout: PlayerLayoutState, block: PlayerBlock): Modifier =
    onGloballyPositioned { c ->
        layout.registerHome(block, Rect(c.positionInRoot(), c.size.toSize()))
    }

/**
 * Home tracking + offset + scale + pick-up lift.
 *
 * Hidden part:
 *  • editor closed + [collapse]  → takes no room at all
 *  • editor closed + !collapse   → keeps its room, draws nothing, cannot be tapped
 *  • editor open                 → faint ghost, so it can still be found and turned back on
 */
fun Modifier.editableBlock(ls: PlayerLayoutState, block: PlayerBlock, collapse: Boolean = true): Modifier {
    val s = ls.state(block)
    val off = !s.visible && !ls.editing
    if (off) {
        ls.registerHome(block, Rect.Zero)
        return if (collapse) {
            this.layout { _, _ -> layout(0, 0) {} }
        } else {
            this.layout { m, c ->
                val p = m.measure(c)
                layout(p.width, p.height) {}   // measured, never placed
            }
        }
    }
    return this.blockHome(ls, block).graphicsLayer {
        val o = s.offset
        val sc = s.displayScale
        translationX = o.x
        translationY = o.y
        scaleX = sc
        scaleY = sc
        alpha = if (s.visible) 1f else 0.22f
    }
}

/**
 * Full-screen edit layer. Draws frames, guides and handles, and owns every touch:
 *  • touch a part and drag      → move (with snap + swap)
 *  • drag the corner dot        → resize
 *  • two fingers on a part      → pinch resize
 *  • tap empty space            → deselect
 */
@Composable
fun PlayerEditOverlay(
    layout: PlayerLayoutState,
    statusTop: Dp,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    val accent = ChordTheme.accent
    val measurer = rememberTextMeasurer()
    var hint by remember { mutableStateOf(true) }

    val selAnim: Map<PlayerBlock, State<Float>> = PlayerBlock.values().associateWith { b ->
        animateFloatAsState(if (layout.selected == b) 1f else 0f, tween(160), label = "sel_${b.name}")
    }
    val tgtAnim: Map<PlayerBlock, State<Float>> = PlayerBlock.values().associateWith { b ->
        animateFloatAsState(if (layout.dropTarget == b) 1f else 0f, tween(140), label = "tgt_${b.name}")
    }

    Box(modifier.fillMaxSize()) {
        Canvas(
            Modifier.fillMaxSize().pointerInput(layout) {
                val slop = viewConfiguration.touchSlop
                val pad = 10.dp.toPx()
                val gripHit = 26.dp.toPx()
                val snapPx = 6.dp.toPx()
                val marginPx = EDIT_MARGIN.toPx()

                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    hint = false
                    if (layout.sectionMode) {
                        sectionGesture(layout, down, slop, pad, snapPx, haptics)
                        return@awaitEachGesture
                    }
                    val origin = layout.origin
                    val hit = layout.hitTest(down.position + origin, pad, gripHit)

                    if (hit == null) {
                        do {
                            val e = awaitPointerEvent()
                            e.changes.forEach { it.consume() }
                        } while (e.changes.any { it.pressed })
                        layout.select(null)
                        return@awaitEachGesture
                    }

                    val block = hit.block
                    val s = layout.state(block)
                    layout.grab(block)
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)

                    val resizeMode = hit.grip
                    val startOffset = s.liveOffset
                    val startScale = s.liveScale
                    val center = s.rect.center
                    val startDist = (down.position + origin - center).getDistance().coerceAtLeast(1f)
                    var total = Offset.Zero
                    var moved = false
                    var pinching = false
                    var pinchDist = 1f
                    var pinchScale = startScale
                    var wasSnapped = false
                    var lastTarget: PlayerBlock? = null
                    var scaleSnapped = false

                    while (true) {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.filter { it.pressed }
                        if (pressed.isEmpty()) {
                            event.changes.forEach { it.consume() }
                            break
                        }
                        if (pressed.size >= 2) {
                            val d = (pressed[0].position - pressed[1].position).getDistance().coerceAtLeast(1f)
                            if (!pinching) {
                                pinching = true
                                pinchDist = d
                                pinchScale = s.liveScale
                                layout.clearDrop()
                            } else {
                                val snapped = layout.resizeTo(block, pinchScale * d / pinchDist)
                                if (snapped && !scaleSnapped) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                scaleSnapped = snapped
                            }
                        } else if (!pinching) {
                            val c = pressed.first()
                            if (resizeMode) {
                                moved = true
                                val dist = (c.position + origin - center).getDistance()
                                val snapped = layout.resizeTo(block, startScale * dist / startDist)
                                if (snapped && !scaleSnapped) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                scaleSnapped = snapped
                            } else {
                                total += c.position - c.previousPosition
                                if (!moved && total.getDistance() > slop) moved = true
                                if (moved) {
                                    val snapped = layout.dragTo(block, startOffset, total, snapPx, marginPx)
                                    if (snapped && !wasSnapped) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    wasSnapped = snapped
                                    val t = layout.dropTarget
                                    if (t != null && t != lastTarget) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    lastTarget = t
                                }
                            }
                        }
                        event.changes.forEach { it.consume() }
                    }

                    when {
                        pinching || resizeMode -> layout.endResize(block)
                        moved -> if (layout.endDrag(block, startOffset)) {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        }
                        else -> layout.release(block)
                    }
                }
            }
        ) {
            val o = layout.origin
            val corner = CornerRadius(12.dp.toPx())
            val dash = PathEffect.dashPathEffect(floatArrayOf(9.dp.toPx(), 7.dp.toPx()))

            if (layout.sectionMode) {
                PlayerSection.values().forEach { sec ->
                    val rect = layout.sectionRect(sec) ?: return@forEach
                    val r = rect.inflate(4.dp.toPx()).translate(-o)
                    val sel = layout.selectedSection == sec
                    val tgt = layout.dropSection == sec
                    drawRoundRect(Color.White.copy(alpha = if (sel) 0.08f else 0.03f), r.topLeft, r.size, corner)
                    if (tgt) drawRoundRect(accent.copy(alpha = 0.26f), r.topLeft, r.size, corner)
                    if (sel || tgt) {
                        drawRoundRect(accent, r.topLeft, r.size, corner, style = Stroke(width = 2.dp.toPx()))
                    } else {
                        drawRoundRect(
                            Color.White.copy(alpha = 0.26f), r.topLeft, r.size, corner,
                            style = Stroke(width = 0.8.dp.toPx(), pathEffect = dash),
                        )
                    }
                    val label = measurer.measure(sec.label, TextStyle(fontSize = 10.sp, fontWeight = FontWeight.SemiBold))
                    val lw = label.size.width + 12.dp.toPx()
                    val lh = label.size.height + 4.dp.toPx()
                    val lx = r.left.coerceIn(2.dp.toPx(), size.width - lw - 2.dp.toPx())
                    val ly = (r.top - lh + 2.dp.toPx()).coerceAtLeast(2.dp.toPx())
                    drawRoundRect(if (sel || tgt) accent else Color(0xCC171717), Offset(lx, ly), Size(lw, lh), CornerRadius(lh / 2f))
                    drawText(label, color = if (sel || tgt) Color.Black else Color.White, topLeft = Offset(lx + 6.dp.toPx(), ly + 2.dp.toPx()))
                }
            } else layout.states.values.forEach { s ->
                if (s.home.width <= 0f) return@forEach
                val sel = selAnim.getValue(s.block).value
                val tgt = tgtAnim.getValue(s.block).value
                val r = s.displayRect.translate(-o)
                val tl = r.topLeft
                val sz = r.size

                drawRoundRect(Color.White.copy(alpha = 0.015f + 0.04f * sel), tl, sz, corner)
                if (tgt > 0f) drawRoundRect(accent.copy(alpha = 0.26f * tgt), tl, sz, corner)
                if (sel < 1f) {
                    drawRoundRect(
                        Color.White.copy(alpha = 0.26f * (1f - sel)), tl, sz, corner,
                        style = Stroke(width = 0.8.dp.toPx(), pathEffect = dash),
                    )
                }
                val strong = maxOf(sel, tgt)
                if (strong > 0f) {
                    drawRoundRect(accent.copy(alpha = strong), tl, sz, corner, style = Stroke(width = 1.5.dp.toPx()))
                }
                if (sel > 0f) {
                    val g = layout.gripCenter(s) - o
                    drawCircle(Color.White.copy(alpha = sel), radius = 7.dp.toPx(), center = g)
                    drawCircle(accent.copy(alpha = sel), radius = 3.5.dp.toPx(), center = g)
                }
            }

            layout.guideX?.let { x ->
                drawLine(accent.copy(alpha = 0.85f), Offset(x - o.x, 0f), Offset(x - o.x, size.height), strokeWidth = 1.dp.toPx(), pathEffect = dash)
            }
            layout.guideY?.let { y ->
                drawLine(accent.copy(alpha = 0.85f), Offset(0f, y - o.y), Offset(size.width, y - o.y), strokeWidth = 1.dp.toPx(), pathEffect = dash)
            }

            // Live size label while resizing.
            layout.resizingBlock?.let { b ->
                val s = layout.state(b)
                val text = measurer.measure("${(s.liveScale * 100f).roundToInt()}%", TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold))
                val r = s.displayRect.translate(-o)
                val w = text.size.width + 20.dp.toPx()
                val h = text.size.height + 8.dp.toPx()
                val x = (r.center.x - w / 2f).coerceIn(4.dp.toPx(), size.width - w - 4.dp.toPx())
                val y = (r.top - h - 8.dp.toPx()).coerceAtLeast(statusTop.toPx() + 4.dp.toPx())
                drawRoundRect(Color(0xE6171717), Offset(x, y), Size(w, h), CornerRadius(h / 2f))
                drawText(text, color = Color.White, topLeft = Offset(x + 10.dp.toPx(), y + 4.dp.toPx()))
            }
        }

        AnimatedVisibility(
            visible = hint,
            modifier = Modifier.align(Alignment.TopCenter).padding(top = statusTop + 60.dp, start = 24.dp, end = 24.dp),
            enter = fadeIn(tween(220)),
            exit = fadeOut(tween(160)),
        ) {
            Text(
                "Drag to move  ·  corner dot or pinch to resize  ·  use the panel for text, icon and hero size",
                color = Color.White.copy(alpha = 0.9f),
                fontSize = 11.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0xB8171717))
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }
    }
}

/** One finger on a whole section: drag it, snap it, swap it with another section. */
private suspend fun AwaitPointerEventScope.sectionGesture(
    layout: PlayerLayoutState,
    down: PointerInputChange,
    slop: Float,
    pad: Float,
    snapPx: Float,
    haptics: HapticFeedback,
) {
    val sec = layout.hitSection(down.position + layout.origin, pad)
    if (sec == null) {
        do {
            val e = awaitPointerEvent()
            e.changes.forEach { it.consume() }
        } while (e.changes.any { it.pressed })
        layout.selectSection(null)
        return
    }
    val starts = layout.grabSection(sec)
    layout.beginSectionDrag(sec, starts)
    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)

    var total = Offset.Zero
    var moved = false
    var wasSnapped = false
    var lastTarget: PlayerSection? = null
    while (true) {
        val e = awaitPointerEvent()
        val pressed = e.changes.filter { it.pressed }
        if (pressed.isEmpty()) {
            e.changes.forEach { it.consume() }
            break
        }
        val c = pressed.first()
        total += c.position - c.previousPosition
        if (!moved && total.getDistance() > slop) moved = true
        if (moved) {
            val snapped = layout.dragSection(sec, starts, total, snapPx)
            if (snapped && !wasSnapped) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            wasSnapped = snapped
            val t = layout.dropSection
            if (t != null && t != lastTarget) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            lastTarget = t
        }
        e.changes.forEach { it.consume() }
    }
    if (moved) {
        if (layout.endSectionDrag(sec, starts)) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
    } else {
        layout.releaseSection(sec)
    }
}

/** Compact edit controls. Changes are saved as the user edits. */
@Composable
fun LayoutEditBar(
    layout: PlayerLayoutState,
    statusTop: Dp,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .padding(top = statusTop + 8.dp, end = 14.dp)
            .clip(RoundedCornerShape(50))
            .background(Color(0xB8171717))
            .padding(horizontal = 5.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(
            onClick = { layout.reset() },
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
        ) { Text("Reset", color = Color.White.copy(alpha = 0.78f), fontSize = 12.sp) }
        TextButton(
            onClick = { layout.save(); onDone() },
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
        ) { Text("Done", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold) }
    }
}
