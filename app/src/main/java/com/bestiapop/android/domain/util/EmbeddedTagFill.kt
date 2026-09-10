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

    val genericTitle = IdentifyRanking.isGenericIdentifyTitle(song.title)
    val spuriousArtistMatch = genericTitle && !wasPlaceholder && (
        (fileArtistOk && IdentifyRanking.fieldSimilarity(song.artist, meta.artist) < IdentifyRanking.MEDIUM_SCORE) ||
        (!fileArtistOk && !song.folderPath.contains(song.artist, ignoreCase = true) && !albumNamesMatch(song.album, meta.album))
    )
    val needsArtistReset = wasPlaceholder || spuriousArtistMatch
    val needsAlbumReset = albumGeneric || spuriousArtistMatch

    val knownSplit = if (needsArtistReset && !fileArtistOk) {
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
        needsArtistReset && fileArtistOk -> meta.artist
        needsArtistReset && splitArtist != null -> splitArtist
        spuriousArtistMatch -> Song.UNKNOWN_ARTIST
        else -> song.artist
    }
    val proposedAlbum = when {
        needsAlbumReset && fileAlbumOk -> meta.album
        spuriousArtistMatch -> Song.UNKNOWN_ALBUM
        else -> song.album
    }
    val resolvedAlbum = if (needsAlbumReset && fileAlbumOk) {
        pickPersistedAlbumName(
            library = library,
            proposedAlbum = proposedAlbum,
            proposedArtist = proposedArtist,
            sourceAlbum = song.album,
            isGeneric = IdentifyRanking::isGenericAlbum
        )
    } else {
        proposedAlbum
    }
    val bucketArtists = library.mapNotNull { sibling ->
        sibling.artist.takeIf { albumNamesMatch(sibling.album, resolvedAlbum) }
    } + proposedArtist
    val resolvedArtist = if (needsArtistReset && !IdentifyRanking.isPlaceholderArtist(proposedArtist)) {
        pickPersistedArtistName(bucketArtists, proposedArtist)
    } else {
        proposedArtist
    }
    val resolvedTitle = when {
        spuriousArtistMatch && meta.title.isNotBlank() && !IdentifyRanking.isGenericIdentifyTitle(meta.title) -> meta.title
        wasPlaceholder && fileArtistOk && meta.title.isNotBlank() ->
            IdentifyRanking.preferBilingualTitle(meta.title, song.title)
        wasPlaceholder && splitTitle != null -> splitTitle
        isWeakIdentityTitle(song.artist, song.title) && meta.title.isNotBlank() -> meta.title
        else -> song.title
    }
    val resolvedGenre = if (
        (needsArtistReset || needsAlbumReset) &&
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
        spuriousArtistMatch -> fileArt
        wasPlaceholder && isRemoteCatalogArtwork(song.artworkUri) && fileArt != null -> fileArt
        !SongPathNormalizer.hasUsableArtwork(song.artworkUri) && fileArt != null -> fileArt
        else -> song.artworkUri
    }
    val fileLyrics = meta.lyrics?.trim()?.takeIf { it.isNotEmpty() }
    val resolvedLyrics = when {
        needsArtistReset && fileLyrics != null -> fileLyrics
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
