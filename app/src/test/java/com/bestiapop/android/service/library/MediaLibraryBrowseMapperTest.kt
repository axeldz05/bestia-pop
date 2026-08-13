package com.bestiapop.android.service.library

import android.app.Application
import androidx.media3.common.MediaMetadata
import com.bestiapop.android.data.model.Album
import com.bestiapop.android.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class MediaLibraryBrowseMapperTest {

    @Test
    fun songIsPlayableLeafAndAlbumIsBrowsableContainer() {
        val song = MediaLibraryBrowseMapper.song(
            Song(
                id = 3L,
                uriString = "/music/song.mp3",
                title = "Song",
                artist = "Artist",
                album = "Album"
            )
        )
        val album = MediaLibraryBrowseMapper.album(
            Album(
                name = "stored",
                displayName = "Visible",
                artist = "Artist",
                songCount = 1
            )
        )

        assertEquals(MediaLibraryIds.song(3L), song.mediaId)
        assertFalse(song.mediaMetadata.isBrowsable ?: true)
        assertTrue(song.mediaMetadata.isPlayable ?: false)
        assertTrue(album.mediaMetadata.isBrowsable ?: false)
        assertFalse(album.mediaMetadata.isPlayable ?: true)
        assertEquals(MediaMetadata.MEDIA_TYPE_ALBUM, album.mediaMetadata.mediaType)
    }
}
