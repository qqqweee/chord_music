package com.example.chord.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.chord.ui.haptics.Haptic
import com.example.chord.ui.haptics.rememberHaptics
import com.kyant.backdrop.backdrops.LayerBackdrop
import dev.chrisbanes.haze.HazeState

data class NavItem(val label: String, val icon: ImageVector)

val DefaultNavItems = listOf(
    NavItem("Home", Icons.Rounded.Home),
    NavItem("Explore", Icons.Rounded.Explore),
    NavItem("Library", Icons.Rounded.LibraryMusic),
    NavItem("Search", Icons.Rounded.Search),
    NavItem("Settings", Icons.Rounded.Settings),
)

private val PillShape = RoundedCornerShape(50)
private val PillHeight = 70.dp
private val PillPad = 6.dp


@Composable
fun BottomNavPill(
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    items: List<NavItem> = DefaultNavItems,
    hazeState: HazeState? = null,
    /** Needed only for true liquid glass. null = hybrid glass. */
    backdrop: LayerBackdrop? = null,
) {
    val haptics = rememberHaptics()

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(PillHeight)
            .chordGlass(hazeState, PillShape, backdrop = backdrop)
            .padding(PillPad),
    ) {
        val tabWidth = maxWidth  / items.size

        val thumbX by animateDpAsState(
            targetValue = tabWidth * selected,
            animationSpec = spring(
                dampingRatio = 0.78f,
                stiffness = Spring.StiffnessMedium,
            ),
            label = "navThumb",
        )

        Box(
            Modifier
                .offset(x = thumbX)
                .width(tabWidth)
                .fillMaxHeight()
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.13f))
        )

        Row(Modifier.fillMaxSize()) {
            items.forEachIndexed { i, item ->
                val on = i == selected

                val tint by animateColorAsState(
                    targetValue = if (on) {
                        Color.White
                    } else {
                        Color.White.copy(alpha = 0.55f)
                    },
                    animationSpec = tween(200),
                    label = "navTint",
                )

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(CircleShape)
                        .clickable(
                            interactionSource = remember {
                                MutableInteractionSource()
                            },
                            indication = null,
                        ) {
                            if (!on) haptics.perform(Haptic.Select)
                            onSelect(i)
                        },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(
                        item.icon,
                        contentDescription = item.label,
                        tint = tint,
                        modifier = Modifier.size(22.dp),
                    )

                    Text(
                        text = item.label,
                        color = tint,
                        fontSize = 10.sp,
                        lineHeight = 12.sp,
                        fontWeight = if (on) {
                            FontWeight.SemiBold
                        } else {
                            FontWeight.Medium
                        },
                        maxLines = 1,
                        modifier = Modifier.padding(top = 1.dp),
                    )
                }
            }
        }
    }
}