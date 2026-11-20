@file:OptIn(ExperimentalFoundationApi::class)

package com.example.chord.ui.home

import com.example.chord.ui.theme.ChordTheme
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.ViewCarousel
import androidx.compose.material.icons.rounded.ViewModule
import com.example.chord.data.settings.AppSettings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.chord.data.home.HomeItem
import com.example.chord.data.mixes.MixEngine
import com.example.chord.data.home.HomeItemType
import com.example.chord.data.home.HomeShelf
import com.example.chord.data.home.PickCard
import com.example.chord.data.home.PickKind
import com.example.chord.data.home.PickMood
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.lerp
import com.example.chord.data.home.ShelfStyle
import com.example.chord.ui.components.CoverArt
import com.example.chord.ui.components.CoverImage
import com.example.chord.ui.components.MediaRow
import com.example.chord.ui.components.NowPlayingOverlay
import com.example.chord.ui.components.pressScale
import com.example.chord.ui.theme.ChordType
import com.example.chord.ui.theme.rememberArtColors
import androidx.compose.material.icons.rounded.Casino

internal val Bg: Color get() = ChordTheme.bg
internal val Dim = Color.White.copy(alpha = 0.60f)
internal val PageGutter = 18.dp

private val TileFill = Color.White.copy(alpha = 0.08f)
private val CardGap = 12.dp

/**
 * One shelf, drawn by its style. Used by Home, Explore and Artist pages.
 *   Hero      one big cover card with a Play pill
 *   SpeedDial 2 x 3 compact tiles
 *   Big       wide cards row
 *   Auto      cards row, or Quick-picks pages when it is a song list
 */
@Composable
fun Shelf(
    shelf: HomeShelf,
    nowPlayingId: String?,
    isPlaying: Boolean,
    onClick: (HomeItem) -> Unit,
    onRefreshLastFm: () -> Unit = { MixEngine.refreshLastFm() },
) {
    if (shelf.title == "Last.fm") {
        Column(Modifier.padding(top = 30.dp)) {
            LastFmSectionHeader(onRefreshLastFm)
            shelf.children.forEach { child ->
                Shelf(child, nowPlayingId, isPlaying, onClick, onRefreshLastFm)
            }
            if (shelf.children.isEmpty()) {
                Text(
                    "Your Last.fm profile will appear here after your account has listening data.",
                    style = ChordType.CardSub,
                    color = Dim,
                    modifier = Modifier.padding(horizontal = PageGutter, vertical = 8.dp),
                )
            }
        }
        return
    }
    if (shelf.style == ShelfStyle.Hero) {
        shelf.items.firstOrNull()?.let { HeroCard(it, shelf.strapline, nowPlayingId, isPlaying, onClick) }
        return
    }
    Column(Modifier.padding(top = 30.dp)) {
        when {
            shelf.style == ShelfStyle.SpeedDial -> SpeedDialHeader(shelf.title)
            else -> SectionHeader(shelf.title, shelf.strapline)
        }
        when {
            shelf.style == ShelfStyle.TopPicks -> TopPicksRow(shelf, nowPlayingId, isPlaying, onClick)
            shelf.style == ShelfStyle.SpeedDial -> SpeedDial(shelf.items, nowPlayingId, isPlaying, onClick)
            shelf.isList && shelf.items.all { it.videoId != null } -> ListShelf(shelf, nowPlayingId, isPlaying, onClick)
            else -> CardRow(
                shelf, if (shelf.style == ShelfStyle.Big) 168.dp else 146.dp,
                nowPlayingId, isPlaying, onClick,
            )
        }
    }
}

@Composable
internal fun LastFmSectionHeader(onRefresh: () -> Unit = { MixEngine.refreshLastFm() }) {
    val mixes by MixEngine.mixes.collectAsState()
    val busy by MixEngine.lastFmBusy.collectAsState()
    val count = mixes.count { it.group.name == "LastFm" }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = PageGutter).padding(bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Last.fm", style = ChordType.Section, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                when {
                    busy -> "Updating your listening profile…"
                    count == 0 -> if (AppSettings.lastFmUser.isNotBlank() || AppSettings.lastFmImportUser.isNotBlank()) "Your listening profile and discoveries" else "Your Last.fm recommendations"
                    else -> "$count personalised mixes · charts and discoveries"
                },
                style = ChordType.CardSub, color = Dim, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Box(
            Modifier.size(36.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.10f))
                .pressScale(pressedScale = 0.9f, onClick = { if (!busy) onRefresh() }),
            contentAlignment = Alignment.Center,
        ) {
            if (busy) androidx.compose.material3.CircularProgressIndicator(Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp)
            else Icon(Icons.Rounded.Refresh, contentDescription = "Rebuild Last.fm recommendations", tint = Color.White, modifier = Modifier.size(19.dp))
        }
    }
}

