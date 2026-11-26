package com.example.chord.ui.stats.story

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.chord.data.stats.Recap
import com.example.chord.data.stats.RecapArtist
import com.example.chord.data.stats.RecapPeriod
import com.example.chord.data.stats.TopSong
import com.example.chord.ui.components.CoverImage
import com.example.chord.ui.components.pressScale
import com.example.chord.ui.theme.ChordFont
import kotlinx.coroutines.delay
import java.util.Locale

/** How long the "wait for it" drumroll lasts before the #1 artist shows. */
internal const val REVEAL_AT_MS = 2600L

private val Cream = StoryColors.Cream
private val Ink = StoryColors.Ink
private val Coral = StoryColors.Coral
private val Hot = StoryColors.Hot

private fun n(v: Long) = String.format(Locale.US, "%,d", v)
private fun mins(ms: Long) = ms / 60_000L

/** Space kept free at the bottom for the "now playing" chip. */
private val ChipRoom = 96.dp

private fun Modifier.slidePad() = this.fillMaxSize().statusBarsPadding().navigationBarsPadding()
    .padding(horizontal = 24.dp).padding(top = StoryTop, bottom = ChipRoom)

/* ---------- 1. intro ---------- */

@Composable
internal fun IntroSlide(r: Recap, p: RecapPeriod, register: (GraphicsLayer) -> Unit) {
    SlideFrame(false, listOf(Decor.Arcs, Decor.Scribble, Decor.Dots), 0, register) {
        Column(Modifier.slidePad()) {
            Box {
                PosterHeading(listOf("YOUR", "${p.word} IN", "SOUND"), 1, false, maxSp = 74f)
                val flag = rememberReveal(700)
                LogoDoodle(Coral, Modifier.align(Alignment.TopEnd).offset(x = (-30).dp, y = (-4).dp).size(46.dp).pop(flag, 20.dp, tilt = -8f))
            }
            Spacer(Modifier.weight(1f))
            val showUp = rememberReveal(450)
            Column(Modifier.pop(showUp, 30.dp)) {
                Text("You listened for", style = PosterStyle.copy(fontSize = 20.sp, letterSpacing = (-0.3).sp), color = Ink)
                val count = rememberCountUp(mins(r.totalMs), 500)
                Text(n(count), style = PosterStyle.copy(fontSize = 88.sp, letterSpacing = (-4).sp, fontStyle = androidx.compose.ui.text.font.FontStyle.Normal), color = Ink)
                Text("minutes", style = PosterStyle.copy(fontSize = 24.sp, letterSpacing = (-0.4).sp, fontStyle = androidx.compose.ui.text.font.FontStyle.Normal), color = Ink)
            }
            Spacer(Modifier.height(14.dp))
            val fact = rememberReveal(1600)
            Text(funFact(r.totalMs, p), style = PosterStyle.copy(fontSize = 17.sp, letterSpacing = 0.sp, fontWeight = FontWeight.Medium), color = Ink.copy(alpha = 0.85f), modifier = Modifier.pop(fact, 16.dp))
            Spacer(Modifier.height(70.dp))
        }
    }
}

private fun funFact(ms: Long, p: RecapPeriod): String {
    val h = ms / 3_600_000L
    val d = ms / 86_400_000L
    return when {
        d >= 2 -> "That's about $d full days of music!"
        h >= 3 -> "That's about $h hours. Around ${(ms / 2_700_000L).coerceAtLeast(1)} full albums."
        else -> "A warm-up. Keep playing this ${p.noun}."
    }
}

/* ---------- 2. suspense + #1 artist ---------- */

@Composable
internal fun ArtistRevealSlide(a: RecapArtist, totalMs: Long, p: RecapPeriod, register: (GraphicsLayer) -> Unit) {
    var phase by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { delay(REVEAL_AT_MS); phase = 1 }
    SlideFrame(true, listOf(Decor.Scribble, Decor.Dots), 1, register) {
        Crossfade(phase, animationSpec = tween(260), label = "reveal") { ph ->
            if (ph == 0) Suspense(p) else Reveal(a, totalMs, p)
        }
    }
}

