package com.bestiapop.android.data.listenbrainz

import com.bestiapop.android.data.model.OnlineCatalogTrack
import com.bestiapop.android.data.model.PlayableItem
import com.bestiapop.android.data.model.Song
import com.bestiapop.android.data.model.TrackIdentity
import com.bestiapop.android.data.model.TrackMeta
import com.bestiapop.android.data.model.toListenBrainzCatalogTrack
import com.bestiapop.android.domain.util.TrackMatchKeys

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

    fun toOnlineCatalogTrack(): OnlineCatalogTrack =
        identity.toListenBrainzCatalogTrack(recordingMbid)
}

/** L2: wrap identity + optional local match into a discover/CF/LB matched row. */
fun TrackIdentity.toMatchedRemote(
    localSong: Song?,
    recordingMbid: String? = null,
    score: Double? = null
): MatchedRemoteTrack = MatchedRemoteTrack(
    identity = this,
    recordingMbid = recordingMbid,
    localSong = localSong,
    score = score
)

fun List<MatchedRemoteTrack>.toPlayableItems(): List<PlayableItem> =
    map { it.toPlayableItem() }

fun List<MatchedRemoteTrack>.matchedCount(): Int = count { it.localSong != null }

fun List<MatchedRemoteTrack>.streamCount(): Int = size - matchedCount()

/** Re-bind unmatched rows against [library]; keep already-matched locals. */
fun List<MatchedRemoteTrack>.rematchLocals(library: List<Song>): List<MatchedRemoteTrack> =
    TrackMatchKeys.matchMetasAgainstLibrary(this, library) { match, local ->
        if (match.localSong != null) match
        else match.copy(localSong = local)
    }

fun List<MatchedRemoteTrack>.unmatchedCatalogTracks(): List<OnlineCatalogTrack> =
    filter { it.localSong == null }.map { it.toOnlineCatalogTrack() }
