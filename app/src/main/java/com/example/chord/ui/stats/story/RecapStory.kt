package com.example.chord.ui.stats.story

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.example.chord.data.home.HomeItem
import com.example.chord.data.home.HomeItemType
import com.example.chord.data.stats.ListenStatsStore
import com.example.chord.data.stats.Recap
import com.example.chord.data.stats.RecapPeriod
import com.example.chord.data.stats.RecapSchedule
import com.example.chord.data.stats.TopSong
import com.example.chord.ui.components.AppLogo
import com.example.chord.ui.components.CoverImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

/** What the player is doing right now. Read on demand, so the story does not recompose on every tick. */
class PlayerSnap(val videoId: String?, val playing: Boolean, val durationMs: Long)

private enum class Kind { Intro, Reveal, Artists, Tracks, Dna, Outro, Empty }

private class Slide(val kind: Kind, val ms: Long, val song: TopSong?, val songDelay: Long = 0) {
    val onInk get() = kind == Kind.Reveal || kind == Kind.Tracks || kind == Kind.Outro
}

private const val OPENER_MS = 2300L

/**
 * The story. Full screen, above everything.
 * - Opens with a gradient "ignite" moment while the numbers load.
 * - Tap right = next, tap left = back, hold = pause, drag down = close.
 * - Each slide can start a song: the intro plays your top song, the #1 artist slide
 *   plays the song YOU played most by that artist (Spotify style).
 * - [onClose] says if music was started, so the host can pause it.
 */
@Composable
fun RecapStory(
    period: RecapPeriod,
    nowPlayingId: String?,
    isPlaying: Boolean,
    onPlay: (HomeItem) -> Unit,
    onTogglePlay: () -> Unit,
    onSeek: (Long) -> Unit,
    onVolume: (Float) -> Unit,
    snapshot: () -> PlayerSnap,
    onClose: (playedAny: Boolean) -> Unit,
) {
    val ctx = LocalContext.current
    // However the story ends, music volume goes back to full.
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { onVolume(1f) } }
    val recap by produceState<Recap?>(null, period) { value = ListenStatsStore.get(ctx).recap(period.days) }
    var opened by remember { mutableStateOf(false) }
    var played by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { RecapSchedule.markSeen(ctx, period); delay(OPENER_MS); opened = true }
    BackHandler { onClose(played) }

    Box(Modifier.fillMaxSize().background(StoryColors.Ink)) {
        Crossfade(opened && recap != null, animationSpec = tween(450), label = "storyOpen") { ready ->
            if (!ready) StoryOpener(period)
            else StoryPlayer(recap!!, period, nowPlayingId, isPlaying, onPlay, onTogglePlay, onSeek, onVolume, snapshot, { played = true }, { onClose(played) })
        }
    }
}

/* ---------- opener ---------- */

@Composable
private fun StoryOpener(p: RecapPeriod) {
    Box(Modifier.fillMaxSize()) {
        AuroraBackdrop(Modifier.fillMaxSize())
        val ringT = rememberInfiniteTransition(label = "rings")
            .animateFloat(0f, 1f, infiniteRepeatable(tween(2400, easing = LinearEasing)), label = "ringT")
        Canvas(Modifier.fillMaxSize()) {
            repeat(3) { i ->
                val f = (ringT.value + i / 3f) % 1f
                drawCircle(StoryColors.Cream.copy(alpha = (1f - f) * 0.35f), size.minDimension * (0.12f + f * 0.55f), center, style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()))
            }
        }
        Column(
            Modifier.fillMaxSize().padding(horizontal = 32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val logo = rememberReveal(100)
            AppLogo(Modifier.graphicsLayer { val s = logo.value.coerceIn(0f, 1.1f); scaleX = s; scaleY = s }, logoSize = 84.dp)
            Spacer(Modifier.height(36.dp))
            Typewriter(
                listOf("Your ${p.noun} in sound."),
                PosterStyle.copy(fontSize = 34.sp, lineHeight = 36.sp),
                StoryColors.Cream, loop = false, startDelay = 350, charMs = 55,
            )
            Spacer(Modifier.height(10.dp))
            val sub = rememberReveal(1500)
            Text(
                "Headphones on.", style = PosterStyle.copy(fontSize = 16.sp, letterSpacing = 0.sp, fontWeight = FontWeight.Medium, fontStyle = androidx.compose.ui.text.font.FontStyle.Normal),
                color = StoryColors.Cream.copy(alpha = 0.8f), modifier = Modifier.graphicsLayer { alpha = sub.value.coerceIn(0f, 1f) },
            )
        }
    }
}

