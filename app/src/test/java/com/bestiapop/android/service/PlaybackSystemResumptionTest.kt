package com.bestiapop.android.service

import android.app.Application
import androidx.media3.session.MediaConstants
import com.bestiapop.android.data.model.PlayableItem
import com.bestiapop.android.data.model.Song
import com.bestiapop.android.data.model.TrackIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class PlaybackSystemResumptionTest {

    @Test
    fun metadataOnlyRemote_omitsNetworkArtworkAndPlaybackUri() {
        val item = PlayableItem.Remote(
            identity = TrackIdentity(
                title = "Remote",
                artist = "Artist",
                album = "Album",
                artworkUri = "https://images.example/cover.jpg",
                durationMs = 100_000L
            ),
            youtubeQueryOrId = "remote query",
            queueEntryId = "remote-slot"
        )

        val mediaItem = playbackResumptionMetadataItem(item, positionMs = 40_000L)

        assertNull(mediaItem.localConfiguration)
        assertNull(mediaItem.mediaMetadata.artworkUri)
        assertEquals(
            MediaConstants.EXTRAS_VALUE_COMPLETION_STATUS_PARTIALLY_PLAYED,
            mediaItem.mediaMetadata.extras?.getInt(
                MediaConstants.EXTRAS_KEY_COMPLETION_STATUS
            )
        )
        assertEquals(
            0.4,
            mediaItem.mediaMetadata.extras?.getDouble(
                MediaConstants.EXTRAS_KEY_COMPLETION_PERCENTAGE
            ) ?: -1.0,
            0.001
        )
    }

    @Test
    fun metadataOnlyLocal_keepsLocalArtworkAndMarksCompletedTrack() {
        val item = PlayableItem.Local(
            Song(
                id = 7L,
                uriString = "/music/song.mp3",
                title = "Local",
                artist = "Artist",
                album = "Album",
                artworkUri = "content://covers/local",
                durationMs = 100_000L
            ),
            queueEntryId = "local-slot"
        )

        val mediaItem = playbackResumptionMetadataItem(item, positionMs = 96_000L)

        assertEquals(
            "content://covers/local",
            mediaItem.mediaMetadata.artworkUri?.toString()
        )
        assertEquals(
            MediaConstants.EXTRAS_VALUE_COMPLETION_STATUS_FULLY_PLAYED,
            mediaItem.mediaMetadata.extras?.getInt(
                MediaConstants.EXTRAS_KEY_COMPLETION_STATUS
            )
        )
    }
}
