package com.bestiapop.android.ui.components

import com.bestiapop.android.data.model.Album
import com.bestiapop.android.data.model.Artist
import com.bestiapop.android.data.model.GenreGroup
import com.bestiapop.android.data.model.Song
import com.bestiapop.android.domain.util.NaturalTextOrder
import com.bestiapop.android.ui.SortOption
import com.bestiapop.android.ui.state.LibraryListItem
import com.bestiapop.android.ui.state.LibraryListModel
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * Level 3: Pure utility functions for calculating fast-scroll sections.
 */
object FastScrollSections {

    const val MIN_ITEMS_THRESHOLD = 12
    private val defaultZone: ZoneId by lazy { ZoneId.systemDefault() }

    /**
     * Level 3 utility: Resolves the closest section index for a given vertical touch coordinate [touchY],
     * utilizing exact measured slot centers [itemCenters] or mathematical bounds fallback.
     */
    fun findClosestSectionIndex(
        touchY: Float,
        itemCenters: FloatArray,
        sectionsCount: Int,
        railHeight: Float = 0f,
        verticalPaddingPx: Float = 0f
    ): Int {
        if (sectionsCount <= 0) return 0
        if (sectionsCount == 1) return 0

        var hasValidCenters = false
        for (i in 0 until sectionsCount) {
            if (i < itemCenters.size && itemCenters[i] > 0f) {
                hasValidCenters = true
                break
            }
        }

        if (hasValidCenters) {
            var closestIndex = 0
            var minDiff = Float.MAX_VALUE
            for (i in 0 until sectionsCount) {
                if (i >= itemCenters.size) break
                val center = itemCenters[i]
                if (center <= 0f) continue
                val diff = kotlin.math.abs(center - touchY)
                if (diff < minDiff) {
                    minDiff = diff
                    closestIndex = i
                }
            }
            return closestIndex
        }

        val usableHeight = (railHeight - 2 * verticalPaddingPx).coerceAtLeast(1f)
        val contentY = (touchY - verticalPaddingPx).coerceIn(0f, usableHeight - 0.001f)
        val fraction = contentY / usableHeight
        return (fraction * sectionsCount).toInt().coerceIn(0, sectionsCount - 1)
    }

    /**
     * Resolves the label and popup label using natural text ordering:
     * - A..Z for letters
     * - "0-9" for numbers
     * - "*" for symbols
     */
    fun textSection(input: String?): Pair<String, String> {
        val desc = NaturalTextOrder.sectionDescriptor(input)
        return desc.label to desc.popupLabel
    }

    /**
     * Normalizes a string's leading character into an alphabet letter (A-Z), "0-9", or "*".
     */
    fun normalizeSectionChar(input: String?): String =
        NaturalTextOrder.sectionDescriptor(input).label

    private val SPANISH_MONTHS_SHORT = arrayOf(
        "", "Ene", "Feb", "Mar", "Abr", "May", "Jun", "Jul", "Ago", "Sep", "Oct", "Nov", "Dic"
    )
    private val SPANISH_MONTHS_FULL = arrayOf(
        "", "Enero", "Febrero", "Marzo", "Abril", "Mayo", "Junio", "Julio", "Agosto", "Septiembre", "Octubre", "Noviembre", "Diciembre"
    )

    private enum class DateGranularity {
        YEAR,
        MONTH,
        DAY,
        HOUR
    }

    private data class ParsedItemDate(
        val index: Int,
        val epochMs: Long,
        val year: Int,
        val month: Int,
        val day: Int,
        val hour: Int,
        val previewText: String?
    )

    /**
     * Extracts date sections grouped by month/year.
     */
    fun dateSection(epochMs: Long): Pair<String, String> {
        if (epochMs <= 0L) return "#" to "Sin fecha"
        val date = Instant.ofEpochMilli(epochMs).atZone(defaultZone)
        val monthIdx = date.monthValue.coerceIn(1, 12)
        val monthShort = SPANISH_MONTHS_SHORT.getOrElse(monthIdx) { "Mes" }
        val monthFull = SPANISH_MONTHS_FULL.getOrElse(monthIdx) { "Mes" }
        return monthShort to "$monthFull ${date.year}"
    }

