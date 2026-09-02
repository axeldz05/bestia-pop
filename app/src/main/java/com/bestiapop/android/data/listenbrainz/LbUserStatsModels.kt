package com.bestiapop.android.data.listenbrainz

data class LbUserStatArtist(
    val artistName: String,
    val listenCount: Long = 0,
    val artistMbid: String? = null
)

data class LbUserStatRelease(
    val releaseName: String,
    val artistName: String,
    val listenCount: Long = 0,
    val releaseMbid: String? = null
)

data class LbUserStatRecording(
    val trackName: String,
    val artistName: String,
    val releaseName: String? = null,
    val listenCount: Long = 0,
    val recordingMbid: String? = null
)
