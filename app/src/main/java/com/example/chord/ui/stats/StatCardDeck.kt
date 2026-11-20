package com.example.chord.ui.stats

import android.content.Context
import android.content.Intent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.core.content.FileProvider
import com.example.chord.data.home.HomeItem
import com.example.chord.data.home.HomeItemType
import com.example.chord.data.stats.ListenStats
import com.example.chord.data.stats.formatListenTime
import com.example.chord.ui.components.CoverImage
import com.example.chord.ui.haptics.Haptic
import com.example.chord.ui.haptics.hapticClickable
import com.example.chord.ui.theme.ChordType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs
import kotlin.math.min
import androidx.compose.animation.core.animateFloat
/* ---------- data ---------- */

private val StyleNames = listOf("Aurora" to "soft glass", "Poster" to "bold type", "Vinyl" to "warm record", "Ticket" to "paper stub")
private val Radius = listOf(28.dp, 24.dp, 28.dp, 16.dp)
private val Dark = Color(0xFF0A0A0D)
private val Paper = Color(0xFFF3F3F6)
private val Ink = Color(0xFF101216)
private val Red = Color(0xFFE5381F)

/** kind: 0 time, 1 song, 2 artist, 3 week. */
private class Face(
    val kind: Int, val label: String, val head: String, val sub: String, val thumb: String?,
    val foot: List<Pair<String, String>>, val streak: Int = 0, val click: (() -> Unit)? = null,
)

private fun faces(s: ListenStats, onItem: (HomeItem) -> Unit): List<Face> {
    val song = s.topSong
    val artist = s.topArtist
    val total = s.totalMs.toLong().coerceAtLeast(1L)
    fun pct(ms: Long) = "${ms * 100 / total}%"
    return listOf(
        Face(0, "Listening time", formatListenTime(s.totalMs), "listened in total", null,
            listOf(formatListenTime(s.todayMs) to "Today", formatListenTime(s.weekMs) to "7 days", "${s.streakDays} d" to "Streak")),
        Face(1, "Top song", song?.title ?: "Nothing yet", song?.artist?.ifBlank { "Unknown" } ?: "Play something", song?.thumb,
            if (song != null) listOf(formatListenTime(song.ms) to "Listened", pct(song.ms.toLong()) to "Of total") else listOf("-" to "Listened"),
            click = song?.let { x -> { onItem(HomeItem(x.title, x.artist, x.thumb, x.videoId, null, HomeItemType.Song)) } }),
        Face(2, "Top artist", artist?.name ?: "Nothing yet", artist?.let { formatListenTime(it.ms) } ?: "Play something", artist?.thumb,
            if (artist != null) listOf(formatListenTime(artist.ms) to "Listened", pct(artist.ms.toLong()) to "Of total") else listOf("-" to "Listened"),
            click = artist?.let { x -> { onItem(HomeItem(x.name, "", x.thumb, null, null, HomeItemType.Artist)) } }),
        Face(3, "This week", "${s.streakDays}", "day streak", null,
            listOf(formatListenTime(s.todayMs) to "Today", formatListenTime(s.weekMs) to "7 days", formatListenTime(s.totalMs) to "All time"),
            streak = s.streakDays),
    )
}

/* ---------- deck ---------- */

