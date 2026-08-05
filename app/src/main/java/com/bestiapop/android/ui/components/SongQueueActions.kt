package com.bestiapop.android.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.bestiapop.android.data.model.Song
import com.bestiapop.android.ui.MusicPlayerViewModel

data class SongQueueActions(
    val onPlayNext: (Song) -> Unit,
    val onAddToQueue: (Song) -> Unit,
    val onStartRadio: (Song) -> Unit,
)

@Composable
fun rememberSongQueueActions(viewModel: MusicPlayerViewModel): SongQueueActions {
    return remember(viewModel) {
        SongQueueActions(
            onPlayNext = { viewModel.playNextInQueue(it) },
            onAddToQueue = { viewModel.addToQueue(it) },
            onStartRadio = { viewModel.startRadio(seedSong = it) },
        )
    }
}
