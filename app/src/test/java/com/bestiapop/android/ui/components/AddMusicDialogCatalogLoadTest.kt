package com.bestiapop.android.ui.components

import com.bestiapop.android.data.model.OnlineCatalogTrack
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AddMusicDialogCatalogLoadTest {

    @Test
    fun initialCatalogLoadsOnlyWhenNoCatalogSectionHasData() {
        assertTrue(
            shouldLoadInitialCatalog(
                songResults = emptyList(),
                albumResults = emptyList(),
                playlistResults = emptyList(),
                genreResults = emptyList()
            )
        )

        assertFalse(
            shouldLoadInitialCatalog(
                songResults = listOf(
                    OnlineCatalogTrack(
                        id = "track",
                        title = "Track",
                        artist = "Artist",
                        album = "Album",
                        artworkUri = null,
                        durationMs = 1L,
                        audioUrl = "track",
                        provider = "Catalog"
                    )
                ),
                albumResults = emptyList(),
                playlistResults = emptyList(),
                genreResults = emptyList()
            )
        )
    }
}