@Composable
internal fun SectionHeader(title: String, eyebrow: String, modifier: Modifier = Modifier) {
    // Title only. Quiet and roomy. [eyebrow] stays in the signature for old callers.
    Text(
        title, style = ChordType.Section, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis,
        modifier = modifier.padding(horizontal = PageGutter).padding(bottom = 14.dp),
    )
}

// ---- Hero ---------------------------------------------------------------------

@Composable
private fun HeroCard(
    item: HomeItem,
    eyebrow: String,
    nowPlayingId: String?,
    isPlaying: Boolean,
    onClick: (HomeItem) -> Unit,
) {
    val shape = RoundedCornerShape(26.dp)
    val playing = item.videoId != null && item.videoId == nowPlayingId

    Box(
        Modifier.padding(horizontal = PageGutter, vertical = 4.dp).fillMaxWidth().aspectRatio(1.1f)
            .clip(shape).background(Color(0xFF1A1A1A))
            .pressScale(pressedScale = 0.975f, onClick = { onClick(item) }),
    ) {
        CoverArt(item.thumbnailUrl, 720, Modifier.fillMaxSize())
        // Fade into the cover's own colour so the text always reads.
        Box(
            Modifier.matchParentSize().drawBehind {
                drawRect(
                    Brush.verticalGradient(
                        0.30f to Color.Transparent,
                        1f to Color.Black.copy(alpha = 0.92f),
                    )
                )
            }
        )
        Column(Modifier.align(Alignment.BottomStart).padding(20.dp)) {
            if (eyebrow.isNotBlank()) {
                Text(eyebrow.uppercase(), style = ChordType.Eyebrow, color = Color.White.copy(alpha = 0.78f), maxLines = 1)
            }
            Text(
                item.title, style = ChordType.Hero, color = Color.White,
                maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp),
            )
            if (item.subtitle.isNotBlank()) {
                Text(
                    item.subtitle, style = ChordType.CardSub.copy(fontSize = 13.sp), color = Color.White.copy(alpha = 0.82f),
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            Row(
                Modifier.padding(top = 16.dp).clip(RoundedCornerShape(50)).background(Color.White)
                    .padding(start = 14.dp, end = 20.dp, top = 9.dp, bottom = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.PlayArrow, null, tint = Color.Black, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(6.dp))
                Text(if (playing && isPlaying) "Playing" else "Play", style = ChordType.Label, color = Color.Black)
            }
        }
    }
}

// ---- Top picks ----------------------------------------------------------------

private val PickShape = RoundedCornerShape(26.dp)

/** Fallback when a shelf has no typed cards: plain cover cards from the items. */
private fun HomeShelf.cards(): List<PickCard> = picks.ifEmpty {
    items.map { i ->
        PickCard(
            PickKind.Item, i.title,
            when (i.type) { HomeItemType.Artist -> "Artist"; HomeItemType.Album -> "Album"; HomeItemType.Playlist -> "Playlist"; else -> "Song" },
            i.subtitle, listOf(i.thumbnailUrl), i,
        )
    }
}

/** Tall cards that snap, ~62% of the screen wide, next card peeking. Dots show where you are. */
@Composable
private fun TopPicksRow(
    shelf: HomeShelf,
    nowPlayingId: String?,
    isPlaying: Boolean,
    onClick: (HomeItem) -> Unit,
) {
    val cards = remember(shelf) { shelf.cards() }
    val width = (LocalConfiguration.current.screenWidthDp * 0.62f).dp
    val state = rememberLazyListState()
    Column {
        LazyRow(
            state = state,
            flingBehavior = rememberSnapFlingBehavior(state),
            contentPadding = PaddingValues(horizontal = PageGutter),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            itemsIndexed(cards, key = { i, c -> c.kind.name + (c.open?.videoId ?: c.open?.browseId ?: c.title) + i }, contentType = { _, c -> c.kind }) { _, card ->
                val open = card.open
                val playing = open?.videoId != null && open.videoId == nowPlayingId
                PickCardView(
                    card = card,
                    label = if (playing) (if (isPlaying) "Playing now" else "Paused") else card.label,
                    width = width,
                    onClick = { open?.let(onClick) },
                )
            }
        }
        if (cards.size > 1) {
            Row(
                Modifier.fillMaxWidth().padding(top = 14.dp),
                horizontalArrangement = Arrangement.Center,
            ) {
                val current by remember {
                    derivedStateOf {
                        state.firstVisibleItemIndex.coerceIn(0, cards.lastIndex)
                    }
                }
                cards.indices.take(8).forEach { i ->
                    val on = i == current
                    Box(
                        Modifier.padding(horizontal = 3.dp).size(width = if (on) 16.dp else 6.dp, height = 6.dp)
                            .clip(CircleShape).background(Color.White.copy(alpha = if (on) 0.9f else 0.25f)),
                    )
                }
            }
        }
    }
}

@Composable
private fun PickCardView(card: PickCard, label: String, width: Dp, onClick: () -> Unit) {
    Box(
        Modifier.width(width).aspectRatio(0.75f).clip(PickShape).background(Color(0xFF17171A))
            .border(1.dp, Color.White.copy(alpha = 0.10f), PickShape)
            .pressScale(pressedScale = 0.97f, onClick = onClick),
    ) {
        when (card.kind) {
            PickKind.NewRelease -> NewReleaseFace(card, label)
            PickKind.Similar -> SimilarFace(card, label)
            PickKind.Mood -> MoodFace(card, label)
            PickKind.Rotation -> RotationFace(card, label)
            PickKind.Item -> ItemFace(card, label)
        }
    }
}

@Composable
private fun CardText(label: String, title: String, subtitle: String, modifier: Modifier = Modifier, titleSize: Int = 22, subLines: Int = 2) {
    Column(modifier.padding(horizontal = 18.dp, vertical = 16.dp)) {
        Text(label, style = ChordType.CardSub.copy(fontSize = 14.sp), color = Color.White.copy(alpha = 0.72f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            title, style = ChordType.CardTitle.copy(fontSize = titleSize.sp, lineHeight = (titleSize + 4).sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold),
            color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp),
        )
        if (subtitle.isNotBlank()) Text(
            subtitle, style = ChordType.CardSub.copy(fontSize = 15.sp, lineHeight = 20.sp), color = Color.White.copy(alpha = 0.78f),
            maxLines = subLines, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp),
        )
    }
}

