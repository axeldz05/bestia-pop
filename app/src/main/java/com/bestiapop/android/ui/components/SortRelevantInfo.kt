package com.bestiapop.android.ui.components

import com.bestiapop.android.data.model.Song
import com.bestiapop.android.ui.SortOption
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Extra subtitle fragment for album/artist aggregate rows when the sort field is not already shown.
 * Song rows use [sortEmphasisFor] instead.
 */
@Suppress("UNUSED_PARAMETER")
fun formatSortRelevantInfo(
    sortOption: SortOption,
    genre: String?,
    dateAdded: Long?,
    alreadyShowsArtist: Boolean = false,
    alreadyShowsAlbum: Boolean = false,
    alreadyShowsTitle: Boolean = false
): String? {
    return when (sortOption) {
        SortOption.TITLE, SortOption.ARTIST, SortOption.ALBUM -> null
        SortOption.GENRE -> genre?.takeIf {
            it.isNotBlank() && !it.equals(Song.UNKNOWN_GENRE, ignoreCase = true)
        }
        SortOption.DATE_ADDED -> dateAdded?.let { formatDateAdded(it) }
    }
}

fun formatDateAdded(epochMs: Long): String {
    val date = Date(epochMs)
    val now = Calendar.getInstance()
    val songCal = Calendar.getInstance().apply { time = date }
    val pattern = if (songCal.get(Calendar.YEAR) == now.get(Calendar.YEAR)) "dd MMM" else "dd MMM yyyy"
    val formatter = SimpleDateFormat(pattern, Locale.getDefault())
    return formatter.format(date)
}
