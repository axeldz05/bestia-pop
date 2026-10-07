package com.bestiapop.android.domain.usecase

import com.bestiapop.android.data.model.Song
import com.bestiapop.android.domain.radio.LocalMetadataRadio
import com.bestiapop.android.domain.radio.RadioEngine

/**
 * Builds a playback queue for a searched song, placing the seed song first (index 0),
 * followed by a batch of similar songs (up to [limitSimilar]), followed by the remaining
 * songs of [fullCollection] in their original order.
 */
class BuildSearchPlaybackQueueUseCase(
    private val localMetadataRadio: LocalMetadataRadio = LocalMetadataRadio(),
) {
    fun execute(
        seedSong: Song,
        fullCollection: List<Song>,
        limitSimilar: Int = DEFAULT_SIMILAR_LIMIT,
    ): List<Song> {
        if (fullCollection.isEmpty()) return listOf(seedSong)

        val similarSongs =
            localMetadataRadio.suggestSongs(
                seed = seedSong,
                library = fullCollection,
                limit = limitSimilar,
            )

        val seenIds = HashSet<Long>(similarSongs.size + 1)
        seenIds.add(seedSong.id)
        for (song in similarSongs) {
            seenIds.add(song.id)
        }

        val queue = ArrayList<Song>(fullCollection.size)
        queue.add(seedSong)
        queue.addAll(similarSongs)

        for (song in fullCollection) {
            if (song.id !in seenIds) {
                queue.add(song)
            }
        }

        return queue
    }

    companion object {
        const val DEFAULT_SIMILAR_LIMIT = RadioEngine.DEFAULT_LIMIT
    }
}