@Composable
private fun Suspense(p: RecapPeriod) {
    Column(Modifier.slidePad(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Vinyl(Modifier.size(170.dp))
        Spacer(Modifier.height(28.dp))
        Text("WAIT FOR IT", style = PosterStyle.copy(fontSize = 46.sp), color = Cream)
        Spacer(Modifier.height(6.dp))
        Text("Your #1 artist this ${p.noun} is", style = PosterStyle.copy(fontSize = 16.sp, letterSpacing = 0.sp, fontWeight = FontWeight.Medium), color = Cream.copy(alpha = 0.75f))
        Spacer(Modifier.height(14.dp))
        Drumroll()
    }
}

@Composable
private fun Drumroll() {
    val t = rememberInfiniteTransition(label = "dots")
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        repeat(3) { i ->
            val s = t.animateFloat(0.4f, 1f, infiniteRepeatable(tween(420, delayMillis = i * 140, easing = LinearEasing), RepeatMode.Reverse), label = "d$i")
            Box(Modifier.size(12.dp).graphicsLayer { scaleX = s.value; scaleY = s.value }.background(Hot, CircleShape))
        }
    }
}

@Composable
private fun Vinyl(modifier: Modifier) {
    val spin = rememberInfiniteTransition(label = "spin")
        .animateFloat(0f, 360f, infiniteRepeatable(tween(2600, easing = LinearEasing)), label = "spin")
    Canvas(modifier.graphicsLayer { rotationZ = spin.value }) {
        val c = center
        val r = size.minDimension / 2f
        drawCircle(Cream, r)
        for (i in 1..6) drawCircle(Ink.copy(alpha = 0.35f), r * (0.35f + i * 0.1f), c, style = Stroke(1.5f.dp.toPx()))
        drawCircle(Coral, r * 0.3f, c)
        drawCircle(Ink, r * 0.05f, c)
        // shine arc, so you see it turn
        drawArc(Ink.copy(alpha = 0.4f), 200f, 40f, false, Offset(r * 0.1f, r * 0.1f), Size(size.width - r * 0.2f, size.height - r * 0.2f), style = Stroke(6.dp.toPx(), cap = StrokeCap.Round))
    }
}

@Composable
private fun Reveal(a: RecapArtist, totalMs: Long, p: RecapPeriod) {
    Column(Modifier.slidePad()) {
        PosterHeading(listOf("YOUR TOP", "ARTIST"), 1, true, maxSp = 60f, startDelay = 0)
        Spacer(Modifier.weight(1f))
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            val burst = remember { Animatable(0f) }
            val pop = rememberReveal(120)
            LaunchedEffect(Unit) { burst.animateTo(1f, tween(900)) }
            val ring = rememberInfiniteTransition(label = "ring")
                .animateFloat(0f, 360f, infiniteRepeatable(tween(9000, easing = LinearEasing)), label = "ring")
            Box(Modifier.size(250.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxSize()) {
                    val b = burst.value
                    drawCircle(Coral.copy(alpha = (1f - b) * 0.7f), size.minDimension * (0.3f + b * 1.1f))
                    val dash = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(14.dp.toPx(), 12.dp.toPx()), ring.value)
                    drawCircle(Hot, size.minDimension / 2f - 3.dp.toPx(), style = Stroke(3.dp.toPx(), cap = StrokeCap.Round, pathEffect = dash))
                }
                CoverImage(a.thumb, 206.dp, CircleShape, Modifier.graphicsLayer { val s = pop.value.coerceIn(0f, 1.1f); scaleX = s; scaleY = s })
            }
            Spacer(Modifier.height(22.dp))
            val nm = rememberReveal(500)
            Box(Modifier.fillMaxWidth().pop(nm, 30.dp), contentAlignment = Alignment.Center) {
                FitText(a.name.uppercase(), 56f, Cream)
            }
            Spacer(Modifier.height(8.dp))
            val sub = rememberReveal(900)
            val pct = if (totalMs > 0) (a.ms * 100 / totalMs).toInt() else 0
            Text(
                "${n(mins(a.ms))} minutes together · $pct% of your ${p.noun}",
                style = PosterStyle.copy(fontSize = 15.sp, letterSpacing = 0.sp, fontWeight = FontWeight.Medium, fontStyle = androidx.compose.ui.text.font.FontStyle.Normal),
                color = Cream.copy(alpha = 0.8f), textAlign = TextAlign.Center, modifier = Modifier.pop(sub, 14.dp),
            )
        }
        Spacer(Modifier.weight(1.2f))
    }
}

