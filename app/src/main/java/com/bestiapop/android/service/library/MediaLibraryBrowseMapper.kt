package com.bestiapop.android.service.library

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.bestiapop.android.data.model.Album
import com.bestiapop.android.data.model.Artist
import com.bestiapop.android.data.model.Playlist
import com.bestiapop.android.data.model.Song
import com.bestiapop.android.service.mediaMetadataBuilder

internal object MediaLibraryBrowseMapper {
    fun root(): MediaItem = browsable(
        mediaId = MediaLibraryIds.ROOT,
        title = "Bestia Pop",
        mediaType = MediaMetadata.MEDIA_TYPE_FOLDER_MIXED
    )

    fun categories(): List<MediaItem> = listOf(
        browsable(
            MediaLibraryIds.SONGS,
            "Canciones",
            MediaMetadata.MEDIA_TYPE_FOLDER_MIXED
        ),
        browsable(
            MediaLibraryIds.ALBUMS,
            "Álbumes",
            MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS
        ),
        browsable(
            MediaLibraryIds.ARTISTS,
            "Artistas",
            MediaMetadata.MEDIA_TYPE_FOLDER_ARTISTS
        ),
        browsable(
            MediaLibraryIds.PLAYLISTS,
            "Playlists",
            MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS
        )
    )

    fun song(song: Song): MediaItem {
        val metadata = song.mediaMetadataBuilder()
            .setIsBrowsable(false)
            .setIsPlayable(true)
            .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
            .apply {
                if (song.durationMs > 0L) setDurationMs(song.durationMs)
                if (song.trackNumber > 0) setTrackNumber(song.trackNumber)
            }
            .build()
        return MediaItem.Builder()
            .setMediaId(MediaLibraryIds.song(song.id))
            .setMediaMetadata(metadata)
            .build()
    }

    fun album(album: Album): MediaItem = browsable(
        mediaId = MediaLibraryIds.album(album.name),
        title = album.displayName,
        artworkUri = album.artworkUri,
        artist = album.artist,
        mediaType = MediaMetadata.MEDIA_TYPE_ALBUM
    )

    fun artist(artist: Artist): MediaItem = browsable(
        mediaId = MediaLibraryIds.artist(artist.name),
        title = artist.name,
        artworkUri = artist.photoUri,
        mediaType = MediaMetadata.MEDIA_TYPE_ARTIST
    )

    fun playlist(playlist: Playlist): MediaItem = browsable(
        mediaId = MediaLibraryIds.playlist(playlist.id),
        title = playlist.name,
        artworkUri = playlist.coverUri,
        description = playlist.description,
        mediaType = MediaMetadata.MEDIA_TYPE_PLAYLIST
    )

    private fun browsable(
        mediaId: String,
        title: String,
        mediaType: Int = MediaMetadata.MEDIA_TYPE_FOLDER_MIXED,
        artworkUri: String? = null,
        artist: String? = null,
        description: String? = null
    ): MediaItem {
        val metadata = MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(artist)
            .setDescription(description)
            .setArtworkUri(uri(artworkUri))
            .setIsBrowsable(true)
            .setIsPlayable(false)
            .setMediaType(mediaType)
            .build()
        return MediaItem.Builder()
            .setMediaId(mediaId)
            .setMediaMetadata(metadata)
            .build()
    }

    private fun uri(value: String?): Uri? =
        value?.takeIf(String::isNotBlank)?.let(Uri::parse)
}
