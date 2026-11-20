package com.example.chord.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.chord.data.library.PlaylistSummary
import com.example.chord.ui.components.CoverImage
import com.example.chord.ui.theme.ChordTheme

/** Favourites = gradient star. Else 1 cover, or a 2x2 collage when 4+ songs. */
@Composable
fun PlaylistCover(p: PlaylistSummary, size: Dp, shape: Shape, modifier: Modifier = Modifier) {
    Box(modifier.size(size).clip(shape).background(ChordTheme.surfaceHigh), contentAlignment = Alignment.Center) {
        val covers = p.covers.filterNotNull()
        when {
            p.isFavourites -> FavouriteBadge(size)
            covers.size >= 4 -> {
                val half = size / 2
                Column(Modifier.fillMaxSize()) {
                    Row(Modifier.weight(1f)) {
                        CoverImage(covers[0], half, RoundedCornerShape(0.dp)); CoverImage(covers[1], half, RoundedCornerShape(0.dp))
                    }
                    Row(Modifier.weight(1f)) {
                        CoverImage(covers[2], half, RoundedCornerShape(0.dp)); CoverImage(covers[3], half, RoundedCornerShape(0.dp))
                    }
                }
            }
            covers.isNotEmpty() -> CoverImage(covers[0], size, RoundedCornerShape(0.dp))
            else -> Icon(Icons.Rounded.MusicNote, null, tint = ChordTheme.textDim, modifier = Modifier.size(size / 2.5f))
        }
    }
}
