
package com.example.chord.ui.artist

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.example.chord.data.home.HomeItem
import com.example.chord.data.home.HomeItemType
import com.example.chord.data.model.Song
import com.example.chord.ui.components.MediaRow
import com.example.chord.ui.components.ShimmerBox
import com.example.chord.ui.components.artistOf
import com.example.chord.ui.components.pressScale
import com.example.chord.ui.home.Shelf
import com.example.chord.ui.theme.ChordTheme

private val ArtistDim = Color.White.copy(alpha = 0.62f)
private val ArtistBg: Color get() = ChordTheme.bg

@Composable
fun ArtistScreen(
    item: HomeItem,
    nowPlayingId: String?,
    isPlaying: Boolean,
    onBack: () -> Unit,
    onOpenBrowse: (HomeItem) -> Unit,
    onPlayQueue: (List<Song>, Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val id = item.browseId ?: return
    val vm: ArtistViewModel = viewModel(
        key = "artist:$id",
        factory = ArtistViewModel.Factory(id),
    )
    val ui by vm.state.collectAsState()
    val page = ui.page
    val name = page?.name?.ifBlank { null } ?: item.title
    val cover = page?.thumbnailUrl ?: item.thumbnailUrl

    val songs = remember(page) {
        page?.songs.orEmpty().map { row ->
            Song(
                title = row.title,
                details = name,
                videoId = row.videoId.orEmpty(),
                thumbnailUrl = row.thumbnailUrl,
            )
        }
    }

    val albumShelf = remember(page) {
        page?.shelves?.firstOrNull {
            it.title.contains("album", ignoreCase = true) &&
                    it.items.any { entry ->
                        entry.type == HomeItemType.Album || entry.browseId != null
                    }
        }
    }

    val recentAlbum = albumShelf?.items?.firstOrNull()
    var followed by remember(id) { mutableStateOf(false) }

    Box(modifier.fillMaxSize().background(ArtistBg)) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 190.dp),
        ) {
            item(key = "artist-hero") {
                ArtistHero(
                    name = name,
                    subtitle = page?.subtitle.orEmpty(),
                    imageUrl = cover,
                    onPlay = {
                        if (songs.isNotEmpty()) onPlayQueue(songs, 0)
                    },
                    onShuffle = {
                        if (songs.isNotEmpty()) onPlayQueue(songs.shuffled(), 0)
                    },
                    followed = followed,
                    onFollow = { followed = !followed },
                )
            }

            when {
                ui.loading && page == null -> {
                    item(key = "loading") {
                        Column(
                            Modifier.padding(
                                horizontal = 20.dp,
                                vertical = 18.dp,
                            )
                        ) {
                            repeat(4) {
                                ShimmerBox(
                                    Modifier.fillMaxWidth()
                                        .height(62.dp)
                                        .padding(vertical = 5.dp)
                                )
                            }
                        }
                    }
                }

                ui.error != null && page == null -> {
                    item(key = "error") {
                        Column(
                            Modifier.fillMaxWidth().padding(32.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                ui.error.orEmpty(),
                                color = ArtistDim,
                                fontSize = 14.sp,
                            )

                            Spacer(Modifier.height(14.dp))

                            Text(
                                "Retry",
                                color = Color.Black,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .background(Color.White)
                                    .clickable { vm.load() }
                                    .padding(
                                        horizontal = 26.dp,
                                        vertical = 10.dp,
                                    ),
                            )
                        }
                    }
                }

                else -> {
                    if (recentAlbum != null) {
                        item(key = "recent-album") {
                            RecentAlbumCard(
                                album = recentAlbum,
                                onClick = {
                                    onOpenBrowse(recentAlbum)
                                },
                            )
                        }
                    }

                    if (songs.isNotEmpty()) {
                        item(key = "top-songs-title") {
                            Text(
                                "Top songs",
                                color = Color.White,
                                fontSize = 25.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = (-0.6).sp,
                                modifier = Modifier.padding(
                                    start = 20.dp,
                                    end = 20.dp,
                                    top = 28.dp,
                                    bottom = 8.dp,
                                ),
                            )
                        }

                        itemsIndexed(
                            page?.songs.orEmpty(),
                            key = { index, song ->
                                "artist-song-$index-${song.videoId}"
                            },
                        ) { index, song ->
                            MediaRow(
                                item = song,
                                active = song.videoId == nowPlayingId,
                                playing = isPlaying,
                                onClick = {
                                    onPlayQueue(songs, index)
                                },
                                modifier = Modifier.padding(horizontal = 20.dp),
                            )
                        }
                    }

                    page?.shelves.orEmpty().forEachIndexed { index, shelf ->
                        item(key = "artist-shelf-$index-${shelf.title}") {
                            Shelf(
                                shelf = shelf,
                                nowPlayingId = nowPlayingId,
                                isPlaying = isPlaying,
                                onClick = { clicked ->
                                    if (clicked.videoId != null) {
                                        onPlayQueue(
                                            listOf(
                                                Song(
                                                    clicked.title,
                                                    artistOf(clicked.subtitle),
                                                    clicked.videoId,
                                                    clicked.thumbnailUrl,
                                                )
                                            ),
                                            0,
                                        )
                                    } else {
                                        onOpenBrowse(clicked)
                                    }
                                },
                            )
                        }
                    }

                    page?.description?.takeIf { it.isNotBlank() }?.let {
                            description ->
                        item(key = "artist-about") {
                            Column(
                                Modifier.padding(
                                    horizontal = 20.dp,
                                    vertical = 28.dp,
                                )
                            ) {
                                Text(
                                    "About",
                                    color = Color.White,
                                    fontSize = 24.sp,
                                    fontWeight = FontWeight.Bold,
                                )

                                Text(
                                    description,
                                    color = ArtistDim,
                                    fontSize = 14.sp,
                                    lineHeight = 21.sp,
                                    maxLines = 8,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(top = 8.dp),
                                )
                            }
                        }
                    }
                }
            }
        }

        Box(
            Modifier.align(Alignment.TopStart)
                .padding(top = 48.dp, start = 16.dp)
                .size(48.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.62f))
                .pressScale(onClick = onBack),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.AutoMirrored.Rounded.ArrowBack,
                contentDescription = "Back",
                tint = Color.White,
                modifier = Modifier.size(27.dp),
            )
        }
    }
}

