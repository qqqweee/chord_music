package com.example.chord.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.chord.data.model.Song
import com.example.chord.data.repository.MusicRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class SearchUiState(
    val query: String = "Arijit Singh",
    val songs: List<Song> = emptyList(),
    val isLoading: Boolean = false,
    val message: String = ""
)

class SearchViewModel(
    private val repository: MusicRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(SearchUiState())
    val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()

    fun updateQuery(query: String) {
        _uiState.value = _uiState.value.copy(query = query)
    }

    fun search() {
        val query = _uiState.value.query.trim()

        if (query.isBlank() || _uiState.value.isLoading) return

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isLoading = true,
                message = ""
            )

            try {
                val songs = repository.searchSongs(query)

                _uiState.value = _uiState.value.copy(
                    songs = songs,
                    isLoading = false,
                    message = if (songs.isEmpty()) {
                        "No songs found."
                    } else {
                        "${songs.size} songs found"
                    }
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    message = "Search failed: ${e.message ?: "Unknown error"}"
                )
            }
        }
    }
}