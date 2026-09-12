package com.bestiapop.android.ui.screens.discover

import com.bestiapop.android.data.model.CatalogAlbum
import com.bestiapop.android.data.model.TrackIdentity
import com.bestiapop.android.data.model.TrackMeta
import com.bestiapop.android.data.model.toIdentity
import com.bestiapop.android.domain.usecase.RelatedAlbumItem
import com.bestiapop.android.domain.usecase.RelatedArtistItem
import org.junit.Assert.assertEquals
import org.junit.Test

class DiscoverSwipeActionsTest {

    @Test
    fun catalogActions_supportsSwipeTrackAndAlbumCallbacks() {
        var swipedTrack: TrackMeta? = null
        var swipedAlbum: CatalogAlbum? = null

        val actions = DiscoverCatalogActions(
            onPlayTrack = {},
            onDownloadTrack = {},
            onSelectAlbum = {},
            onSaveAlbum = {},
            onSwipeTrack = { swipedTrack = it },
            onSwipeAlbum = { swipedAlbum = it }
        )

        val track = TrackIdentity(title = "Test Song", artist = "Test Artist", album = "Test Album")
        val album = CatalogAlbum(id = "123", title = "Test Album", artist = "Test Artist", coverUrl = null)

        actions.onSwipeTrack?.invoke(track)
        actions.onSwipeAlbum?.invoke(album)

        assertEquals("Test Song", swipedTrack?.title)
        assertEquals("Test Artist", swipedTrack?.artist)
        assertEquals("123", swipedAlbum?.id)
        assertEquals("Test Album", swipedAlbum?.title)
    }

    @Test
    fun collectionActions_supportsSwipeTrackCallback() {
        var swipedTrack: TrackMeta? = null

        val actions = DiscoverCollectionActions(
            onBack = {},
            onPlayAll = {},
            onShuffle = {},
            onSaveAlbum = {},
            onDownloadAll = {},
            onPlayCandidate = {},
            onDownloadCandidate = {},
            onSwipeTrack = { swipedTrack = it }
        )

        val track = TrackIdentity(title = "Candidate Song", artist = "Candidate Artist")
        actions.onSwipeTrack?.invoke(track)

        assertEquals("Candidate Song", swipedTrack?.title)
        assertEquals("Candidate Artist", swipedTrack?.artist)
    }

    @Test
    fun artistActions_supportsSwipeTrackAndAlbumCallbacks() {
        var swipedTrack: TrackMeta? = null
        var swipedAlbum: CatalogAlbum? = null

        val actions = DiscoverArtistActions(
            onBack = {},
            onPlayAll = {},
            onShuffle = {},
            onStartRadio = {},
            onSelectAlbum = {},
            onSaveAlbum = {},
            onPlayTrack = {},
            onDownloadTrack = {},
            onPlayLocalSong = {},
            onSwipeTrack = { swipedTrack = it },
            onSwipeAlbum = { swipedAlbum = it }
        )

        val track = TrackIdentity(title = "Artist Top Track", artist = "Artist Name")
        val album = CatalogAlbum(id = "alb-1", title = "Artist Album", artist = "Artist Name", coverUrl = null)

        actions.onSwipeTrack?.invoke(track)
        actions.onSwipeAlbum?.invoke(album)

        assertEquals("Artist Top Track", swipedTrack?.title)
        assertEquals("alb-1", swipedAlbum?.id)
    }

    @Test
    fun topRelatedActions_supportsSwipeTrackAlbumAndArtistCallbacks() {
        var swipedTrack: TrackMeta? = null
        var swipedAlbum: RelatedAlbumItem? = null
        var swipedArtist: RelatedArtistItem? = null

        val actions = DiscoverTopRelatedActions(
            onSwipeTrack = { swipedTrack = it },
            onSwipeAlbum = { swipedAlbum = it },
            onSwipeArtist = { swipedArtist = it }
        )

        val track = TrackIdentity(title = "Related Track", artist = "Related Artist")
        val album = RelatedAlbumItem(title = "Related Album", artist = "Related Artist", artworkUri = null, source = "Local")
        val artist = RelatedArtistItem(name = "Related Artist", artworkUri = null, source = "ListenBrainz")

        actions.onSwipeTrack?.invoke(track)
        actions.onSwipeAlbum?.invoke(album)
        actions.onSwipeArtist?.invoke(artist)

        assertEquals("Related Track", swipedTrack?.title)
        assertEquals("Related Album", swipedAlbum?.title)
        assertEquals("Related Artist", swipedArtist?.name)
    }

    @Test
    fun trackMeta_toIdentity_preservesAllFields() {
        val meta: TrackMeta = object : TrackMeta {
            override val title: String = "My Title"
            override val artist: String = "My Artist"
            override val album: String = "My Album"
            override val artworkUri: String = "content://art/1"
            override val durationMs: Long = 180000L
            override val trackNumber: Int = 3
        }

        val identity = meta.toIdentity()

        assertEquals("My Title", identity.title)
        assertEquals("My Artist", identity.artist)
        assertEquals("My Album", identity.album)
        assertEquals("content://art/1", identity.artworkUri)
        assertEquals(180000L, identity.durationMs)
        assertEquals(3, identity.trackNumber)
    }
}
