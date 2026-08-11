package com.bestiapop.android.ui.components

import com.bestiapop.android.data.model.Song
import com.bestiapop.android.ui.SortOption

/**
 * Row copy for library lists: dominant line = active [SortOption], optional trailing sort key.
 * Absorbs the old [formatSortRelevantInfo] subtitle fragment for songs.
 */
data class SortEmphasizedTexts(
    val title: String,
    val subtitle: String,
    val trailing: String?,
    val trailingIsSortKey: Boolean
)

fun sortEmphasisFor(song: Song, sortOption: SortOption): SortEmphasizedTexts {
    val duration = formatDuration(song.durationMs)
    val genreLabel = song.genre.takeIf {
        it.isNotBlank() && !it.equals(Song.UNKNOWN_GENRE, ignoreCase = true)
    }
    return when (sortOption) {
        SortOption.TITLE -> SortEmphasizedTexts(
            title = song.title,
            subtitle = joinMeta(song.artist, song.album),
            trailing = duration,
            trailingIsSortKey = false
        )
        SortOption.ARTIST -> SortEmphasizedTexts(
            title = song.artist,
            subtitle = joinMeta(song.title, song.album),
            trailing = duration,
            trailingIsSortKey = false
        )
        SortOption.ALBUM -> SortEmphasizedTexts(
            title = song.album,
            subtitle = joinMeta(song.title, song.artist),
            trailing = if (song.trackNumber > 0) song.trackNumber.toString() else duration,
            trailingIsSortKey = false
        )
        SortOption.GENRE -> SortEmphasizedTexts(
            title = song.title,
            subtitle = joinMeta(song.artist, song.album),
            trailing = genreLabel ?: duration,
            trailingIsSortKey = genreLabel != null
        )
        SortOption.DATE_ADDED -> SortEmphasizedTexts(
            title = song.title,
            subtitle = joinMeta(song.artist, song.album),
            trailing = formatDateAdded(song.dateAdded),
            trailingIsSortKey = true
        )
    }
}

fun sortEmphasisForLastPlayed(song: Song): SortEmphasizedTexts = SortEmphasizedTexts(
    title = song.title,
    subtitle = joinMeta(song.artist, song.album),
    trailing = if (song.lastPlayedAt > 0) formatDateAdded(song.lastPlayedAt) else null,
    trailingIsSortKey = song.lastPlayedAt > 0
)
