package com.bestiapop.android.ui.components

import com.bestiapop.android.data.model.Song
import com.bestiapop.android.ui.screens.library.LibrarySongListActions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
}

