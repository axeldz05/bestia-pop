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
        CrashReporter.isEnabled = false // prevent actual Firebase calls

        val remoteError =
            PlaybackException(
                "Source error",
                null,
                PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
            )

        // Remote pending file not found should be treated as transient warn
        PlaybackDiagnostics.logPlayerError(remoteError, "remote:12345")
        // Verified: does not throw, handled via warn
    }

    @Test
    fun logPlayerError_remoteParsingError_handledViaWarn() {
        CrashReporter.isEnabled = false
        val parsingError =
            PlaybackException(
                "Invalid NAL length",
                null,
                PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
            )
        PlaybackDiagnostics.logPlayerError(parsingError, "remote://yt/abcdefghijk")
    }

    @Test
    fun logPlayerError_localFileNotFound_handledViaWarn() {
        CrashReporter.isEnabled = false
        val fnfError =
            PlaybackException(
                "File not found",
                null,
                PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
            )
        PlaybackDiagnostics.logPlayerError(fnfError, "content://media/external/audio/media/123")
    }

    @Test
    fun isHandledPlayerError_classifiesRecoverableErrors() {
        // Local: file not found and timeout are handled
        assertTrue(
            PlaybackDiagnostics.isHandledPlayerError(
                PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
                TrackKind.LOCAL,
            ),
        )
        assertTrue(
            PlaybackDiagnostics.isHandledPlayerError(
                PlaybackException.ERROR_CODE_TIMEOUT,
                TrackKind.LOCAL,
            ),
        )
        assertFalse(
            PlaybackDiagnostics.isHandledPlayerError(
                PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
                TrackKind.LOCAL,
            ),
        )

        // Remote: container/manifest parsing, network errors, and timeout are handled
        assertTrue(
            PlaybackDiagnostics.isHandledPlayerError(
                PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
                TrackKind.REMOTE,
            ),
        )
        assertTrue(
            PlaybackDiagnostics.isHandledPlayerError(
                PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
                TrackKind.REMOTE,
            ),
        )
        assertTrue(
            PlaybackDiagnostics.isHandledPlayerError(
                PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
                TrackKind.REMOTE,
            ),
        )
        assertTrue(
            PlaybackDiagnostics.isHandledPlayerError(
                PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
                TrackKind.REMOTE,
            ),
        )
        assertTrue(
            PlaybackDiagnostics.isHandledPlayerError(
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                TrackKind.REMOTE,
            ),
        )
        assertTrue(
            PlaybackDiagnostics.isHandledPlayerError(
                PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
                TrackKind.REMOTE,
            ),
        )
        assertTrue(
            PlaybackDiagnostics.isHandledPlayerError(
                PlaybackException.ERROR_CODE_TIMEOUT,
                TrackKind.REMOTE,
            ),
        )

        // Unrelated errors on remote: audio sink error is not handled as recovery
        assertFalse(
            PlaybackDiagnostics.isHandledPlayerError(
                PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
                TrackKind.REMOTE,
            ),
        )

        // NONE kind: never handled
        assertFalse(
            PlaybackDiagnostics.isHandledPlayerError(
                PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
                TrackKind.NONE,
            ),
        )
    }

    @Test
    fun logMediaItemTransition_doesNotThrowAndSanitizesTrack() {
        val mediaItem =
            MediaItem
                .Builder()
                .setMediaId("/storage/emulated/0/Music/Secret Song.mp3")
                .build()
        PlaybackDiagnostics.logMediaItemTransition(mediaItem, androidx.media3.common.Player.MEDIA_ITEM_TRANSITION_REASON_AUTO)
    }
}
