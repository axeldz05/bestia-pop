package com.bestiapop.android.data.playback

import com.bestiapop.android.data.model.Song

enum class PlaybackChangeHint {
    AUTO,
    METADATA_UPDATE,
    NEW_PLAYBACK
}

enum class PlaybackTrackChange {
    STOPPED,
    METADATA_UPDATE,
    NEW_PLAYBACK
}

/**
 * Separates updates to the current queue item from a new playback occurrence.
 *
 * Identity alone cannot recognize Repeat One or two adjacent occurrences of the same song, so
 * callers handling a real Media3 transition must pass [PlaybackChangeHint.NEW_PLAYBACK].
 */
object PlaybackTrackChangePolicy {
    fun resolve(
        previous: Song?,
        current: Song?,
        hint: PlaybackChangeHint = PlaybackChangeHint.AUTO
    ): PlaybackTrackChange {
        if (current == null) return PlaybackTrackChange.STOPPED
        if (previous == null || hint == PlaybackChangeHint.NEW_PLAYBACK) {
            return PlaybackTrackChange.NEW_PLAYBACK
        }
        return if (sameIdentity(previous, current)) {
            PlaybackTrackChange.METADATA_UPDATE
        } else {
            PlaybackTrackChange.NEW_PLAYBACK
        }
    }

    fun sameIdentity(first: Song, second: Song): Boolean {
        if (first.id > 0L && second.id > 0L) return first.id == second.id
        val firstUri = first.uriString.trim()
        val secondUri = second.uriString.trim()
        return firstUri.isNotEmpty() && firstUri == secondUri
    }
}