    /**
     * Context-aware date section builder that automatically adapts its granularity
     * (Year -> Month -> Day -> Hour) according to the distribution of dates in the list,
     * ensuring meaningful and distinct sections appear even when all songs were added
     * in the same year or month.
     */
    fun buildDateSections(
        totalItems: Int,
        getDateAdded: (index: Int) -> Long,
        getPreviewText: (index: Int) -> String?,
        isValidItem: (index: Int) -> Boolean = { true }
    ): List<FastScrollSection> {
        if (totalItems < MIN_ITEMS_THRESHOLD) return emptyList()

        val validItems = ArrayList<ParsedItemDate>(totalItems)
        val allFilteredItems = ArrayList<Pair<Int, Long>>(totalItems)

        for (i in 0 until totalItems) {
            if (!isValidItem(i)) continue
            val epoch = getDateAdded(i)
            allFilteredItems += (i to epoch)
            if (epoch > 0L) {
                val zdt = Instant.ofEpochMilli(epoch).atZone(defaultZone)
                validItems += ParsedItemDate(
                    index = i,
                    epochMs = epoch,
                    year = zdt.year,
                    month = zdt.monthValue,
                    day = zdt.dayOfMonth,
                    hour = zdt.hour,
                    previewText = getPreviewText(i)
                )
            }
        }

        if (allFilteredItems.size < MIN_ITEMS_THRESHOLD) return emptyList()
        if (validItems.isEmpty()) return emptyList()

        val distinctYears = validItems.map { it.year }.distinct().size
        val distinctMonths = validItems.map { it.year to it.month }.distinct().size
        val distinctDays = validItems.map { Triple(it.year, it.month, it.day) }.distinct().size
        val distinctHours = validItems.map { "${it.year}-${it.month}-${it.day}-${it.hour}" }.distinct().size

        val granularity = when {
            distinctMonths > 24 -> DateGranularity.YEAR
            distinctMonths > 1 -> DateGranularity.MONTH
            distinctDays > 1 -> DateGranularity.DAY
            distinctHours > 1 -> DateGranularity.HOUR
            else -> DateGranularity.MONTH
        }

        val today = LocalDate.now(defaultZone)
        val sections = ArrayList<FastScrollSection>()
        var lastKey: String? = null

        for (item in allFilteredItems) {
            val (idx, epoch) = item
            val (label, popupLabel, sectionKey) = if (epoch <= 0L) {
                Triple("#", "Sin fecha", "no-date")
            } else {
                val zdt = Instant.ofEpochMilli(epoch).atZone(defaultZone)
                val year = zdt.year
                val month = zdt.monthValue
                val day = zdt.dayOfMonth
                val hour = zdt.hour
                val monthShort = SPANISH_MONTHS_SHORT.getOrElse(month) { "Mes" }
                val monthFull = SPANISH_MONTHS_FULL.getOrElse(month) { "Mes" }

                when (granularity) {
                    DateGranularity.YEAR -> {
                        val shortYear = if (year >= 2000) "'${year.toString().takeLast(2)}" else year.toString()
                        Triple(shortYear, year.toString(), year.toString())
                    }
                    DateGranularity.MONTH -> {
                        Triple(monthShort, "$monthFull $year", "$year-$month")
                    }
                    DateGranularity.DAY -> {
                        val itemDate = zdt.toLocalDate()
                        val dayDiff = ChronoUnit.DAYS.between(itemDate, today)
                        val (l, p) = when {
                            dayDiff == 0L -> "Hoy" to "Hoy ($day $monthShort)"
                            dayDiff == 1L -> "Ayer" to "Ayer ($day $monthShort)"
                            else -> "$day" to "$day de $monthFull"
                        }
                        Triple(l, p, "$year-$month-$day")
                    }
                    DateGranularity.HOUR -> {
                        val hourStr = String.format(Locale.ROOT, "%02dh", hour)
                        val timeStr = String.format(Locale.ROOT, "%02d:00", hour)
                        Triple(hourStr, "$timeStr ($day $monthShort)", "$year-$month-$day-$hour")
                    }
                }
            }

            if (sectionKey != lastKey) {
                lastKey = sectionKey
                sections += FastScrollSection(
                    label = label,
                    popupLabel = popupLabel,
                    itemIndex = idx,
                    previewText = getPreviewText(idx)
                )
            }
        }

        return if (sections.size <= 1) emptyList() else sections
    }