/** Three small waves, the Chord mark, for the corner of coloured cards. */
@Composable
private fun WaveMark(modifier: Modifier = Modifier) {
    androidx.compose.foundation.Canvas(modifier.size(width = 30.dp, height = 22.dp)) {
        val stroke = androidx.compose.ui.graphics.drawscope.Stroke(width = size.height * 0.17f, cap = androidx.compose.ui.graphics.StrokeCap.Round)
        repeat(3) { i ->
            val y = size.height * (0.16f + 0.34f * i)
            val p = androidx.compose.ui.graphics.Path().apply {
                moveTo(size.width * 0.04f, y + size.height * 0.10f)
                quadraticBezierTo(size.width * 0.5f, y - size.height * 0.16f, size.width * 0.96f, y + size.height * 0.04f)
            }
            drawPath(p, Color.White, style = stroke)
        }
    }
}

//@Composable
//private fun WaveMark(modifier: Modifier = Modifier) {
//    androidx.compose.foundation.Canvas(modifier = modifier.size(30.dp)) {
//        val radius = size.minDimension / 2f
//        val center = androidx.compose.ui.geometry.Offset(
//            size.width / 2f,
//            size.height / 2f
//        )
//
//        val circle = androidx.compose.ui.graphics.Path().apply {
//            addOval(
//                androidx.compose.ui.geometry.Rect(
//                    center.x - radius,
//                    center.y - radius,
//                    center.x + radius,
//                    center.y + radius
//                )
//            )
//        }
//
//        val lower = androidx.compose.ui.graphics.Path().apply {
//            moveTo(0f, size.height * 0.72f)
//            lineTo(size.width, size.height * 0.55f)
//            lineTo(size.width, size.height)
//            lineTo(0f, size.height)
//            close()
//        }
//
//        clipPath(circle) {
//            // Base grey: visible as the diagonal separator
//            drawCircle(
//                color = Color(0xFF858585),
//                radius = radius,
//                center = center
//            )
//
//            // Light upper section
//            val upper = androidx.compose.ui.graphics.Path().apply {
//                moveTo(0f, 0f)
//                lineTo(size.width, 0f)
//                lineTo(size.width, size.height * 0.52f)
//                lineTo(0f, size.height * 0.67f)
//                close()
//            }
//
//            drawPath(upper, Color(0xFFE0E0E0))
//
//            // Dark lower section
//            val lower = androidx.compose.ui.graphics.Path().apply {
//                moveTo(0f, size.height * 0.78f)
//                lineTo(size.width, size.height * 0.63f)
//                lineTo(size.width, size.height)
//                lineTo(0f, size.height)
//                close()
//            }
//
//            drawPath(lower, Color(0xFF353535))
//        }
//    }
//}

