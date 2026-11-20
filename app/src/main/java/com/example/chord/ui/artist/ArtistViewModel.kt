package com.example.chord.ui.artist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.chord.data.home.ArtistPage
import com.example.chord.data.home.HomeRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ArtistUiState(
    val loading: Boolean = true,
    val page: ArtistPage? = null,
    val error: String? = null,
)

class ArtistViewModel(private val browseId: String) : ViewModel() {
    private val repo = HomeRepository()
    private val _state = MutableStateFlow(ArtistUiState())
    val state: StateFlow<ArtistUiState> = _state.asStateFlow()

    init { load() }

    fun load() {
        _state.value = ArtistUiState(loading = true)
        viewModelScope.launch {
            repo.artist(browseId)
                .onSuccess { _state.value = ArtistUiState(loading = false, page = it) }
                .onFailure { _state.value = ArtistUiState(loading = false, error = it.message ?: "Couldn't load artist") }
        }
    }

    class Factory(private val id: String) : androidx.lifecycle.ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = ArtistViewModel(id) as T
    }
}