/* ---------- player ---------- */

@Composable
private fun StoryPlayer(
    r: Recap,
    period: RecapPeriod,
    nowPlayingId: String?,
    isPlaying: Boolean,
    onPlay: (HomeItem) -> Unit,
    onTogglePlay: () -> Unit,
    onSeek: (Long) -> Unit,
    onVolume: (Float) -> Unit,
    snapshot: () -> PlayerSnap,
    onStartedMusic: () -> Unit,
    onClose: () -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    val slides = remember(r) {
        if (r.isEmpty) listOf(Slide(Kind.Empty, 0, null)) else buildList {
            add(Slide(Kind.Intro, 7000, r.leadSong, 700))
            r.topArtists.firstOrNull()?.let { add(Slide(Kind.Reveal, 10500, it.topSong, REVEAL_AT_MS)) }
            if (r.topArtists.size >= 2) add(Slide(Kind.Artists, 8000, null))
            if (r.topSongs.isNotEmpty()) add(Slide(Kind.Tracks, 8000, r.leadSong, 500))
            add(Slide(Kind.Dna, 8500, null))
            add(Slide(Kind.Outro, 0, null))
        }
    }
    val last = slides.lastIndex

    var index by remember { mutableIntStateOf(0) }
    var paused by remember { mutableStateOf(false) }
    var current by remember { mutableStateOf<TopSong?>(null) }
    val progress = remember { Animatable(0f) }
    val layers = remember { mutableMapOf<Int, GraphicsLayer>() }
    val drag = remember { Animatable(0f) }

    suspend fun fade(from: Float, to: Float, ms: Int) {
        val steps = 14
        for (i in 1..steps) { onVolume(from + (to - from) * i / steps); delay((ms / steps).toLong()) }
    }

    /**
     * Plays the main part of a song, not the intro:
     * fade the old song out, load the new one silent, jump to ~35% in, fade it in.
     */
    suspend fun snippet(s: TopSong) {
        val already = snapshot().videoId == s.videoId
        if (!already) {
            if (snapshot().playing) fade(1f, 0f, 320)
            onVolume(0f)
            onPlay(HomeItem(s.title, s.artist, s.thumb, s.videoId, null, HomeItemType.Song))
            var waited = 0
            while (waited < 9000) {
                val n = snapshot()
                if (n.videoId == s.videoId && n.playing && n.durationMs > 0) break
                delay(100); waited += 100
            }
        } else onVolume(0f)
        val d = snapshot().durationMs
        // Leave 25 s at the end so the snippet never runs out mid-story.
        if (d >= 45_000L) onSeek((d * 0.35).toLong().coerceAtMost(d - 25_000L))
        delay(250)
        fade(0f, 1f, 700)
    }

    var playJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    fun play(s: TopSong) {
        current = s
        onStartedMusic()
        playJob?.cancel()
        playJob = scope.launch { snippet(s) }
    }

    // Last slide shares one summary poster. Other slides share their own picture.
    fun shareSlide(i: Int) {
        scope.launch {
            if (slides[i].kind == Kind.Outro) RecapShare.share(ctx, r, period)
            else layers[i]?.let { shareLayer(ctx, it, period) }
        }
    }

    fun goTo(i: Int) {
        if (i !in 0..last) return
        scope.launch { progress.snapTo(0f); index = i }
    }

    // One timer for the whole story. Pausing cancels it and it resumes from where the bar is.
    LaunchedEffect(index, paused) {
        if (paused) return@LaunchedEffect
        val s = slides[index]
        if (s.ms <= 0) { progress.snapTo(1f); return@LaunchedEffect }
        progress.animateTo(1f, tween(((1f - progress.value) * s.ms).toInt().coerceAtLeast(1), easing = LinearEasing))
        if (index < last) { progress.snapTo(0f); index++ }
    }

    // Music for the slide.
    LaunchedEffect(index) {
        val s = slides[index]
        val song = s.song ?: return@LaunchedEffect
        delay(s.songDelay)
        if (current?.videoId != song.videoId) play(song)
    }

    val onInk = slides[index].onInk
    val fg = if (onInk) StoryColors.Cream else StoryColors.Ink

    Box(
        Modifier.fillMaxSize()
            .graphicsLayer {
                translationY = drag.value
                val sc = 1f - (drag.value / 3000f).coerceIn(0f, 0.1f)
                scaleX = sc; scaleY = sc
            }
            .clip(RoundedCornerShape(if (drag.value > 1f) 28.dp else 0.dp))
            .pointerInput(index, last) {
                detectTapGestures(onPress = { off ->
                    val t0 = System.nanoTime()
                    paused = true
                    val ok = tryAwaitRelease()
                    paused = false
                    // Short press = tap. Long press = hold to pause, nothing else.
                    if (ok && System.nanoTime() - t0 < 250_000_000L) {
                        if (off.x < size.width * 0.3f) goTo(if (index == 0) 0 else index - 1) else goTo(index + 1)
                    }
                })
            }
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragEnd = { scope.launch { if (drag.value > 260f) onClose() else drag.animateTo(0f, tween(220)) } },
                    onDragCancel = { scope.launch { drag.animateTo(0f, tween(220)) } },
                    onVerticalDrag = { c, d -> c.consume(); scope.launch { drag.snapTo((drag.value + d).coerceAtLeast(0f)) } },
                )
            }
    ) {
        AnimatedContent(
            targetState = index,
            transitionSpec = { (fadeIn(tween(380)) + scaleIn(tween(420), initialScale = 0.95f)) togetherWith fadeOut(tween(200)) },
            label = "slide",
        ) { i ->
            val reg: (GraphicsLayer) -> Unit = { layers[i] = it }
            val s = slides[i]
            when (s.kind) {
                Kind.Intro -> IntroSlide(r, period, reg)
                Kind.Reveal -> ArtistRevealSlide(r.topArtists.first(), r.totalMs, period, reg)
                Kind.Artists -> ArtistsSlide(r.topArtists, current?.videoId ?: nowPlayingId, ::play, reg)
                Kind.Tracks -> TracksSlide(r.topSongs, current?.videoId ?: nowPlayingId, ::play, reg)
                Kind.Dna -> DnaSlide(r, period, reg)
                Kind.Outro -> OutroSlide(period, onShare = { shareSlide(i) }, onReplay = { goTo(0) }, reg)
                Kind.Empty -> EmptySlide(period, reg)
            }
        }

        // Top: progress bars + share + close.
        Column(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 16.dp).padding(top = 10.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                slides.forEachIndexed { i, _ ->
                    Box(
                        Modifier.weight(1f).height(3.dp).clip(RoundedCornerShape(2.dp)).background(fg.copy(alpha = 0.25f))
                            .drawBehind {
                                val f = when { i < index -> 1f; i == index -> progress.value; else -> 0f }
                                drawRect(fg, size = Size(size.width * f, size.height))
                            }
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth().height(36.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                if (slides[index].kind != Kind.Empty) RoundIcon(Icons.Rounded.Share, "Share", fg) {
                    shareSlide(index)
                }
                Spacer(Modifier.size(6.dp))
                RoundIcon(Icons.Rounded.Close, "Close", fg) { onClose() }
            }
        }

        // Bottom: what is playing. Tap to pause / resume.
        val now = current
        if (now != null && slides[index].kind != Kind.Outro && slides[index].kind != Kind.Empty) {
            NowPlayingChip(
                now, isPlaying, onInk,
                Modifier.align(Alignment.BottomStart).navigationBarsPadding().padding(start = 20.dp, bottom = 22.dp),
                onTogglePlay,
            )
        }
    }
}

@Composable
private fun RoundIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, desc: String, tint: Color, onClick: () -> Unit) {
    Box(
        Modifier.size(36.dp).clip(CircleShape)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, desc, tint = tint, modifier = Modifier.size(22.dp)) }
}