/** Swipe deck of 4 stat cards, 4 looks, saved choice, share = image of the top card. */
@Composable
fun StatCardDeck(stats: ListenStats, onItem: (HomeItem) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences("chord_ui", Context.MODE_PRIVATE) }
    var style by remember { mutableIntStateOf(prefs.getInt("stat_style", 0).coerceIn(0, 3)) }
    var top by remember { mutableIntStateOf(0) }
    val offX = remember { Animatable(0f) }
    val data = remember(stats) { faces(stats, onItem) }
    val layers = List(4) { rememberGraphicsLayer() }
    val w = min(LocalConfiguration.current.screenWidthDp - 32, 340).dp
    val wPx = with(LocalDensity.current) { w.toPx() }

    Column(modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            StyleNames.forEachIndexed { i, (n, sub) ->
                StyleChip(n, sub, i == style, Modifier.weight(1f)) {
                    style = i; prefs.edit().putInt("stat_style", i).apply()
                }
            }
        }
        Box(Modifier.padding(top = 20.dp, bottom = 34.dp).width(w).aspectRatio(0.8f)) {
            for (i in 0..3) key(i) {
                val pos = (i - top + 4) % 4
                val p by animateFloatAsState(pos.toFloat(), if (pos == 3) snap() else tween(380), label = "pos")
                val isTop = pos == 0
                Box(
                    Modifier.fillMaxSize().zIndex(4f - pos)
                        .graphicsLayer {
                            val dx = if (isTop) offX.value else 0f
                            translationX = dx
                            translationY = p.coerceAtMost(2f) * 16.dp.toPx()
                            scaleX = 1f - p.coerceAtMost(2f) * 0.055f; scaleY = scaleX
                            rotationZ = dx / wPx * 8f
                            alpha = if (p > 2f) (3f - p).coerceIn(0f, 1f) else 1f
                        }
                        .then(
                            if (!isTop) Modifier else Modifier
                                .pointerInput(top) { detectTapGestures(onTap = { data[i].click?.invoke() }) }
                                .pointerInput(top) {
                                    detectHorizontalDragGestures(
                                        onDragEnd = {
                                            scope.launch {
                                                if (abs(offX.value) > wPx * 0.28f) {
                                                    val dir = if (offX.value > 0) 1f else -1f
                                                    offX.animateTo(dir * wPx * 1.3f, tween(180))
                                                    top = (top + 1) % 4
                                                    offX.snapTo(0f)
                                                } else offX.animateTo(0f)
                                            }
                                        },
                                        onHorizontalDrag = { c, d -> c.consume(); scope.launch { offX.snapTo(offX.value + d) } },
                                    )
                                }
                        ),
                ) { StatCard(style, data[i], (p * 0.2f).coerceIn(0f, 0.5f), layers[i], Modifier.fillMaxSize()) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            repeat(4) { i ->
                val dw by animateDpAsState(if (i == top) 18.dp else 6.dp, label = "dot")
                Box(Modifier.size(dw, 6.dp).clip(CircleShape).background(Color.White.copy(alpha = if (i == top) 0.9f else 0.2f)))
            }
        }
        Row(
            Modifier.padding(top = 16.dp).clip(CircleShape).background(Paper)
                .hapticClickable(Haptic.Confirm, onClick = {
                    scope.launch {
                        runCatching { shareLayer(context, layers[top]) }.onFailure {
                            android.util.Log.e("StatShare", "share failed", it)
                            android.widget.Toast.makeText(context, it.message ?: "Share failed", android.widget.Toast.LENGTH_LONG).show()
                        }
                    }
                })
                .padding(horizontal = 20.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.Share, "Share", tint = Dark, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(7.dp))
            Text("Share this card", style = ChordType.CardTitle.copy(fontSize = 13.sp), color = Dark)
        }
    }
}

@Composable
private fun StyleChip(name: String, sub: String, on: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val sh = RoundedCornerShape(12.dp)
    Column(
        modifier.clip(sh).background(if (on) Paper else Color.White.copy(alpha = 0.07f))
            .border(0.5.dp, Color.White.copy(alpha = 0.14f), sh)
            .hapticClickable(Haptic.Confirm, onClick = onClick).padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(name, style = ChordType.CardTitle.copy(fontSize = 12.sp), color = if (on) Dark else Color.White.copy(alpha = 0.85f), maxLines = 1)
        Text(sub, style = ChordType.CardSub.copy(fontSize = 10.sp), color = if (on) Dark.copy(alpha = 0.6f) else Color.White.copy(alpha = 0.45f), maxLines = 1)
    }
}

