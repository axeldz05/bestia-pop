package com.bestiapop.android.ui.state

import androidx.compose.runtime.Immutable
import com.bestiapop.android.data.model.Song

/**
 * Precomputed LazyColumn entries for the library song list.
 * Built in the domain/VM layer so the UI only renders a flat, keyed list.
 */
@Immutable
sealed interface LibraryListItem {
    val key: Any
    val contentType: String

    @Immutable
    data class AlbumHeader(
        val albumName: String,
        val artistName: String,
        val artworkUri: String?,
        val songCount: Int,
        val albumSongs: List<Song>
    ) : LibraryListItem {
        override val key: Any get() = "header_$albumName"
        override val contentType: String get() = CONTENT_TYPE_ALBUM_HEADER
    }

    @Immutable
    data class SongRow(
        val song: Song,
        /** Play index into the visual song order ([GetLibrarySongsUseCase.songsFromListItems]). */
        val index: Int
    ) : LibraryListItem {
        override val key: Any get() = song.id
        override val contentType: String get() = CONTENT_TYPE_SONG
    }

    companion object {
        const val CONTENT_TYPE_SONG = "song"
        const val CONTENT_TYPE_ALBUM_HEADER = "album_header"
    }
}
