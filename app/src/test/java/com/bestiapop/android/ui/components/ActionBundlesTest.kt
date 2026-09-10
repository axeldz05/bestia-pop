package com.bestiapop.android.ui.components

import com.bestiapop.android.data.model.Song
import com.bestiapop.android.ui.screens.library.AggregateBrowseActions
import com.bestiapop.android.ui.screens.library.AlbumBrowseActions
import com.bestiapop.android.ui.screens.library.LibraryAlbumGroupActions
import com.bestiapop.android.ui.screens.library.LibrarySongListActions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionBundlesTest {

    private fun dummySong(id: Long = 1L) = Song(
        id = id,
        uriString = "file:///dummy$id.mp3",
        title = "Title $id",
        artist = "Artist",
        album = "Album",
        durationMs = 120_000L
    )

    @Test
    fun albumHeaderActions_defaultsAreFunctional() {
        var played = false
        var shuffled = false
        val actions = AlbumHeaderActions(
            onPlay = { played = true },
            onShuffle = { shuffled = true }
        )
        actions.onPlay()
        actions.onShuffle()
        assertTrue(played)
        assertTrue(shuffled)
        assertEquals(null, actions.onIdentify)
    }

    @Test
    fun multiSelectActions_mapsCallbacksCorrectly() {
        var played = false
        var cleared = false
        val actions = MultiSelectActions(
            onPlaySelected = { played = true },
            onEnqueueSelected = {},
            onAddToPlaylist = {},
            onIdentifySelected = {},
            onSimilarSelected = {},
            onDeleteSelected = {},
            onSelectAll = {},
            onClearSelection = { cleared = true }
        )
        actions.onPlaySelected()
        actions.onClearSelection()
        assertTrue(played)
        assertTrue(cleared)
    }

    @Test
    fun librarySongListActions_secondaryConstructorComposesCorrectly() {
        var toggledSong: Song? = null
        var playedAlbum: String? = null
        var nextSong: Song? = null

        val testSong = dummySong(42L)
        val actions = LibrarySongListActions(
            onPlayNext = { song: Song -> nextSong = song },
            onAddToQueue = {},
            onStartRadio = {},
            onAddToPlaylist = {},
            onEditMetadata = {},
            onEditLyrics = {},
            onIdentify = {},
            onDeleteSong = {},
            onPlayAlbum = { name: String, _: List<Long> -> playedAlbum = name },
            onShuffleAlbum = { _: String, _: List<Long> -> },
            onToggleSelect = { song: Song -> toggledSong = song },
            onToggleSelectAlbum = {},
            onAlbumLongClick = {},
            onToggleCollapseAlbum = {},
            onEditAlbum = {},
            onChangeAlbumCover = {},
            onIdentifyAlbum = {},
            onOpenAlbum = {}
        )

        actions.onToggleSelect(testSong)
        actions.onPlayNext(testSong)
        actions.onPlayAlbum("BestiaAlbum", listOf(42L))

        assertEquals(testSong, toggledSong)
        assertEquals(testSong, nextSong)
        assertEquals("BestiaAlbum", playedAlbum)
        assertNotNull(actions.songActions)
        assertNotNull(actions.albumActions)
    }

    @Test
    fun aggregateBrowseActions_invokesProperItem() {
        var clickedItem = ""
        var playedItem = ""
        var shuffledItem = ""

        val actions = AggregateBrowseActions<String>(
            onClick = { clickedItem = it },
            onPlay = { playedItem = it },
            onShuffle = { shuffledItem = it }
        )

        actions.onClick("Rock")
        actions.onPlay("Rock")
        actions.onShuffle("Rock")

        assertEquals("Rock", clickedItem)
        assertEquals("Rock", playedItem)
        assertEquals("Rock", shuffledItem)
    }
}
