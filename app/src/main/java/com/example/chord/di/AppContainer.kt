package com.example.chord.di

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.example.chord.data.repository.MusicRepository
import com.example.chord.playback.PlaybackController
import com.example.chord.ui.player.PlayerViewModel
import com.example.chord.ui.search.SearchViewModel

class AppContainer(context: Context) {

    private val appContext = context.applicationContext
    private val musicRepository = MusicRepository()

    fun searchViewModelFactory(): ViewModelProvider.Factory =
        factory {
            SearchViewModel(musicRepository)
        }

    fun playerViewModelFactory(): ViewModelProvider.Factory =
        factory {
            PlayerViewModel(PlaybackController(appContext))
        }

    private inline fun <reified T : ViewModel> factory(
        crossinline create: () -> T
    ): ViewModelProvider.Factory =
        object : ViewModelProvider.Factory {

            @Suppress("UNCHECKED_CAST")
            override fun <VM : ViewModel> create(
                modelClass: Class<VM>
            ): VM {
                require(modelClass.isAssignableFrom(T::class.java)) {
                    "Unknown ViewModel: ${modelClass.name}"
                }
                return create() as VM
            }
        }
}