    /**
     * Extracts relative time buckets for recently played songs.
     */
    fun recentSection(epochMs: Long, now: Long = System.currentTimeMillis()): Pair<String, String> {
        if (epochMs <= 0L) return "#" to "Sin fecha"
        val diffMs = (now - epochMs).coerceAtLeast(0)
        val diffDays = diffMs / (24 * 60 * 60 * 1000L)
        return when {
            diffDays == 0L -> "H" to "Hoy"
            diffDays == 1L -> "A" to "Ayer"
            diffDays < 7L -> "Sem" to "Esta semana"
            diffDays < 30L -> "Mes" to "Este mes"
            else -> {
                val date = Instant.ofEpochMilli(epochMs).atZone(defaultZone)
                val year = date.year.toString()
                val shortYear = if (year.length >= 2) "'${year.takeLast(2)}" else year
                shortYear to year
            }
        }
    }

    /**
     * Extracts genre sections with compact rail tag and full popup name.
     */
    fun genreSection(genre: String?): Pair<String, String> {
        val clean = genre?.trim()?.takeIf {
            it.isNotBlank() && !it.equals(Song.UNKNOWN_GENRE, ignoreCase = true)
        } ?: return "?" to "Desconocido"

        val shortLabel = if (clean.length <= 3) clean else clean.take(2)
        return shortLabel to clean
    }

    /**
     * Level 1 primitive: Iterates over [totalItems] and builds deduplicated [FastScrollSection]s
     * using the section descriptor returned by [resolveSection]. If consecutive items share the
     * same [FastScrollSection.label], they are collapsed under the first occurrence's index.
     */
    inline fun buildDeduplicatedSections(
        totalItems: Int,
        resolveSection: (index: Int) -> FastScrollSection?
    ): List<FastScrollSection> {
        if (totalItems < MIN_ITEMS_THRESHOLD) return emptyList()
        val sections = ArrayList<FastScrollSection>()
        var lastKey: String? = null

        for (i in 0 until totalItems) {
            val section = resolveSection(i) ?: continue
            if (section.label != lastKey) {
                lastKey = section.label
                sections += section
            }
        }

        return if (sections.size <= 1) emptyList() else sections
    }