/** "Made for You". Newest release as full art, the title huge over it. */
@Composable
private fun BoxScope.NewReleaseFace(card: PickCard, label: String) {
    CoverArt(card.images.firstOrNull(), 720, Modifier.fillMaxSize())
    Box(
        Modifier.matchParentSize().drawBehind {
            drawRect(Brush.verticalGradient(0.30f to Color.Transparent, 0.70f to Color.Black.copy(alpha = 0.35f), 1f to Color.Black.copy(alpha = 0.85f)))
        }
    )
    Icon(
        Icons.Rounded.PlayArrow, null, tint = Color.White,
        modifier = Modifier.align(Alignment.TopStart).padding(14.dp).size(30.dp)
            .border(1.5.dp, Color.White, CircleShape).padding(3.dp),
    )
    Text(
        card.title.split(" ").take(3).joinToString("\n"),
        style = ChordType.Hero.copy(fontSize = 54.sp, lineHeight = 50.sp, letterSpacing = (-2.5).sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium),
        color = Color.White.copy(alpha = 0.88f),
        modifier = Modifier.align(Alignment.BottomStart).padding(start = 18.dp, bottom = 118.dp),
    )
    CardText(label, card.title, card.subtitle, Modifier.align(Alignment.BottomStart))
}

/** Artist + similar: a colour taken from the art, three round photos. */
@Composable
private fun BoxScope.SimilarFace(card: PickCard, label: String) {
    val colors = rememberArtColors(card.images.firstOrNull())
    Box(
        Modifier.matchParentSize().drawBehind {
            drawRect(
                Brush.verticalGradient(
                    0f to lerp(colors.mid.value, colors.accent.value, 0.25f),
                    1f to lerp(colors.mid.value, colors.accent.value, 0.75f),
                )
            )
        }
    )
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.matchParentSize()) {
        val w = maxWidth
        val spots = listOf(
            Triple(0.07f, 0.046f, 0.594f),
            Triple(0.59f, 0.47f, 0.333f),
            Triple(0.31f, 0.665f, 0.29f),
        )
        card.images.filterNotNull().take(3).forEachIndexed { i, url ->
            val (x, y, d) = spots[i]
            Box(
                Modifier.offset(w * x, w * y).size(w * d).clip(CircleShape).background(Color(0xFF0A1414)),
            ) { CoverArt(url, 400, Modifier.fillMaxSize()) }
        }
    }
    WaveMark(Modifier.align(Alignment.TopEnd).padding(16.dp))
    CardText(label, card.title, "", Modifier.align(Alignment.BottomStart), subLines = 0)
}

