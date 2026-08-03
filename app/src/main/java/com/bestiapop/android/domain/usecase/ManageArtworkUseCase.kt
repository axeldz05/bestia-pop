package com.bestiapop.android.domain.usecase

import com.bestiapop.android.domain.repository.IMusicRepository

class ManageArtworkUseCase(private val repository: IMusicRepository) {

    suspend fun updateAlbumArtwork(albumName: String, artworkUri: String) {
        if (albumName.isBlank() || artworkUri.isBlank()) return
        val savedUri = repository.savePlaylistCoverImage(artworkUri) ?: artworkUri
        
        // Fetch all songs to find songs matching this album and update them
        val songs = repository.getAllSongsSync()
        val albumSongs = songs.filter { it.album.equals(albumName, ignoreCase = true) }
        
        albumSongs.forEach { song ->
            repository.updateSongMetadata(
                songId = song.id,
                title = song.title,
                artist = song.artist,
                album = song.album,
                genre = song.genre
            )
            repository.enhanceSongMetadataAndLyrics(song.copy(artworkUri = savedUri))
        }
    }
}