@Composable
private fun NowPlayingChip(song: TopSong, isPlaying: Boolean, onInk: Boolean, modifier: Modifier, onToggle: () -> Unit) {
    val bg = if (onInk) StoryColors.Cream.copy(alpha = 0.14f) else StoryColors.Ink.copy(alpha = 0.92f)
    val fg = if (onInk) StoryColors.Cream else StoryColors.Cream
    val enter = rememberReveal(0)
    Row(
        modifier.widthIn(max = 300.dp).graphicsLayer { alpha = enter.value.coerceIn(0f, 1f); translationY = (1f - enter.value) * 30f }
            .clip(RoundedCornerShape(18.dp)).background(bg)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onToggle)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CoverImage(song.thumb, 40.dp, RoundedCornerShape(10.dp))
        Column(Modifier.padding(horizontal = 10.dp).weight(1f, fill = false)) {
            Text("NOW PLAYING", fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp, color = StoryColors.Hot)
            Text(song.title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Bars(isPlaying, fg)
        Spacer(Modifier.size(8.dp))
        Icon(if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, null, tint = fg, modifier = Modifier.size(22.dp))
        Spacer(Modifier.size(4.dp))
    }
}

@Composable
private fun Bars(active: Boolean, color: Color) {
    val t = rememberInfiniteTransition(label = "eq")
    val a = t.animateFloat(0.25f, 1f, infiniteRepeatable(tween(520, easing = LinearEasing), RepeatMode.Reverse), label = "a")
    val b = t.animateFloat(0.25f, 1f, infiniteRepeatable(tween(640, easing = LinearEasing), RepeatMode.Reverse), label = "b")
    val c = t.animateFloat(0.25f, 1f, infiniteRepeatable(tween(450, easing = LinearEasing), RepeatMode.Reverse), label = "c")
    Canvas(Modifier.size(16.dp)) {
        val bw = size.width / 5f
        listOf(a, b, c).forEachIndexed { i, s ->
            val h = size.height * (if (active) s.value else 0.3f)
            drawRoundRect(color, Offset(i * bw * 1.5f, size.height - h), Size(bw, h), CornerRadius(bw / 2f))
        }
    }
}

/* ---------- share ---------- */

private suspend fun shareLayer(ctx: Context, layer: GraphicsLayer, p: RecapPeriod) {
    try {
        val bmp: Bitmap = layer.toImageBitmap().asAndroidBitmap()
        val file = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            // Same folder the old stats share used, so the existing FileProvider paths allow it.
            val dir = File(ctx.cacheDir, "share").apply { mkdirs() }
            File(dir, "chord_recap_${p.name.lowercase()}.png").also { f ->
                f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
        }
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TEXT, "My ${p.noun} in sound, on Chord")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ctx.startActivity(Intent.createChooser(send, "Share your ${p.noun}").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: Exception) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        android.util.Log.w("ChordRecap", "share failed: ${e.message}", e)
        android.widget.Toast.makeText(ctx, "Could not share this slide", android.widget.Toast.LENGTH_SHORT).show()
    }
}
