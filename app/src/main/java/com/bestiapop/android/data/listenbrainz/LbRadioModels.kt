package com.bestiapop.android.data.listenbrainz

import com.bestiapop.android.data.model.TrackIdentity
import com.bestiapop.android.data.model.TrackMeta

data class LbMetadataLookup(
    val artistMbids: List<String>,
    val recordingMbid: String?,
    val artistCreditName: String?,
    val recordingName: String?
)

data class LbRadioRecording(
    val recordingMbid: String,
    val similarArtistMbid: String?,
    val similarArtistName: String?,
    val totalListenCount: Long = 0
)

data class LbRecordingMetadata(
    val identity: TrackIdentity,
    val recordingMbid: String
) : TrackMeta by identity {
    companion object {
        /** L2: flat recording metadata construction (identity is Level 1). */
        operator fun invoke(
            title: String,
            artist: String,
            album: String = "",
            recordingMbid: String
        ) = LbRecordingMetadata(
            identity = TrackIdentity(title = title, artist = artist, album = album),
            recordingMbid = recordingMbid
        )
    }
}
