package com.bestiapop.android.ui.state

import com.bestiapop.android.data.preferences.PLAYLIST_DETAIL_CF
import com.bestiapop.android.data.preferences.PLAYLIST_DETAIL_LB
import com.bestiapop.android.data.preferences.PLAYLIST_DETAIL_LOCAL
import com.bestiapop.android.data.preferences.UiNavSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistDetailNavTest {

    @Test
    fun fromSnapshot_mapsKnownKinds() {
        assertEquals(PlaylistDetailNav.None, PlaylistDetailNav.fromSnapshot(UiNavSnapshot()))
        assertEquals(
            PlaylistDetailNav.Local(7L),
            PlaylistDetailNav.fromSnapshot(
                UiNavSnapshot(playlistDetailKind = PLAYLIST_DETAIL_LOCAL, playlistLocalId = 7L)
            )
        )
        assertEquals(
            PlaylistDetailNav.ListenBrainz("mbid-1"),
            PlaylistDetailNav.fromSnapshot(
                UiNavSnapshot(playlistDetailKind = PLAYLIST_DETAIL_LB, playlistLbMbid = "mbid-1")
            )
        )
        assertTrue(
            PlaylistDetailNav.fromSnapshot(
                UiNavSnapshot(playlistDetailKind = PLAYLIST_DETAIL_CF)
            ) is PlaylistDetailNav.CfRecommendations
        )
    }

    @Test
    fun kindName_roundTrip() {
        assertEquals(PLAYLIST_DETAIL_LOCAL, PlaylistDetailNav.Local(1L).kindName())
        assertEquals(PLAYLIST_DETAIL_LB, PlaylistDetailNav.ListenBrainz("x").kindName())
        assertEquals(PLAYLIST_DETAIL_CF, PlaylistDetailNav.CfRecommendations.kindName())
    }
}