private suspend fun shareLayer(context: Context, layer: GraphicsLayer) {
    val bmp = layer.toImageBitmap().asAndroidBitmap()
    val file = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "share").apply { mkdirs() }
        File(dir, "stat_card.png").also { f ->
            f.outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "image/png"; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

/* ---------- palettes ---------- */

private class Aur(val base: Long, val g: List<Triple<Long, Float, Float>>)

private val AUR = listOf(
    Aur(0xFF0C0A1F, listOf(Triple(0xFF7C5CFF, 0f, 0f), Triple(0xFFFF5C9D, 1f, .28f), Triple(0xFF27C4F5, .3f, 1f))),
    Aur(0xFF140A1C, listOf(Triple(0xFFFFB454, 1f, 0f), Triple(0xFFFF5C7A, 0f, .6f), Triple(0xFF6A4CFF, .6f, 1f))),
    Aur(0xFF07121C, listOf(Triple(0xFF27E0B0, 0f, 0f), Triple(0xFF3D7BFF, 1f, .5f), Triple(0xFFB05CFF, .2f, 1f))),
    Aur(0xFF180A14, listOf(Triple(0xFFFF7A5C, 1f, 0f), Triple(0xFFFF5CC8, 0f, .5f), Triple(0xFF5C7CFF, .7f, 1f))),
)
private val POSTER = listOf(0xFF2338FF, 0xFF0F0F12, 0xFFFF4B2B, 0xFFE8E6E1)
private val VINYL = listOf(
    listOf(0xFFFF8F4F, 0xFFC8324F, 0xFF40134F, 0xFF160A24),
    listOf(0xFFFF8F4F, 0xFFC8324F, 0xFF40134F, 0xFF160A24),
    listOf(0xFFFFD166, 0xFFEF5D5D, 0xFF522060, 0xFF160A24),
    listOf(0xFFFF6F91, 0xFFB0306C, 0xFF2D1457, 0xFF120A24),
)

private fun DrawScope.aurora(a: Aur) {
    drawRect(Color(a.base))
    a.g.forEach { (c, x, y) ->
        drawRect(Brush.radialGradient(listOf(Color(c), Color.Transparent), Offset(x * size.width, y * size.height), size.width * 0.85f))
    }
}

private fun TextStyle.mono(on: Boolean) = if (on) copy(fontFamily = FontFamily.Monospace) else this

/* ---------- one card ---------- */

@Composable
private fun StatCard(style: Int, f: Face, dim: Float, capture: GraphicsLayer, modifier: Modifier) {
    val k = f.kind
    val sh = RoundedCornerShape(Radius[style])
    val mono = style == 3
    val ink = when { style == 3 -> Ink; style == 1 && k == 3 -> Color(0xFF0F0F12); else -> Color.White }
    val line = Color.White.copy(alpha = 0.16f)
    val bg = when (style) {
        0 -> Modifier.drawBehind { aurora(AUR[k]) }.border(0.5.dp, line, sh)
        1 -> Modifier.background(Color(POSTER[k]))
        2 -> Modifier.background(Brush.verticalGradient(VINYL[k].map { Color(it) })).border(0.5.dp, line, sh)
        else -> Modifier.background(Color(0xFFE7EAEF))
    }
    val bigSize = listOf(52, 64, 52, 40)[style].sp
    val cover = listOf(150, 140, 0, 112)[style].dp
    val coverShape = when {
        k == 2 -> CircleShape
        style == 0 -> RoundedCornerShape(34.dp)
        style == 1 -> RoundedCornerShape(0.dp)
        else -> RoundedCornerShape(6.dp)
    }
    val vinylDisc = style == 2 && (k == 1 || k == 2)

    Box(
        modifier
            .drawWithContent { capture.record { this@drawWithContent.drawContent() }; drawLayer(capture) }
            .graphicsLayer { shape = sh; clip = true; compositingStrategy = CompositingStrategy.Offscreen }
            .then(bg)
            .drawWithContent { drawContent(); if (dim > 0f) drawRect(Color.Black.copy(alpha = dim)) },
    ) {
        if (style == 2) {
            if (vinylDisc) Disc(236.dp, f.thumb, Modifier.align(Alignment.TopEnd).offset(84.dp, 78.dp))
            else Disc(270.dp, null, Modifier.align(Alignment.BottomEnd).offset(96.dp, 96.dp).graphicsLayer { alpha = 0.5f })
        }
        if (style == 3) Canvas(Modifier.fillMaxSize()) {
            val y = size.height * 0.62f
            val r = 11.dp.toPx()
            drawCircle(Color.Black, r, Offset(0f, y), blendMode = BlendMode.Clear)
            drawCircle(Color.Black, r, Offset(size.width, y), blendMode = BlendMode.Clear)
            drawLine(
                Color(0xFF98A0AD), Offset(18.dp.toPx(), y), Offset(size.width - 18.dp.toPx(), y), 2.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(10.dp.toPx(), 6.dp.toPx())),
            )
            var x = 22.dp.toPx(); var i = 0
            val end = size.width - 22.dp.toPx(); val t = size.height - 42.dp.toPx()
            while (x < end) {
                val bw = if (i % 3 == 0) 2.dp.toPx() else 1.dp.toPx()
                drawRect(Ink, Offset(x, t), Size(bw, 22.dp.toPx()))
                x += bw + if (i % 2 == 0) 2.dp.toPx() else 3.dp.toPx(); i++
            }
        }
        Column(Modifier.fillMaxSize().padding(start = 22.dp, end = 22.dp, top = 22.dp, bottom = if (style == 3) 54.dp else 22.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("chord", style = ChordType.CardTitle.copy(fontSize = 12.sp).mono(mono), color = if (mono) Red else ink, modifier = Modifier.weight(1f))
                Text(f.label.uppercase(), style = ChordType.Eyebrow.mono(mono), color = if (mono) Red else ink.copy(alpha = 0.7f))
            }
            Spacer(Modifier.weight(1f))
            if (k == 0 || k == 3) {
                val size = if (k == 3) bigSize * 1.5f else bigSize
                var st = ChordType.CardTitle.copy(fontSize = size, letterSpacing = (-size.value * 0.045f).sp, lineHeight = size).mono(mono)
                if (style == 0) st = st.copy(brush = Brush.verticalGradient(listOf(Color.White, Color.White.copy(alpha = 0.65f))))
                Text(f.head, style = st, color = ink, maxLines = 1, softWrap = false)
                Text(f.sub, style = ChordType.CardSub.mono(mono), color = ink.copy(alpha = 0.65f), modifier = Modifier.padding(top = 6.dp))
                if (k == 3) Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    repeat(7) { i ->
                        Box(
                            Modifier.weight(1f).height(6.dp).clip(RoundedCornerShape(if (style == 1 || style == 3) 0.dp else 3.dp))
                                .background(ink.copy(alpha = if (i < min(f.streak, 7)) 1f else 0.2f)),
                        )
                    }
                }
            } else {
                if (!vinylDisc) Box(
                    Modifier.size(cover).clip(coverShape)
                        .background(Brush.linearGradient(listOf(Color.White.copy(alpha = 0.5f), Color.White.copy(alpha = 0.08f))))
                        .border(0.5.dp, line, coverShape),
                    contentAlignment = Alignment.Center,
                ) {
                    if (f.thumb != null) CoverImage(f.thumb, cover, coverShape)
                    else Text(f.head.take(1).uppercase(), style = ChordType.CardTitle.copy(fontSize = 48.sp).mono(mono), color = ink.copy(alpha = 0.9f))
                }
                val ts = listOf(26, 34, 26, 18)[style].sp
                Column(Modifier.then(if (vinylDisc) Modifier.fillMaxWidth(0.6f) else Modifier.padding(top = 14.dp))) {
                    Text(f.head, style = ChordType.CardTitle.copy(fontSize = ts, letterSpacing = (-ts.value * 0.035f).sp, lineHeight = ts * 1.1f).mono(mono),
                        color = ink, maxLines = if (style == 1) 2 else 1, overflow = TextOverflow.Ellipsis)
                    Text(f.sub, style = ChordType.CardSub.mono(mono), color = ink.copy(alpha = 0.65f), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
                }
            }
            Spacer(Modifier.weight(1f))
            val footMod = when (style) {
                0 -> Modifier.clip(RoundedCornerShape(18.dp)).background(Color.White.copy(alpha = 0.10f)).border(0.5.dp, line, RoundedCornerShape(18.dp)).padding(horizontal = 14.dp, vertical = 12.dp)
                1 -> Modifier.drawBehind { drawLine(ink, Offset(0f, 0f), Offset(size.width, 0f), 2.dp.toPx()) }.padding(top = 12.dp)
                2 -> Modifier.clip(RoundedCornerShape(16.dp)).background(Color(0x59140820)).padding(horizontal = 14.dp, vertical = 12.dp)
                else -> Modifier
            }
            Row(footMod.fillMaxWidth()) {
                f.foot.forEach { (v, l) ->
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(v, style = ChordType.CardTitle.copy(fontSize = if (mono) 13.sp else 15.sp).mono(mono), color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(l, style = ChordType.CardSub.copy(fontSize = 11.sp).mono(mono), color = ink.copy(alpha = 0.6f))
                    }
                }
            }
        }
    }
}

/** Spinning record. Cover sits in the middle as the label. */
@Composable
private fun Disc(d: Dp, thumb: String?, modifier: Modifier) {
    val rot by rememberInfiniteTransition(label = "spin").animateFloat(
        0f, 360f, infiniteRepeatable(tween(16000, easing = LinearEasing)), label = "rot",
    )
    Box(
        modifier.size(d).graphicsLayer { rotationZ = rot }.drawBehind {
            drawCircle(Color(0xFF140E18))
            var r = size.minDimension / 2
            while (r > size.minDimension * 0.2f) { drawCircle(Color(0xFF241A2A), r, style = Stroke(1.dp.toPx())); r -= 4.dp.toPx() }
            drawCircle(Brush.sweepGradient(listOf(Color.Transparent, Color.White.copy(alpha = 0.09f), Color.Transparent, Color.White.copy(alpha = 0.09f), Color.Transparent)))
            drawCircle(Color(0xFFFFD37A), size.minDimension * 0.19f)
        },
        contentAlignment = Alignment.Center,
    ) { if (thumb != null) CoverImage(thumb, d * 0.38f, CircleShape) }
}