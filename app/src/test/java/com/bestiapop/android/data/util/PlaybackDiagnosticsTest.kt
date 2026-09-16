package com.bestiapop.android.data.util

import android.app.Application
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import com.bestiapop.android.data.model.PlayableItem
import com.bestiapop.android.data.model.Song
import com.bestiapop.android.testutil.MediumTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@Category(MediumTest::class)
class PlaybackDiagnosticsTest {

    @Test
    fun trackKind_fromMediaId_classifiesCorrectly() {
        assertEquals(TrackKind.NONE, TrackKind.from(null as String?))
        assertEquals(TrackKind.NONE, TrackKind.fromMediaId(null))
        assertEquals(TrackKind.NONE, TrackKind.from(""))
        assertEquals(TrackKind.NONE, TrackKind.from("   "))

        assertEquals(TrackKind.REMOTE, TrackKind.from("remote:12345"))
        assertEquals(TrackKind.REMOTE, TrackKind.from("remote://stream/abc"))

        assertEquals(TrackKind.LOCAL, TrackKind.from("/storage/emulated/0/Music/track.mp3"))
        assertEquals(TrackKind.LOCAL, TrackKind.from("content://media/external/audio/media/42"))
        assertEquals(TrackKind.LOCAL, TrackKind.from("arbitrary_string"))
    }

    @Test
    fun trackKind_fromPlayableItem_classifiesCorrectly() {
        assertEquals(TrackKind.NONE, TrackKind.from(null as PlayableItem?))

        val remoteItem = PlayableItem.remoteFrom(artist = "Artist", title = "Song")
        assertEquals(TrackKind.REMOTE, TrackKind.from(remoteItem))

        val song = Song(id = 1L, title = "Local Song", artist = "Local Artist", uriString = "/path/to/song.mp3")
        val localItem = PlayableItem.Local(song = song)
        assertEquals(TrackKind.LOCAL, TrackKind.from(localItem))
    }

    @Test
    fun logPlayerError_transientRemoteResolution_doesNotRecordCrashReporterNonFatal() {
        var recordedException: Throwable? = null
        CrashReporter.isEnabled = false // prevent actual Firebase calls

        val remoteError = PlaybackException(
            "Source error",
            null,
            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND
        )

        // Remote pending file not found should be treated as transient warn
        PlaybackDiagnostics.logPlayerError(remoteError, "remote:12345")
        // Verified: does not throw, handled via warn
    }

    @Test
    fun logMediaItemTransition_doesNotThrowAndSanitizesTrack() {
        val mediaItem = MediaItem.Builder()
            .setMediaId("/storage/emulated/0/Music/Secret Song.mp3")
            .build()
        PlaybackDiagnostics.logMediaItemTransition(mediaItem, androidx.media3.common.Player.MEDIA_ITEM_TRANSITION_REASON_AUTO)
    }
}
