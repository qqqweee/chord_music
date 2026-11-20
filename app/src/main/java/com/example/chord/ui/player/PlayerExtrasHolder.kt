package com.example.chord.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue

/**
 * Builds the PlayerExtras that PlayerScreen's favourite, lyrics-provider and queue buttons need,
 * straight from PlayerViewModel. Call it where PlayerScreen is called and pass `extras = ...`.
 */
@Composable
fun rememberPlayerExtras(vm: PlayerViewModel): PlayerExtras {
    val favourite by vm.isFavorite.collectAsState()
    val providers by vm.providers.collectAsState()      // ALL providers, live status
    val selected by vm.selectedLyrics.collectAsState()
    val shuffle by vm.shuffle.collectAsState()
    val repeat by vm.repeat.collectAsState()
    val autoplay by vm.autoplay.collectAsState()

    return PlayerExtras(
        isFavorite = favourite,
        onToggleFavorite = vm::toggleFavorite,
        lyricsProviderName = selected?.providerName,
        providers = providers,
        onSelectProvider = vm::selectLyricsProvider,
        onRemoveQueueItem = vm::removeQueueItem,
        onMoveQueueItem = vm::moveQueueItem,             // drag reorder
        shuffle = shuffle, onToggleShuffle = vm::toggleShuffle,
        repeat = repeat, onCycleRepeat = vm::cycleRepeat,
        autoplay = autoplay, onToggleAutoplay = vm::toggleAutoplay,
    )
}