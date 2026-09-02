package com.bestiapop.android.domain.util

import com.bestiapop.android.data.model.Song
import com.bestiapop.android.data.util.AudioFileMetadata
import com.bestiapop.android.data.util.SongPathNormalizer
import com.bestiapop.android.data.util.looksLikeStoragePath

/** Genre written when both tag readers fail, or a generic MediaStore default. */
fun isPlaceholderGenre(genre: String): Boolean {
    val g = genre.trim()
    return g.isEmpty() ||
        g.equals(AudioFileMetadata.FALLBACK_GENRE, ignoreCase = true) ||
        g.equals(Song.UNKNOWN_GENRE, ignoreCase = true)
}

fun isRemoteCatalogArtwork(artworkUri: String?): Boolean {
    val raw = artworkUri?.trim().orEmpty()
    return raw.startsWith("http://", ignoreCase = true) ||
        raw.startsWith("https://", ignoreCase = true)
}

/**
 * Fill Unknown/generic Room identity from embedded file tags without overwriting
 * a song that already has a real artist/album.
 */
fun fillSongGapsFromFileTags(
    song: Song,
    meta: AudioFileMetadata,
    library: List<Song>
): Song {
    val wasPlaceholder = IdentifyRanking.isPlaceholderArtist(song.artist)
    val albumGeneric = IdentifyRanking.isGenericAlbum(song.album)
    val fileArtistOk = !IdentifyRanking.isPlaceholderArtist(meta.artist)
    val fileAlbumOk = !IdentifyRanking.isGenericAlbum(meta.album)
    val knownSplit = if (wasPlaceholder && !fileArtistOk) {
        val phrases = listOf(meta.title, song.title)
            .map { it.trim() }
            .filter { it.isNotEmpty() && !looksLikeStoragePath(it) }
            .distinct()
        val artists = library.map { it.artist }
        phrases.firstNotNullOfOrNull { splitUsingKnownArtists(it, artists) }
    } else {
        null
    }
    val splitArtist = knownSplit?.artist?.takeIf { it.isNotBlank() }
    val splitTitle = knownSplit?.title?.takeIf { it.isNotBlank() }

    val proposedArtist = when {
        wasPlaceholder && fileArtistOk -> meta.artist
        wasPlaceholder && splitArtist != null -> splitArtist
        else -> song.artist
    }
    val proposedAlbum = if (albumGeneric && fileAlbumOk) meta.album else song.album
    val resolvedAlbum = if (albumGeneric && fileAlbumOk) {
        pickPersistedAlbumName(
            library = library,
            proposedAlbum = proposedAlbum,
            proposedArtist = proposedArtist,
            sourceAlbum = song.album,
            isGeneric = IdentifyRanking::isGenericAlbum
        )
    } else {
        song.album
    }
    val bucketArtists = library.mapNotNull { sibling ->
        sibling.artist.takeIf { albumNamesMatch(sibling.album, resolvedAlbum) }
    } + song.artist
    val resolvedArtist = if (wasPlaceholder && !IdentifyRanking.isPlaceholderArtist(proposedArtist)) {
        pickPersistedArtistName(bucketArtists, proposedArtist)
    } else {
        song.artist
    }
    val resolvedTitle = when {
        wasPlaceholder && fileArtistOk && meta.title.isNotBlank() ->
            IdentifyRanking.preferBilingualTitle(meta.title, song.title)
        wasPlaceholder && splitTitle != null -> splitTitle
        isWeakIdentityTitle(song.artist, song.title) && meta.title.isNotBlank() -> meta.title
        else -> song.title
    }
    val resolvedGenre = if (
        (wasPlaceholder || albumGeneric) &&
        isPlaceholderGenre(song.genre) &&
        !isPlaceholderGenre(meta.genre)
    ) {
        meta.genre
    } else {
        song.genre
    }
    val resolvedYear = if (song.year <= 0 && meta.year > 0) meta.year else song.year
    val resolvedTrack = if (song.trackNumber <= 0 && meta.trackNumber > 0) {
        meta.trackNumber
    } else {
        song.trackNumber
    }
    val fileArt = meta.artworkUri.takeIf { SongPathNormalizer.hasUsableArtwork(it) }
    val resolvedArtwork = when {
        wasPlaceholder && isRemoteCatalogArtwork(song.artworkUri) && fileArt != null -> fileArt
        !SongPathNormalizer.hasUsableArtwork(song.artworkUri) && fileArt != null -> fileArt
        else -> song.artworkUri
    }
    val fileLyrics = meta.lyrics?.trim()?.takeIf { it.isNotEmpty() }
    val resolvedLyrics = when {
        wasPlaceholder && fileLyrics != null -> fileLyrics
        song.lyrics.isNullOrBlank() && fileLyrics != null -> fileLyrics
        else -> song.lyrics
    }
    return song.copy(
        title = resolvedTitle,
        artist = resolvedArtist,
        album = resolvedAlbum,
        genre = resolvedGenre,
        year = resolvedYear,
        trackNumber = resolvedTrack,
        artworkUri = resolvedArtwork,
        lyrics = resolvedLyrics
    )
}