/** Mood: a flat colour, a dashed outline of a shape, short line of text. */
@Composable
private fun BoxScope.MoodFace(card: PickCard, label: String) {
    val mood = card.mood
    val top = Color(mood?.top ?: 0xFFEF3B26)
    val bottom = Color(mood?.bottom ?: 0xFFE5311F)
    Box(
        Modifier.matchParentSize().drawBehind {
            drawRect(Brush.verticalGradient(listOf(top, bottom)))
            val dash = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(34f, 26f))
            val stroke = androidx.compose.ui.graphics.drawscope.Stroke(width = 4.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round, pathEffect = dash)
            val line = Color.White.copy(alpha = 0.55f)
            val w = size.width
            val h = size.height
            when (mood) {
                PickMood.Heartbreak, PickMood.Workout, null -> {
                    // heart, big and cut by the left edge
                    val cx = w * 0.42f
                    val cy = h * 0.36f
                    val s = w * 0.62f
                    val p = androidx.compose.ui.graphics.Path().apply {
                        moveTo(cx, cy + s * 0.72f)
                        cubicTo(cx - s * 1.25f, cy + s * 0.05f, cx - s * 0.75f, cy - s * 0.95f, cx, cy - s * 0.35f)
                        cubicTo(cx + s * 0.75f, cy - s * 0.95f, cx + s * 1.25f, cy + s * 0.05f, cx, cy + s * 0.72f)
                    }
                    drawPath(p, line, style = stroke)
                }
                PickMood.RiseShine -> {
                    val c = androidx.compose.ui.geometry.Offset(w * 0.5f, h * 0.30f)
                    drawCircle(line, radius = w * 0.20f, center = c, style = stroke)
                    drawCircle(line, radius = w * 0.34f, center = c, style = stroke)
                }
                PickMood.Focus -> {
                    val c = androidx.compose.ui.geometry.Offset(w * 0.5f, h * 0.32f)
                    listOf(0.12f, 0.24f, 0.36f).forEach { drawCircle(line, radius = w * it, center = c, style = stroke) }
                }
                PickMood.WindDown, PickMood.LateNight -> {
                    val c = androidx.compose.ui.geometry.Offset(w * 0.46f, h * 0.32f)
                    val r = w * 0.30f
                    val moon = androidx.compose.ui.graphics.Path().apply {
                        addOval(androidx.compose.ui.geometry.Rect(c, r))
                    }
                    val bite = androidx.compose.ui.graphics.Path().apply {
                        addOval(androidx.compose.ui.geometry.Rect(androidx.compose.ui.geometry.Offset(c.x + r * 0.5f, c.y - r * 0.2f), r * 0.95f))
                    }
                    val crescent = androidx.compose.ui.graphics.Path.combine(androidx.compose.ui.graphics.PathOperation.Difference, moon, bite)
                    drawPath(crescent, line, style = stroke)
                }
            }
        }
    )
    WaveMark(Modifier.align(Alignment.TopEnd).padding(16.dp))
    CardText(label, card.title, card.subtitle, Modifier.align(Alignment.BottomStart), titleSize = 32)
}

/** Your most played song: sunburst in the cover's colours, the cover in the middle. */
@Composable
private fun BoxScope.RotationFace(card: PickCard, label: String) {
    val colors = rememberArtColors(card.images.firstOrNull())
    Box(
        Modifier.matchParentSize().drawBehind {
            val a = colors.accent.value
            val b = colors.mid.value
            val c = colors.top.value
            val center = androidx.compose.ui.geometry.Offset(size.width * 0.5f, size.height * 0.38f)
            val n = 16
            val reach = size.height * 1.4f
            repeat(n) { i ->
                val a0 = Math.toRadians((i * 360.0 / n)).toFloat()
                val a1 = Math.toRadians(((i + 1) * 360.0 / n)).toFloat()
                val p = androidx.compose.ui.graphics.Path().apply {
                    moveTo(center.x, center.y)
                    lineTo(center.x + reach * kotlin.math.cos(a0), center.y + reach * kotlin.math.sin(a0))
                    lineTo(center.x + reach * kotlin.math.cos(a1), center.y + reach * kotlin.math.sin(a1))
                    close()
                }
                drawPath(p, if (i % 2 == 0) lerp(b, a, 0.55f) else lerp(c, a, 0.25f))
            }
        }
    )
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.matchParentSize()) {
        val d = maxWidth * 0.52f
        Box(
            Modifier.align(Alignment.TopCenter).padding(top = maxHeight * 0.38f - d / 2).size(d)
                .clip(RoundedCornerShape(18.dp)).background(Color(0xFF1E1E22)),
        ) { CoverArt(card.images.firstOrNull(), 500, Modifier.fillMaxSize()) }
    }
    Box(
        Modifier.matchParentSize().drawBehind {
            drawRect(Brush.verticalGradient(0.55f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.75f)))
        }
    )
    WaveMark(Modifier.align(Alignment.TopEnd).padding(16.dp))
    CardText(label, card.title, card.subtitle, Modifier.align(Alignment.BottomStart), subLines = 1)
}

