package com.bestiapop.android.ui.components

import com.bestiapop.android.ui.SortOption
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Extra subtitle fragment for the active sort when that field is not already shown
 * in the row (title / artist / album).
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
        SortOption.GENRE -> genre?.takeIf { it.isNotBlank() && !it.equals("Unknown Genre", ignoreCase = true) }
        SortOption.DATE_ADDED -> dateAdded?.let { formatDateAdded(it) }
    }
}

fun formatDateAdded(epochMs: Long): String {
    val formatter = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())
    return formatter.format(Date(epochMs))
}
