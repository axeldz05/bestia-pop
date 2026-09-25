package com.bestiapop.android.service

import android.app.Application
import android.net.Uri
import com.bestiapop.android.data.model.PlayableItem
import com.bestiapop.android.data.model.ResolvedStream
import com.bestiapop.android.data.model.Song
import com.bestiapop.android.data.model.TrackIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class PlaybackMediaItemCodecTest {
    @Test
    fun remote_roundTrip_keepsPortableFieldsAndCdnOnlyInUri() {
        val cdn = "https://rr1---sn.example.googlevideo.com/videoplayback?expire=123"
        val original =
            PlayableItem.Remote(
                identity =
                    TrackIdentity(
                        title = "Remote title",
                        artist = "Remote artist",
                        album = "Remote album",
                        artworkUri = "https://images.example/cover.jpg",
                        durationMs = 123_000L,
                        trackNumber = 7,
                    ),
                recordingMbid = "recording-mbid",
                youtubeQueryOrId = "video-or-query",
                resolved =
                    ResolvedStream(
                        audioUrl = cdn,
                        userAgent = "extractor-UA",
                        videoId = "abcdefghijk",
                        resolvedAtEpochMs = 99_000L,
                    ),
                queueEntryId = "remote-slot",
            )

        val payload = PlaybackMediaItemCodec.portablePayload(original)
        val decoded =
            PlaybackMediaItemCodec.restore(payload, mediaUri = cdn)
                as PlayableItem.Remote

        assertFalse(payload.toString().contains(cdn))
        assertEquals(original.queueEntryId, decoded.queueEntryId)
        assertEquals(original.identity, decoded.identity)
        assertEquals(original.recordingMbid, decoded.recordingMbid)
        assertEquals(original.youtubeQueryOrId, decoded.youtubeQueryOrId)
        assertEquals(original.resolved, decoded.resolved)

        val portableValues =
            listOf(
                payload.queueEntryId,
                payload.queryOrId,
                payload.recordingMbid,
                payload.videoId,
                payload.userAgent,
            )
        assertTrue(portableValues.any { it == "remote-slot" })
        assertTrue(portableValues.any { it == "video-or-query" })
        assertTrue(portableValues.any { it == "recording-mbid" })
        assertTrue(portableValues.any { it == "abcdefghijk" })
        assertTrue(portableValues.any { it == "extractor-UA" })
    }

    @Test
    fun local_roundTrip_keepsQueueOccurrenceAndIdentity() {
        val song =
            Song(
                id = 42L,
                uriString = "/music/local.flac",
                title = "Local title",
                artist = "Local artist",
                album = "Local album",
                durationMs = 45_000L,
                trackNumber = 2,
            )
        val original = PlayableItem.Local(song, queueEntryId = "local-slot")

        val payload = PlaybackMediaItemCodec.portablePayload(original)
        val decoded =
            PlaybackMediaItemCodec.restore(payload, library = listOf(song))
                as PlayableItem.Local

        assertEquals("local-slot", decoded.queueEntryId)
        assertEquals(song, decoded.song)
        assertEquals(PlaybackMediaItemCodec.VERSION, payload.version)
    }

    @Test
    fun encode_whenLocalSongHasRemoteUri_encodesAsRemoteWithoutRemoteContentUri() {
        val remoteSong =
            Song(
                id = 99L,
                uriString = "remote://yt/video123",
                title = "Remote Title",
                artist = "Remote Artist",
                album = "Remote Album",
            )
        val localItemWithRemoteSong = PlayableItem.Local(remoteSong, queueEntryId = "test-remote-slot")
        val encoded = PlaybackMediaItemCodec.encode(localItemWithRemoteSong) { Uri.parse(it.uriString) }

        assertTrue(encoded.mediaId.startsWith("remote:"))
        assertEquals(Uri.EMPTY, encoded.localConfiguration?.uri)

        val decoded = PlaybackMediaItemCodec.decode(encoded)
        assertTrue(decoded is PlayableItem.Remote)
        assertEquals("test-remote-slot", decoded?.queueEntryId)
        assertEquals("video123", (decoded as PlayableItem.Remote).youtubeQueryOrId)
    }

    @Test
    fun restore_whenPayloadKindLocalHasRemoteUri_restoresAsRemote() {
        val payload =
            PlaybackMediaItemPortablePayload(
                version = PlaybackMediaItemCodec.VERSION,
                kind = "local",
                queueEntryId = "legacy-slot",
                identity = TrackIdentity(title = "Legacy Remote", artist = "Artist"),
                localUri = "remote://yt/legacyVideoId",
            )
        val restored = PlaybackMediaItemCodec.restore(payload)
        assertTrue(restored is PlayableItem.Remote)
        assertEquals("legacyVideoId", (restored as PlayableItem.Remote).youtubeQueryOrId)
        assertEquals("legacy-slot", restored.queueEntryId)
    }
}