    /**
     * Builds sections for [LibraryListModel] (songs, album groups, and recent lists).
     */
    fun fromLibraryList(
        model: LibraryListModel,
        sortOption: SortOption,
        emphasizeLastPlayed: Boolean = false
    ): List<FastScrollSection> {
        val hasHeaders = model.segments.isNotEmpty()

        if (sortOption == SortOption.DATE_ADDED && !emphasizeLastPlayed) {
            return buildDateSections(
                totalItems = model.size,
                getDateAdded = { index ->
                    when (val item = model.itemAt(index)) {
                        is LibraryListItem.AlbumHeader ->
                            model.songsById[item.songIds.firstOrNull()]?.dateAdded ?: 0L
                        is LibraryListItem.SongRow ->
                            item.song.dateAdded
                    }
                },
                getPreviewText = { index ->
                    when (val item = model.itemAt(index)) {
                        is LibraryListItem.AlbumHeader -> item.displayName
                        is LibraryListItem.SongRow -> item.song.title
                    }
                },
                isValidItem = { index ->
                    if (hasHeaders) model.itemAt(index) is LibraryListItem.AlbumHeader else true
                }
            )
        }

        return buildDeduplicatedSections(model.size) { i ->
            val item = model.itemAt(i)
            if (hasHeaders && item is LibraryListItem.SongRow) {
                null
            } else {
                when (item) {
                    is LibraryListItem.AlbumHeader -> when (sortOption) {
                        SortOption.TITLE, SortOption.ALBUM ->
                            FastScrollSection(textSection(item.displayName), i, item.displayName)
                        SortOption.ARTIST ->
                            FastScrollSection(textSection(item.artistName), i, item.artistName)
                        SortOption.GENRE ->
                            FastScrollSection(genreSection(item.sortHint), i, item.displayName)
                        SortOption.DATE_ADDED -> {
                            val firstSong = model.songsById[item.songIds.firstOrNull()]
                            FastScrollSection(dateSection(firstSong?.dateAdded ?: 0L), i, item.displayName)
                        }
                    }

                    is LibraryListItem.SongRow -> {
                        val song = item.song
                        if (emphasizeLastPlayed) {
                            FastScrollSection(recentSection(song.lastPlayedAt), i, "${song.title} • ${song.artist}")
                        } else {
                            when (sortOption) {
                                SortOption.TITLE ->
                                    FastScrollSection(textSection(song.title), i, "${song.title} • ${song.artist}")
                                SortOption.ARTIST ->
                                    FastScrollSection(textSection(song.artist), i, "${song.artist} • ${song.title}")
                                SortOption.ALBUM ->
                                    FastScrollSection(textSection(song.album), i, "${song.album} • ${song.title}")
                                SortOption.GENRE ->
                                    FastScrollSection(genreSection(song.genre), i, "${song.genre ?: "Desconocido"} • ${song.title}")
                                SortOption.DATE_ADDED ->
                                    FastScrollSection(dateSection(song.dateAdded), i, song.title)
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Builds sections for [Album] browse list.
     */
    fun fromAlbums(
        albums: List<Album>,
        sortOption: SortOption
    ): List<FastScrollSection> {
        if (sortOption == SortOption.DATE_ADDED) {
            return buildDateSections(
                totalItems = albums.size,
                getDateAdded = { albums[it].dateAdded ?: 0L },
                getPreviewText = { "${albums[it].displayName} • ${albums[it].artist}" }
            )
        }

        return buildDeduplicatedSections(albums.size) { i ->
            val album = albums[i]
            when (sortOption) {
                SortOption.TITLE, SortOption.ALBUM ->
                    FastScrollSection(textSection(album.displayName), i, "${album.displayName} • ${album.artist}")
                SortOption.ARTIST ->
                    FastScrollSection(textSection(album.artist), i, "${album.artist} • ${album.displayName}")
                SortOption.GENRE ->
                    FastScrollSection(genreSection(album.genre), i, "${album.displayName} (${album.genre ?: ""})")
                SortOption.DATE_ADDED ->
                    FastScrollSection(dateSection(album.dateAdded ?: 0L), i, album.displayName)
            }
        }
    }

    /**
     * Builds sections for [Artist] browse list.
     */
    fun fromArtists(
        artists: List<Artist>,
        sortOption: SortOption
    ): List<FastScrollSection> {
        if (sortOption == SortOption.DATE_ADDED) {
            return buildDateSections(
                totalItems = artists.size,
                getDateAdded = { artists[it].dateAdded ?: 0L },
                getPreviewText = { artists[it].name }
            )
        }

        return buildDeduplicatedSections(artists.size) { i ->
            val artist = artists[i]
            when (sortOption) {
                SortOption.TITLE, SortOption.ARTIST, SortOption.ALBUM ->
                    FastScrollSection(textSection(artist.name), i, artist.name)
                SortOption.GENRE ->
                    FastScrollSection(genreSection(artist.genre), i, "${artist.name} (${artist.genre ?: ""})")
                SortOption.DATE_ADDED ->
                    FastScrollSection(dateSection(artist.dateAdded ?: 0L), i, artist.name)
            }
        }
    }

    /**
     * Builds sections for [GenreGroup] browse list.
     */
    fun fromGenres(
        genres: List<GenreGroup>,
        sortOption: SortOption
    ): List<FastScrollSection> {
        if (sortOption == SortOption.DATE_ADDED) {
            return buildDateSections(
                totalItems = genres.size,
                getDateAdded = { genres[it].dateAdded ?: 0L },
                getPreviewText = { genres[it].name }
            )
        }

        return buildDeduplicatedSections(genres.size) { i ->
            val genre = genres[i]
            when (sortOption) {
                SortOption.DATE_ADDED -> FastScrollSection(dateSection(genre.dateAdded ?: 0L), i, genre.name)
                else -> FastScrollSection(genreSection(genre.name), i, genre.name)
            }
        }
    }
}
