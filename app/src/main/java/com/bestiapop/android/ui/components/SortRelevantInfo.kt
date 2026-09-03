package com.bestiapop.android.ui.components

import com.bestiapop.android.data.model.Song
import com.bestiapop.android.ui.SortOption
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
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

private object DateAddedFormatters {
    @Volatile
    private var lastLocale: Locale? = null
    @Volatile
    private var lastYear: Int = 0
    @Volatile
    private var lastYearCheck = 0L
    @Volatile
    private var sameYearFormatter: DateTimeFormatter? = null
    @Volatile
    private var differentYearFormatter: DateTimeFormatter? = null
    @Volatile
    private var defaultZone: ZoneId = ZoneId.systemDefault()

    fun format(epochMs: Long): String {
        val now = System.currentTimeMillis()
        val currentLocale = Locale.getDefault()
        if (currentLocale != lastLocale || sameYearFormatter == null) {
            lastLocale = currentLocale
            defaultZone = ZoneId.systemDefault()
            sameYearFormatter = DateTimeFormatter.ofPattern("dd MMM", currentLocale)
            differentYearFormatter = DateTimeFormatter.ofPattern("dd MMM yyyy", currentLocale)
        }
        if (now - lastYearCheck > 60_000L) {
            lastYear = LocalDate.now(defaultZone).year
            lastYearCheck = now
        }
        val date = Instant.ofEpochMilli(epochMs).atZone(defaultZone)
        val formatter = if (date.year == lastYear) sameYearFormatter!! else differentYearFormatter!!
        return formatter.format(date)
    }
}

fun formatDateAdded(epochMs: Long): String = DateAddedFormatters.format(epochMs)
