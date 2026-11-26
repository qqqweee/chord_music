package com.example.chord.ui.home

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.chord.data.stats.RecapPeriod
import com.example.chord.ui.components.pressScale
import com.example.chord.ui.stats.story.AuroraBackdrop
import com.example.chord.ui.stats.story.PosterStyle
import com.example.chord.ui.stats.story.StoryColors
import com.example.chord.ui.stats.story.Typewriter
import com.example.chord.ui.stats.story.pop
import com.example.chord.ui.stats.story.rememberReveal

private fun phrases(p: RecapPeriod) = when (p) {
    RecapPeriod.Week -> listOf("Listen to your music this week.", "Your week in sound is ready.", "Who ruled your replays?")
    RecapPeriod.Month -> listOf("Relive your month in music.", "Your month in sound is here.", "30 days. One soundtrack.")
    RecapPeriod.Year -> listOf("Your whole year, in sound.", "Your year in sound is here.", "A year of music. Wait for it.")
}

/**
 * Home card that says a recap is ready. Moving gradient, typed headline,
 * light sweep, dancing bars. Tap opens the story.
 */
@Composable
fun RecapTeaser(period: RecapPeriod, onOpen: () -> Unit, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val enter = rememberReveal(150)
    val t = rememberInfiniteTransition(label = "teaser")
    val sweep = t.animateFloat(-0.4f, 1.4f, infiniteRepeatable(tween(3600, easing = LinearEasing), RepeatMode.Restart), label = "sweep")
    val pulse = t.animateFloat(0.55f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "pulse")
    val btn = t.animateFloat(1f, 1.05f, infiniteRepeatable(tween(1100), RepeatMode.Reverse), label = "btn")
    val lines = remember(period) { phrases(period) }

    Box(
        modifier.padding(horizontal = PageGutter).padding(bottom = 20.dp).fillMaxWidth().height(184.dp)
            .pop(enter, rise = 40.dp)
            .clip(RoundedCornerShape(28.dp))
            .pressScale(0.98f, onClick = onOpen)
    ) {
        AuroraBackdrop(Modifier.fillMaxSize())

        // Soft light that sweeps across now and then.
        Box(
            Modifier.fillMaxSize().drawWithContent {
                drawContent()
                val x = size.width * sweep.value
                drawRect(
                    Brush.linearGradient(
                        listOf(Color.Transparent, Color.White.copy(alpha = 0.16f), Color.Transparent),
                        start = Offset(x - size.width * 0.2f, 0f), end = Offset(x + size.width * 0.1f, size.height),
                    )
                )
            }
        )

        // Dancing bars, bottom right.
        val bars = remember { List(9) { 0.4f + (it * 37 % 10) / 14f } }
        Canvas(Modifier.align(Alignment.BottomEnd).padding(end = 18.dp, bottom = 18.dp).size(86.dp, 52.dp)) {
            val bw = size.width / (bars.size * 1.6f)
            bars.forEachIndexed { i, b ->
                val wave = 0.35f + 0.65f * kotlin.math.abs(kotlin.math.sin((sweep.value * 6f) + i * 0.8f))
                val h = size.height * (b * wave).coerceIn(0.12f, 1f)
                drawRoundRect(StoryColors.Cream.copy(alpha = 0.9f), Offset(i * bw * 1.6f, size.height - h), Size(bw, h), CornerRadius(bw / 2f))
            }
        }

        Column(Modifier.fillMaxSize().padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).graphicsLayer { alpha = pulse.value }.background(StoryColors.Cream, CircleShape))
                Spacer(Modifier.size(8.dp))
                Text(
                    "YOUR ${period.word} · RECAP",
                    fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.6.sp, color = StoryColors.Cream,
                )
                Spacer(Modifier.weight(1f))
                Box(
                    Modifier.size(28.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.22f))
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Rounded.Close, "Dismiss", tint = StoryColors.Cream, modifier = Modifier.size(16.dp)) }
            }
            Spacer(Modifier.height(14.dp))
            Typewriter(
                lines, PosterStyle.copy(fontSize = 27.sp, lineHeight = 29.sp, letterSpacing = (-1).sp), StoryColors.Cream,
                Modifier.fillMaxWidth().height(62.dp), startDelay = 700,
            )
            Spacer(Modifier.weight(1f))
            Row(
                Modifier.graphicsLayer { scaleX = btn.value; scaleY = btn.value; transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0.5f) }
                    .clip(CircleShape).background(StoryColors.Cream).padding(start = 12.dp, end = 18.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.PlayArrow, null, tint = StoryColors.Coral, modifier = Modifier.size(22.dp))
                Spacer(Modifier.size(4.dp))
                Text("Play my story", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = StoryColors.Ink)
            }
        }
    }
}
