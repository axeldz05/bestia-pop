package com.bestiapop.android.data.listenbrainz

import com.bestiapop.android.data.model.PlayableItem
import com.bestiapop.android.data.model.Song
import com.bestiapop.android.data.model.TrackIdentity
import com.bestiapop.android.data.model.TrackMeta

/**
 * Library-matched remote recording (CF recommendations or LB playlist row).
 * [score] is CF-only; LB leaves it null.
 */
data class MatchedRemoteTrack(
    val identity: TrackIdentity,
    val recordingMbid: String?,
    val localSong: Song?,
    val score: Double? = null
) : TrackMeta by identity {
    fun toPlayableItem(): PlayableItem = PlayableItem.fromLibraryOrRemote(
        local = localSong,
        identity = identity,
        recordingMbid = recordingMbid
    )
}

fun List<MatchedRemoteTrack>.toPlayableItems(): List<PlayableItem> =
    map { it.toPlayableItem() }
