package com.example.chord.data.playlist

data class PlaylistHeader(
    val title: String,
    /** Owner or artist line. */
    val byline: String,
    /** "Playlist • 2024" or "Album • Artist • 2023". */
    val subtitle: String,
    /** "50 songs • 3 hours". */
    val secondSubtitle: String,
    val description: String,
    val thumbnailUrl: String?,
)

data class PlaylistTrack(
    val videoId: String,
    val title: String,
    val artist: String,
    val duration: String,
    val thumbnailUrl: String?,
)

data class PlaylistPage(
    val header: PlaylistHeader?,
    val tracks: List<PlaylistTrack>,
    val continuation: String?,
)