/** Plain cover card: song, artist, album or playlist. */
@Composable
private fun BoxScope.ItemFace(card: PickCard, label: String) {
    val item = card.open
    CoverArt(card.images.firstOrNull(), 640, Modifier.fillMaxSize())
    Box(
        Modifier.matchParentSize().drawBehind {
            drawRect(Brush.verticalGradient(0.45f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.82f)))
        }
    )
    Column(Modifier.align(Alignment.BottomStart).padding(horizontal = 18.dp, vertical = 16.dp)) {
        Text(label, style = ChordType.CardSub, color = Color.White.copy(alpha = 0.72f), maxLines = 1)
        Text(
            card.title, style = ChordType.CardTitle.copy(fontSize = 20.sp, lineHeight = 24.sp),
            color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp),
        )
        if (card.subtitle.isNotBlank()) Row(verticalAlignment = Alignment.CenterVertically) {
            com.example.chord.ui.components.ExplicitMark(com.example.chord.data.explicit.ExplicitStore.has(item?.videoId))
            Text(
                card.subtitle, style = ChordType.CardSub.copy(fontSize = 13.sp), color = Color.White.copy(alpha = 0.72f),
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// ---- Speed dial ---------------------------------------------------------------

/** YouTube Music-style Quick Picks: four songs per swipe page. */
@Composable
private fun SpeedDialHeader(title: String) {
    Text(
        title,
        style = ChordType.Section,
        color = Color.White,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .padding(horizontal = PageGutter)
            .padding(bottom = 14.dp),
    )
}

@Composable
private fun SpeedDial(
    items: List<HomeItem>,
    nowPlayingId: String?,
    isPlaying: Boolean,
    onClick: (HomeItem) -> Unit,
) {
    // Extra slot at the end for the random-play tile.
    val pages = remember(items) {
        List(items.size + 1) { it }.chunked(9)
    }

    val state = rememberLazyListState()

    LazyRow(
        state = state,
        flingBehavior = rememberSnapFlingBehavior(state),
        contentPadding = PaddingValues(horizontal = PageGutter),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        itemsIndexed(
            items = pages,
            key = { index, page -> "speed-dial-page-$index-${page.firstOrNull()}" },
        ) { _, page ->
            Column(
                Modifier.fillParentMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                page.chunked(3).forEach { rowItems ->
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        repeat(3) { columnIndex ->
                            val itemIndex = rowItems.getOrNull(columnIndex)

                            if (itemIndex == null) {
                                Spacer(Modifier.weight(1f))
                            } else if (itemIndex == items.size) {
                                DiceTile(
                                    modifier = Modifier.weight(1f),
                                    onClick = {
                                        items.randomOrNull()?.let(onClick)
                                    },
                                )
                            } else {
                                val item = items[itemIndex]

                                DialTile(
                                    item = item,
                                    active = item.videoId != null &&
                                            item.videoId == nowPlayingId,
                                    playing = isPlaying,
                                    onClick = onClick,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (pages.size > 1) {
        val currentPage by remember {
            derivedStateOf {
                state.firstVisibleItemIndex.coerceIn(0, pages.lastIndex)
            }
        }

        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            pages.indices.forEach { index ->
                val selected = index == currentPage

                Box(
                    Modifier
                        .padding(horizontal = 3.dp)
                        .size(
                            width = if (selected) 17.dp else 5.dp,
                            height = 5.dp,
                        )
                        .clip(CircleShape)
                        .background(
                            Color.White.copy(
                                alpha = if (selected) 0.9f else 0.28f
                            )
                        )
                )
            }
        }
    }
}


@Composable
private fun DialTile(
    item: HomeItem,
    active: Boolean,
    playing: Boolean,
    onClick: (HomeItem) -> Unit,
    modifier: Modifier,
) {
    val shape = RoundedCornerShape(14.dp)

    Column(
        modifier = modifier
            .padding(horizontal = 2.dp)
            .pressScale(
                pressedScale = 0.97f,
                onClick = { onClick(item) }
            )
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(shape)
                .background(Color(0xFF1A1A1A))
        ) {
            CoverImage(
                item.thumbnailUrl,
                500.dp,
                shape
            )

            if (active) {
                NowPlayingOverlay(
                    playing,
                    Modifier.fillMaxSize().clip(shape)
                )
            }
        }

        Text(
            text = listOf(item.title, item.subtitle)
                .filter { it.isNotBlank() }
                .joinToString(" · "),
            style = ChordType.CardTitle.copy(fontSize = 13.sp),
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 7.dp)
        )
    }
}

@Composable
private fun DiceTile(
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(14.dp)

    Column(
        modifier = modifier
            .padding(horizontal = 2.dp)
            .pressScale(
                pressedScale = 0.96f,
                onClick = onClick,
            ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(shape)
                .background(
                    Brush.linearGradient(
                        colors = listOf(
                            Color(0xFF343434),
                            Color(0xFF171717),
                        )
                    )
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Rounded.Casino,
                contentDescription = "Play random song",
                tint = Color.White.copy(alpha = 0.9f),
                modifier = Modifier.size(44.dp),
            )
        }

        Text(
            text = "Surprise me",
            style = ChordType.CardTitle.copy(fontSize = 13.sp),
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 7.dp),
        )
    }
}

// ---- Replay artwork -----------------------------------------------------------

/** Distinct artwork for Chord-generated replay mixes only. */
@Composable
private fun ReplayArtwork(item: HomeItem, coverSize: Dp, shape: androidx.compose.ui.graphics.Shape) {
    val palettes = listOf(
        Color(0xFF183D50) to Color(0xFFDB8A61),
        Color(0xFF46365F) to Color(0xFFE0A078),
        Color(0xFF0F514B) to Color(0xFFC4B66E),
        Color(0xFF633B35) to Color(0xFFE1A45F),
        Color(0xFF293E68) to Color(0xFFC78DAE),
        Color(0xFF4A5031) to Color(0xFFD68A69),
        Color(0xFF3B4650) to Color(0xFFB6C8C5),
        Color(0xFF5B344B) to Color(0xFFD7A4B6),
    )
    val seed = item.browseId?.hashCode() ?: item.title.hashCode()
    val pair = palettes[Math.floorMod(seed, palettes.size)]
    val motif = Math.floorMod(seed ushr 3, 4)
    Box(
        Modifier.size(coverSize).clip(shape).background(
            Brush.linearGradient(listOf(pair.first, pair.second, pair.first.copy(red = (pair.first.red * .72f + pair.second.red * .28f))))
        ).drawBehind {
            val w = size.width
            val h = size.height
            val ink = Color.White.copy(alpha = .24f)
            when (motif) {
                0 -> repeat(5) { i ->
                    drawCircle(ink.copy(alpha = .12f + i * .035f), w * (.18f + i * .105f),
                        androidx.compose.ui.geometry.Offset(w * .76f, h * .30f),
                        style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx()))
                }
                1 -> {
                    repeat(4) { i ->
                        val path = androidx.compose.ui.graphics.Path().apply {
                            moveTo(-w * .1f, h * (.34f + i * .13f))
                            cubicTo(w * .24f, h * (.05f + i * .13f), w * .62f, h * (.68f + i * .06f), w * 1.1f, h * (.25f + i * .13f))
                        }
                        drawPath(path, ink.copy(alpha = .16f + i * .025f), style = androidx.compose.ui.graphics.drawscope.Stroke(width = (3 + i).dp.toPx()))
                    }
                }
                2 -> repeat(8) { i ->
                    drawLine(ink.copy(alpha = .16f), androidx.compose.ui.geometry.Offset(w * (i * .18f - .2f), 0f),
                        androidx.compose.ui.geometry.Offset(w * (i * .18f + .45f), h), strokeWidth = (1.5f + (i % 3)).dp.toPx())
                }
                else -> {
                    val center = androidx.compose.ui.geometry.Offset(w * .72f, h * .28f)
                    repeat(18) { i ->
                        val angle = Math.toRadians(i * 20.0).toFloat()
                        val inner = w * .08f
                        val outer = w * (.24f + (i % 3) * .06f)
                        drawLine(ink, androidx.compose.ui.geometry.Offset(center.x + kotlin.math.cos(angle) * inner, center.y + kotlin.math.sin(angle) * inner),
                            androidx.compose.ui.geometry.Offset(center.x + kotlin.math.cos(angle) * outer, center.y + kotlin.math.sin(angle) * outer), strokeWidth = 2.dp.toPx())
                    }
                }
            }
            drawRect(Brush.verticalGradient(listOf(Color.Black.copy(alpha = .02f), Color.Black.copy(alpha = .12f), Color.Black.copy(alpha = .78f))))
        },
    ) {
        Column(Modifier.align(Alignment.BottomStart).padding(13.dp)) {
            Text("CHORD / REPLAY", style = ChordType.Eyebrow, color = Color.White.copy(alpha = .82f), maxLines = 1)
            Text(item.title, style = ChordType.CardTitle.copy(fontSize = 19.sp, lineHeight = 22.sp),
                color = Color.White, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
    }
}

// ---- Cards row ----------------------------------------------------------------

private fun HomeItem.listKey(index: Int) = (videoId ?: browseId ?: title) + "#" + index

@Composable
private fun CardRow(
    shelf: HomeShelf,
    size: Dp,
    nowPlayingId: String?,
    isPlaying: Boolean,
    onClick: (HomeItem) -> Unit,
) {
    val rowState = rememberLazyListState()
    LazyRow(
        state = rowState,
        flingBehavior = rememberSnapFlingBehavior(rowState),
        contentPadding = PaddingValues(horizontal = PageGutter),
        horizontalArrangement = Arrangement.spacedBy(CardGap),
    ) {
        itemsIndexed(shelf.items, key = { i, it -> it.listKey(i) }, contentType = { _, it -> it.type }) { _, item ->
            val round = item.type == HomeItemType.Artist
            val shape = if (round) CircleShape else RoundedCornerShape(14.dp)
            val playing = item.videoId != null && item.videoId == nowPlayingId
            Column(
                Modifier.width(size).pressScale(pressedScale = 0.96f, onClick = { onClick(item) }),
                horizontalAlignment = if (round) Alignment.CenterHorizontally else Alignment.Start,
            ) {
                Box {
                    val localReplayId = item.browseId?.removePrefix("mix:")
                    if (item.type == HomeItemType.Playlist && item.browseId?.startsWith("mix:") == true &&
                        (localReplayId == "onrepeat" || localReplayId == "alltime" || localReplayId?.startsWith("replay_") == true)) {
                        ReplayArtwork(item, size, shape)
                    } else {
                        CoverImage(item.thumbnailUrl, size, shape)
                    }
                    if (playing) NowPlayingOverlay(isPlaying, Modifier.size(size).clip(shape))
                }
                Text(
                    item.title, style = ChordType.CardTitle, color = Color.White,
                    textAlign = if (round) TextAlign.Center else TextAlign.Start,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 9.dp),
                )
                if (item.subtitle.isNotBlank()) {
                    Text(
                        item.subtitle, style = ChordType.CardSub, color = Dim,
                        textAlign = if (round) TextAlign.Center else TextAlign.Start,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** Pages of four stacked rows that snap sideways, like Quick picks. */
@Composable
private fun ListShelf(
    shelf: HomeShelf,
    nowPlayingId: String?,
    isPlaying: Boolean,
    onClick: (HomeItem) -> Unit,
) {
    val state = rememberLazyListState()
    val pages = remember(shelf) { shelf.items.chunked(3) }
    LazyRow(
        state = state,
        flingBehavior = rememberSnapFlingBehavior(state),
        contentPadding = PaddingValues(horizontal = PageGutter),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        itemsIndexed(pages, key = { i, _ -> "page$i" }, contentType = { _, _ -> "page" }) { _, page ->
            Column(Modifier.fillParentMaxWidth(0.88f)) {
                page.forEach { item ->
                    MediaRow(
                        item = item,
                        active = item.videoId != null && item.videoId == nowPlayingId,
                        playing = isPlaying,
                        onClick = { onClick(item) },
                    )
                }
            }
        }
    }
}