/* ---------- 3. top artists ---------- */

@Composable
internal fun ArtistsSlide(artists: List<RecapArtist>, playingId: String?, onPlay: (TopSong) -> Unit, register: (GraphicsLayer) -> Unit) {
    SlideFrame(false, listOf(Decor.Scribble, Decor.Dots, Decor.Arcs), 2, register) {
        Column(Modifier.slidePad()) {
            PosterHeading(listOf("YOUR TOP", "ARTISTS"), 1, false, maxSp = 56f)
            Spacer(Modifier.height(22.dp))
            artists.take(5).forEachIndexed { i, a ->
                val rv = rememberReveal(500 + i * 130)
                val playing = a.topSong != null && a.topSong.videoId == playingId
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 7.dp).pop(rv, 0.dp, slide = 60.dp)
                        .pressScale(0.97f) { a.topSong?.let(onPlay) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("${i + 1}.", style = PosterStyle.copy(fontSize = 28.sp, letterSpacing = (-1).sp), color = Ink, modifier = Modifier.width(42.dp))
                    CoverImage(a.thumb, 62.dp - (i * 3).dp, CircleShape, Modifier.border(if (playing) 3.dp else 1.5.dp, if (playing) Coral else Ink, CircleShape))
                    Column(Modifier.padding(start = 16.dp)) {
                        Text(a.name, style = PosterStyle.copy(fontSize = 19.sp, letterSpacing = (-0.3).sp, fontStyle = androidx.compose.ui.text.font.FontStyle.Normal), color = Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${n(mins(a.ms))} min", style = PosterStyle.copy(fontSize = 13.sp, letterSpacing = 0.sp, fontWeight = FontWeight.Medium, fontStyle = androidx.compose.ui.text.font.FontStyle.Normal), color = Ink.copy(alpha = 0.7f))
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            val hint = rememberReveal(1500)
            Text("Tap an artist to hear the song you played most.", style = PosterStyle.copy(fontSize = 13.sp, letterSpacing = 0.sp, fontWeight = FontWeight.Medium, fontStyle = androidx.compose.ui.text.font.FontStyle.Normal), color = Ink.copy(alpha = 0.65f), modifier = Modifier.pop(hint, 10.dp))
        }
    }
}

/* ---------- 4. top tracks ---------- */

@Composable
internal fun TracksSlide(songs: List<TopSong>, playingId: String?, onPlay: (TopSong) -> Unit, register: (GraphicsLayer) -> Unit) {
    SlideFrame(true, listOf(Decor.Checker, Decor.Dots, Decor.Scribble), 3, register) {
        Column(Modifier.slidePad()) {
            PosterHeading(listOf("YOUR TOP", "TRACKS"), 1, true, maxSp = 56f)
            Spacer(Modifier.height(22.dp))
            songs.take(5).forEachIndexed { i, s ->
                val rv = rememberReveal(500 + i * 130)
                val playing = s.videoId == playingId
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 7.dp).pop(rv, 0.dp, slide = -60.dp)
                        .pressScale(0.97f) { onPlay(s) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("${i + 1}.", style = PosterStyle.copy(fontSize = 26.sp, letterSpacing = (-1).sp), color = if (playing) Hot else Cream, modifier = Modifier.width(40.dp))
                    CoverImage(s.thumb, 60.dp, RoundedCornerShape(6.dp), Modifier.border(if (playing) 2.dp else 0.dp, Hot, RoundedCornerShape(6.dp)))
                    Column(Modifier.padding(start = 16.dp).weight(1f)) {
                        Text(s.title, style = PosterStyle.copy(fontSize = 17.sp, letterSpacing = (-0.2).sp, fontStyle = androidx.compose.ui.text.font.FontStyle.Normal), color = Cream, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(s.artist, style = PosterStyle.copy(fontSize = 13.sp, letterSpacing = 0.sp, fontWeight = FontWeight.Medium, fontStyle = androidx.compose.ui.text.font.FontStyle.Normal), color = Cream.copy(alpha = 0.7f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Text("${n(mins(s.ms).coerceAtLeast(1))}m", style = PosterStyle.copy(fontSize = 13.sp, letterSpacing = 0.sp, fontWeight = FontWeight.Medium, fontStyle = androidx.compose.ui.text.font.FontStyle.Normal), color = Cream.copy(alpha = 0.55f))
                }
            }
            Spacer(Modifier.weight(1f))
        }
    }
}

/* ---------- 5. listening DNA ---------- */

private val Days = listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")
private val DayInitials = listOf("M", "T", "W", "T", "F", "S", "S")

private class Persona(val type: String, val time: String)

private fun persona(r: Recap): Persona {
    if (r.hasHours) {
        val night = (22..23).sumOf { r.hourMs[it] } + (0..4).sumOf { r.hourMs[it] }
        val morn = (5..11).sumOf { r.hourMs[it] }
        val aft = (12..16).sumOf { r.hourMs[it] }
        val eve = (17..21).sumOf { r.hourMs[it] }
        return listOf(
            night to Persona("Night Owl", "Night"), morn to Persona("Early Bird", "Morning"),
            aft to Persona("Daydreamer", "Afternoon"), eve to Persona("Golden Hour", "Evening"),
        ).maxByOrNull { it.first }!!.second
    }
    val wk = r.weekdayMs[5] + r.weekdayMs[6]
    return if (wk * 100 >= r.totalMs * 45) Persona("Weekend Warrior", "Weekend") else Persona("Weekday Grinder", "Weekdays")
}

@Composable
internal fun DnaSlide(r: Recap, p: RecapPeriod, register: (GraphicsLayer) -> Unit) {
    val me = remember(r) { persona(r) }
    val topDay = remember(r) { r.weekdayMs.indices.maxByOrNull { r.weekdayMs[it] } ?: 0 }
    SlideFrame(false, listOf(Decor.Scribble, Decor.Dots), 4, register) {
        Column(Modifier.slidePad()) {
            PosterHeading(listOf("YOUR LISTENING", "DNA"), 1, false, maxSp = 54f)
            Spacer(Modifier.height(26.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                val a = rememberReveal(500)
                Column(Modifier.weight(1f).pop(a, 24.dp)) {
                    Label("Your type"); Spacer(Modifier.height(6.dp)); TapeLabel(me.type, false, sp = 25f)
                }
                val b = rememberReveal(650)
                Column(Modifier.weight(1f).pop(b, 24.dp)) {
                    Label("Favourite time"); Spacer(Modifier.height(6.dp)); TapeLabel(me.time, false, tilt = 1.2f, sp = 25f)
                }
            }
            Spacer(Modifier.height(24.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                val c = rememberReveal(800)
                Column(Modifier.weight(1f).pop(c, 24.dp)) {
                    Label("Most active day"); Spacer(Modifier.height(6.dp)); TapeLabel(Days[topDay], false, sp = 25f)
                    Spacer(Modifier.height(18.dp))
                    Label("Current streak"); Spacer(Modifier.height(4.dp))
                    Text("${r.streakDays} day${if (r.streakDays == 1) "" else "s"} in a row", style = PosterStyle.copy(fontSize = 19.sp, letterSpacing = (-0.3).sp, fontStyle = androidx.compose.ui.text.font.FontStyle.Normal), color = Ink)
                }
                WeekBars(r.weekdayMs, topDay, Modifier.width(150.dp).height(120.dp))
            }
            Spacer(Modifier.weight(1f))
            val fin = rememberReveal(1500)
            Row(Modifier.pop(fin, 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(38.dp).clip(CircleShape).border(1.5.dp, Ink, CircleShape), contentAlignment = Alignment.Center) {
                    Text("♫", color = Coral, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                }
                Column(Modifier.padding(start = 12.dp)) {
                    Text("You played", style = PosterStyle.copy(fontSize = 13.sp, letterSpacing = 0.sp, fontWeight = FontWeight.Medium, fontStyle = androidx.compose.ui.text.font.FontStyle.Normal), color = Ink.copy(alpha = 0.7f))
                    Text("${n(r.songCount.toLong())} different songs this ${p.noun}", style = PosterStyle.copy(fontSize = 17.sp, letterSpacing = (-0.2).sp, fontStyle = androidx.compose.ui.text.font.FontStyle.Normal), color = Ink)
                }
            }
        }
    }
}

@Composable
private fun Label(t: String) =
    Text(t, style = PosterStyle.copy(fontSize = 14.sp, letterSpacing = 0.sp, fontWeight = FontWeight.Medium, fontStyle = androidx.compose.ui.text.font.FontStyle.Normal), color = Ink.copy(alpha = 0.75f))

@Composable
private fun WeekBars(ms: LongArray, peak: Int, modifier: Modifier) {
    val grow = remember { Animatable(0f) }
    LaunchedEffect(Unit) { delay(900); grow.animateTo(1f, spring(dampingRatio = 0.6f, stiffness = 90f)) }
    val max = (ms.maxOrNull() ?: 1L).coerceAtLeast(1L).toFloat()
    Column(modifier) {
        Canvas(Modifier.weight(1f).fillMaxWidth()) {
            val slot = size.width / 7f
            val bw = slot * 0.62f
            ms.forEachIndexed { i, v ->
                val h = (size.height * (0.12f + 0.88f * v / max) * grow.value).coerceAtLeast(2f)
                drawRoundRect(if (i == peak) Coral else Ink, Offset(i * slot + (slot - bw) / 2f, size.height - h), Size(bw, h), CornerRadius(3.dp.toPx()))
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            DayInitials.forEach { Text(it, Modifier.weight(1f), textAlign = TextAlign.Center, fontSize = 11.sp, fontFamily = ChordFont, fontWeight = FontWeight.Medium, color = Ink.copy(alpha = 0.7f)) }
        }
    }
}

/* ---------- 6. outro ---------- */

@Composable
internal fun OutroSlide(p: RecapPeriod, onShare: () -> Unit, onReplay: () -> Unit, register: (GraphicsLayer) -> Unit) {
    SlideFrame(true, listOf(Decor.Checker, Decor.Arcs, Decor.Scribble, Decor.Dots), 5, register) {
        Column(Modifier.slidePad()) {
            PosterHeading(listOf("THAT WAS", "YOUR", p.word), 2, true, maxSp = 78f)
            Spacer(Modifier.height(26.dp))
            val oval = remember { Animatable(0f) }
            LaunchedEffect(Unit) { delay(900); oval.animateTo(1f, tween(1100)) }
            val rv = rememberReveal(800)
            Box(
                Modifier.pop(rv, 20.dp).rotate(-2f).padding(horizontal = 4.dp)
                    .drawBehind {
                        drawArc(Hot, -90f, 360f * oval.value, false, style = Stroke(3.5f.dp.toPx(), cap = StrokeCap.Round))
                    }
                    .padding(horizontal = 26.dp, vertical = 20.dp)
            ) {
                Text("SEE YOU IN\nTHE NEXT ONE", style = PosterStyle.copy(fontSize = 22.sp, letterSpacing = (-0.4).sp, lineHeight = 26.sp), color = Cream, textAlign = TextAlign.Center)
            }
            Spacer(Modifier.weight(1f))
            val bt = rememberReveal(1500)
            Row(Modifier.fillMaxWidth().pop(bt, 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Pill("Share", filled = true, Modifier.weight(1f), onShare)
                Pill("Replay", filled = false, Modifier.weight(1f), onReplay)
            }
        }
    }
}

@Composable
private fun Pill(text: String, filled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    // Solid backs, so they stay readable on top of the cream arcs and checker.
    Box(
        modifier.height(54.dp).clip(CircleShape)
            .background(if (filled) Coral else Ink)
            .border(2.dp, if (filled) Cream else Cream, CircleShape)
            .pressScale(0.96f, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text.uppercase(), style = PosterStyle.copy(fontSize = 17.sp, letterSpacing = 0.5.sp), color = Cream)
    }
}

/* ---------- empty ---------- */

@Composable
internal fun EmptySlide(p: RecapPeriod, register: (GraphicsLayer) -> Unit) {
    SlideFrame(false, listOf(Decor.Dots, Decor.Arcs), 0, register) {
        Column(Modifier.slidePad(), verticalArrangement = Arrangement.Center) {
            PosterHeading(listOf("NOT ENOUGH", "SOUND YET"), 1, false, maxSp = 52f)
            Spacer(Modifier.height(18.dp))
            Text("Play some music this ${p.noun} and come back. Your story builds itself.", style = PosterStyle.copy(fontSize = 17.sp, letterSpacing = 0.sp, fontWeight = FontWeight.Medium, fontStyle = androidx.compose.ui.text.font.FontStyle.Normal), color = Ink)
        }
    }
}