@Composable
private fun ArtistHero(
    name: String,
    subtitle: String,
    imageUrl: String?,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    followed: Boolean,
    onFollow: () -> Unit,
) {
    Box(
        Modifier.fillMaxWidth()
            .height(535.dp)
            .background(Color(0xFF242424))
    ) {
        AsyncImage(
            model = imageUrl,
            contentDescription = "$name artist image",
            contentScale = ContentScale.Crop,
            colorFilter = ColorFilter.colorMatrix(
                ColorMatrix().apply { setToSaturation(0f) }
            ),
            modifier = Modifier.fillMaxSize(),
        )

        Box(
            Modifier.fillMaxSize().drawBehind {
                drawRect(
                    Brush.verticalGradient(
                        0f to Color.Black.copy(alpha = 0.18f),
                        0.42f to Color.Transparent,
                        0.66f to Color.Black.copy(alpha = 0.42f),
                        0.86f to ArtistBg.copy(alpha = 0.96f),
                        1f to ArtistBg,
                    )
                )
            }
        )

        Column(
            Modifier.align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                name,
                color = Color.White,
                fontSize = 39.sp,
                lineHeight = 43.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = (-1.2).sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            if (subtitle.isNotBlank()) {
                Text(
                    subtitle,
                    color = Color.White.copy(alpha = 0.66f),
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            Row(
                Modifier.fillMaxWidth().padding(top = 22.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RoundArtistAction(
                    onClick = onShuffle,
                    icon = { tint ->
                        Icon(
                            Icons.Rounded.Shuffle,
                            "Shuffle",
                            tint = tint,
                            modifier = Modifier.size(25.dp),
                        )
                    },
                )

                Spacer(Modifier.size(24.dp))

                Box(
                    Modifier.size(94.dp)
                        .clip(CircleShape)
                        .background(Color.White)
                        .pressScale(
                            pressedScale = 0.93f,
                            onClick = onPlay,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Rounded.PlayArrow,
                        contentDescription = "Play artist",
                        tint = Color(0xFF303030),
                        modifier = Modifier.size(54.dp),
                    )
                }

                Spacer(Modifier.size(24.dp))

                RoundArtistAction(
                    onClick = onFollow,
                    icon = { tint ->
                        Icon(
                            if (followed) {
                                Icons.Rounded.Star
                            } else {
                                Icons.Rounded.StarBorder
                            },
                            contentDescription = if (followed) {
                                "Unfollow artist"
                            } else {
                                "Follow artist"
                            },
                            tint = tint,
                            modifier = Modifier.size(25.dp),
                        )
                    },
                )
            }
        }
    }
}

@Composable
private fun RoundArtistAction(
    onClick: () -> Unit,
    icon: @Composable (Color) -> Unit,
) {
    Box(
        Modifier.size(68.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.09f))
            .border(
                1.dp,
                Color.White.copy(alpha = 0.13f),
                CircleShape,
            )
            .pressScale(
                pressedScale = 0.92f,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        icon(Color.White)
    }
}

@Composable
private fun RecentAlbumCard(
    album: HomeItem,
    onClick: () -> Unit,
) {
    val year = remember(album.subtitle) {
        Regex("""\b(19|20)\d{2}\b""")
            .find(album.subtitle)?.value
    }

    Row(
        Modifier.fillMaxWidth()
            .padding(horizontal = 20.dp)
            .padding(top = 12.dp)
            .clip(RoundedCornerShape(26.dp))
            .background(Color.White.copy(alpha = 0.075f))
            .border(
                1.dp,
                Color.White.copy(alpha = 0.10f),
                RoundedCornerShape(26.dp),
            )
            .pressScale(
                pressedScale = 0.985f,
                onClick = onClick,
            )
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = album.thumbnailUrl,
            contentDescription = album.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(112.dp)
                .clip(RoundedCornerShape(17.dp)),
        )

        Column(
            Modifier.weight(1f).padding(start = 16.dp, end = 8.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                year ?: "Recent release",
                color = ArtistDim,
                fontSize = 17.sp,
                maxLines = 1,
            )

            Text(
                album.title,
                color = Color.White,
                fontSize = 22.sp,
                lineHeight = 26.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )

            Text(
                "Recent Album",
                color = ArtistDim,
                fontSize = 16.sp,
                maxLines = 1,
                modifier = Modifier.padding(top = 3.dp),
            )
        }

        Box(
            Modifier.size(46.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.08f))
                .border(
                    1.dp,
                    Color.White.copy(alpha = 0.12f),
                    CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Rounded.Add,
                contentDescription = "Open album",
                tint = Color.White,
                modifier = Modifier.size(25.dp),
            )
        }
    }
}
