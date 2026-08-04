package com.bestiapop.android.data.listenbrainz

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
    val recordingMbid: String,
    val title: String,
    val artist: String,
    val releaseName: String? = null
)
