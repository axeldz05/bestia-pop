package com.bestiapop.android.domain.usecase

import com.bestiapop.android.data.model.Song

data class PlaybackPlan(
    val songsToPlay: List<Song>,
    val initialIndex: Int
)

class PlayCollectionUseCase {

    fun playCollection(collection: List<Song>, startIndex: Int = 0): PlaybackPlan {
        if (collection.isEmpty()) return PlaybackPlan(emptyList(), 0)
        val validIndex = startIndex.coerceIn(0, collection.size - 1)
        return PlaybackPlan(
            songsToPlay = collection,
            initialIndex = validIndex
        )
    }

    fun shuffleCollection(collection: List<Song>): PlaybackPlan {
        if (collection.isEmpty()) return PlaybackPlan(emptyList(), 0)
        val shuffled = collection.shuffled()
        return PlaybackPlan(
            songsToPlay = shuffled,
            initialIndex = 0
        )
    }

    fun prepareQueueAppend(currentQueue: List<Song>, newSongs: List<Song>): List<Song> {
        return currentQueue + newSongs
    }
}
