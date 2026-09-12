package com.bestiapop.android.ui.screens.discover

import com.bestiapop.android.data.model.CatalogAlbum
import com.bestiapop.android.data.model.OnlineCatalogTrack
import com.bestiapop.android.data.model.TrackIdentity
import com.bestiapop.android.data.model.TrackMeta
import com.bestiapop.android.data.model.toIdentity
import com.bestiapop.android.domain.usecase.RelatedAlbumItem
import com.bestiapop.android.domain.usecase.RelatedArtistItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DiscoverSwipeActionsTest {

    @Test
    fun discoverSwipeActions_andContext_supportSwipeTrackAlbumAndArtistCallbacks() {
        var swipedTrack: TrackMeta? = null
        var swipedAlbum: CatalogAlbum? = null
        var swipedArtist: String? = null

        val swipeActions = DiscoverSwipeActions(
            onSwipeTrack = { swipedTrack = it },
            onSwipeAlbum = { swipedAlbum = it },
            onSwipeArtist = { swipedArtist = it }
        )

        val context = DiscoverContext(
            swipeActions = swipeActions
        )

        val track = TrackIdentity(title = "Test Song", artist = "Test Artist", album = "Test Album")
        val album = CatalogAlbum(id = "123", title = "Test Album", artist = "Test Artist", coverUrl = null)

        context.swipeActions.onSwipeTrack?.invoke(track)
        context.swipeActions.onSwipeAlbum?.invoke(album)
        context.swipeActions.onSwipeArtist?.invoke("Related Artist")

        assertEquals("Test Song", swipedTrack?.title)
        assertEquals("Test Artist", swipedTrack?.artist)
        assertEquals("123", swipedAlbum?.id)
        assertEquals("Test Album", swipedAlbum?.title)
        assertEquals("Related Artist", swipedArtist)
    }

    @Test
    fun discoverContext_withoutSwipeActions_clearsAllSwipeActionsForCarousels() {
        val swipeActions = DiscoverSwipeActions(
            onSwipeTrack = {},
            onSwipeAlbum = {},
            onSwipeArtist = {}
        )
        val context = DiscoverContext(swipeActions = swipeActions)
        val carouselContext = context.withoutSwipeActions()

        assertNull(carouselContext.swipeActions.onSwipeTrack)
        assertNull(carouselContext.swipeActions.onSwipeAlbum)
        assertNull(carouselContext.swipeActions.onSwipeArtist)
    }

    @Test
    fun catalogActions_focusedOnCatalogInteractions() {
        var playedTrack = false
        val actions = DiscoverCatalogActions(
            onPlayTrack = { playedTrack = true },
            onDownloadTrack = {},
            onSelectAlbum = {},
            onSaveAlbum = {}
        )
        val track = OnlineCatalogTrack(
            identity = TrackIdentity(title = "Song", artist = "Artist", album = "Album"),
            id = "track-1"
        )
        actions.onPlayTrack(track)
        assertEquals(true, playedTrack)
    }

    @Test
    fun collectionActions_focusedOnCollectionInteractions() {
        var backPressed = false
        val actions = DiscoverCollectionActions(
            onBack = { backPressed = true },
            onPlayAll = {},
            onShuffle = {},
            onSaveAlbum = {},
            onDownloadAll = {},
            onPlayCandidate = {},
            onDownloadCandidate = {}
        )
        actions.onBack()
        assertEquals(true, backPressed)
    }

    @Test
    fun artistActions_focusedOnArtistInteractions() {
        var backPressed = false
        val actions = DiscoverArtistActions(
            onBack = { backPressed = true },
            onPlayAll = {},
            onShuffle = {},
            onStartRadio = {},
            onSelectAlbum = {},
            onSaveAlbum = {},
            onPlayTrack = {},
            onDownloadTrack = {},
            onPlayLocalSong = {}
        )
        actions.onBack()
        assertEquals(true, backPressed)
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
