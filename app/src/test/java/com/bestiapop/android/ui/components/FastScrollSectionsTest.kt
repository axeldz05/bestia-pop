package com.bestiapop.android.ui.components

import com.bestiapop.android.data.model.Album
import com.bestiapop.android.data.model.Artist
import com.bestiapop.android.data.model.GenreGroup
import com.bestiapop.android.data.model.Song
import com.bestiapop.android.domain.usecase.GetLibrarySongsUseCase
import com.bestiapop.android.ui.SortOption
import com.bestiapop.android.ui.state.LibraryListModel
import com.bestiapop.android.ui.state.LibraryViewMode
import androidx.compose.ui.unit.dp
import com.bestiapop.android.data.preferences.FastScrollSettings
import com.bestiapop.android.data.preferences.FastScrollSide
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class FastScrollSectionsTest {

    private fun song(
        id: Long,
        title: String,
        artist: String = "Artist",
        album: String = "Album",
        genre: String = "Rock",
        dateAdded: Long = 1_700_000_000_000L + id * 100_000L,
        lastPlayedAt: Long = 0L
    ) = Song(
        id = id,
        uriString = "file:///$id",
        title = title,
        artist = artist,
        album = album,
        genre = genre,
        dateAdded = dateAdded,
        lastPlayedAt = lastPlayedAt
    )

    @Test
    fun normalizeSectionChar_handlesPunctuationAccentsNumbersAndSymbols() {
        assertEquals("C", FastScrollSections.normalizeSectionChar("Canción"))
        assertEquals("A", FastScrollSections.normalizeSectionChar("Árbol"))
        assertEquals("E", FastScrollSections.normalizeSectionChar("éxitos"))
        assertEquals("I", FastScrollSections.normalizeSectionChar("Ícaro"))
        assertEquals("O", FastScrollSections.normalizeSectionChar("Ópera"))
        assertEquals("U", FastScrollSections.normalizeSectionChar("Último"))
        assertEquals("D", FastScrollSections.normalizeSectionChar("\"(Don't Want to)\""))
        assertEquals("Q", FastScrollSections.normalizeSectionChar("¿Quién será?"))
        assertEquals("H", FastScrollSections.normalizeSectionChar("¡Hola mundo!"))
        assertEquals("0-9", FastScrollSections.normalizeSectionChar("1999"))
        assertEquals("0-9", FastScrollSections.normalizeSectionChar("2 Pac"))
        assertEquals("*", FastScrollSections.normalizeSectionChar("...dots"))
        assertEquals("*", FastScrollSections.normalizeSectionChar("..."))
        assertEquals("*", FastScrollSections.normalizeSectionChar(""))
        assertEquals("*", FastScrollSections.normalizeSectionChar(null))
        // Bilingual title with romanized text in parentheses/brackets
        assertEquals("Y", FastScrollSections.normalizeSectionChar("夜鷹 (Yodaka)"))
        assertEquals("S", FastScrollSections.normalizeSectionChar("進撃の巨人 [Shingeki no Kyojin]"))
    }

    @Test
    fun fromLibraryList_returnsEmptyIfUnderThreshold() {
        val songs = (1..11).map { song(it.toLong(), "Song $it") }
        val model = LibraryListModel.of(songs)
        val sections = FastScrollSections.fromLibraryList(model, SortOption.TITLE)
        assertTrue(sections.isEmpty())
    }

    @Test
    fun fromLibraryList_onlyIncludesLettersWithActualSongs() {
        // Create 15 songs starting with A, B, and Z only
        val songs = listOf(
            song(1, "Abba"),
            song(2, "Adele"),
            song(3, "Aerosmith"),
            song(4, "Air"),
            song(5, "Bad Bunny"),
            song(6, "Beatles"),
            song(7, "Beck"),
            song(8, "Beyonce"),
            song(9, "Bjork"),
            song(10, "Blink 182"),
            song(11, "Blur"),
            song(12, "Boston"),
            song(13, "Zappa"),
            song(14, "Zaz"),
            song(15, "Zombies")
        )
        val model = LibraryListModel.of(songs)
        val sections = FastScrollSections.fromLibraryList(model, SortOption.TITLE)

        assertEquals(3, sections.size)
        assertEquals("A", sections[0].label)
        assertEquals(0, sections[0].itemIndex)
        assertEquals("B", sections[1].label)
        assertEquals(4, sections[1].itemIndex)
        assertEquals("Z", sections[2].label)
        assertEquals(12, sections[2].itemIndex)
    }

    @Test
    fun fromLibraryList_byArtist_groupsCorrectly() {
        val songs = listOf(
            song(1, "Song 1", artist = "Arctic Monkeys"),
            song(2, "Song 2", artist = "Arctic Monkeys"),
            song(3, "Song 3", artist = "Arctic Monkeys"),
            song(4, "Song 4", artist = "Arctic Monkeys"),
            song(5, "Song 5", artist = "Daft Punk"),
            song(6, "Song 6", artist = "Daft Punk"),
            song(7, "Song 7", artist = "Daft Punk"),
            song(8, "Song 8", artist = "Daft Punk"),
            song(9, "Song 9", artist = "Radiohead"),
            song(10, "Song 10", artist = "Radiohead"),
            song(11, "Song 11", artist = "Radiohead"),
            song(12, "Song 12", artist = "Radiohead")
        )
        val model = LibraryListModel.of(songs, sortOption = SortOption.ARTIST)
        val sections = FastScrollSections.fromLibraryList(model, SortOption.ARTIST)

        assertEquals(3, sections.size)
        assertEquals("A", sections[0].label)
        assertEquals("D", sections[1].label)
        assertEquals("R", sections[2].label)
    }

    @Test
    fun fromLibraryList_byGenre_groupsCorrectly() {
        val songs = listOf(
            song(1, "Song 1", genre = "Electronic"),
            song(2, "Song 2", genre = "Electronic"),
            song(3, "Song 3", genre = "Electronic"),
            song(4, "Song 4", genre = "Jazz"),
            song(5, "Song 5", genre = "Jazz"),
            song(6, "Song 6", genre = "Jazz"),
            song(7, "Song 7", genre = "Jazz"),
            song(8, "Song 8", genre = "Rock"),
            song(9, "Song 9", genre = "Rock"),
            song(10, "Song 10", genre = "Rock"),
            song(11, "Song 11", genre = "Rock"),
            song(12, "Song 12", genre = "Rock")
        )
        val model = LibraryListModel.of(songs, sortOption = SortOption.GENRE)
        val sections = FastScrollSections.fromLibraryList(model, SortOption.GENRE)

        assertEquals(3, sections.size)
        assertEquals("Electronic", sections[0].popupLabel)
        assertEquals("Jazz", sections[1].popupLabel)
        assertEquals("Rock", sections[2].popupLabel)
    }

    @Test
    fun fromAlbums_extractsAlbumSections() {
        val albums = listOf(
            Album(name = "A1", artist = "Artist", songCount = 1),
            Album(name = "A2", artist = "Artist", songCount = 1),
            Album(name = "A3", artist = "Artist", songCount = 1),
            Album(name = "A4", artist = "Artist", songCount = 1),
            Album(name = "M1", artist = "Artist", songCount = 1),
            Album(name = "M2", artist = "Artist", songCount = 1),
            Album(name = "M3", artist = "Artist", songCount = 1),
            Album(name = "M4", artist = "Artist", songCount = 1),
            Album(name = "T1", artist = "Artist", songCount = 1),
            Album(name = "T2", artist = "Artist", songCount = 1),
            Album(name = "T3", artist = "Artist", songCount = 1),
            Album(name = "T4", artist = "Artist", songCount = 1)
        )
        val sections = FastScrollSections.fromAlbums(albums, SortOption.TITLE)
        assertEquals(3, sections.size)
        assertEquals(listOf("A", "M", "T"), sections.map { it.label })
    }

    @Test
    fun fromArtists_extractsArtistSections() {
        val artists = listOf(
            Artist("Bowie", 1, 1, null, null, null),
            Artist("Bush", 1, 1, null, null, null),
            Artist("Byrne", 1, 1, null, null, null),
            Artist("Blink", 1, 1, null, null, null),
            Artist("Eminem", 1, 1, null, null, null),
            Artist("Enya", 1, 1, null, null, null),
            Artist("Eagles", 1, 1, null, null, null),
            Artist("Erasure", 1, 1, null, null, null),
            Artist("Gorillaz", 1, 1, null, null, null),
            Artist("Genesis", 1, 1, null, null, null),
            Artist("Ghost", 1, 1, null, null, null),
            Artist("Green Day", 1, 1, null, null, null)
        )
        val sections = FastScrollSections.fromArtists(artists, SortOption.TITLE)
        assertEquals(3, sections.size)
        assertEquals(listOf("B", "E", "G"), sections.map { it.label })
    }

    @Test
    fun fromGenres_extractsGenreSections() {
        val genres = listOf(
            GenreGroup("Ambient", 1, null, null),
            GenreGroup("Blues", 1, null, null),
            GenreGroup("Classical", 1, null, null),
            GenreGroup("Disco", 1, null, null),
            GenreGroup("Electronic", 1, null, null),
            GenreGroup("Folk", 1, null, null),
            GenreGroup("Gothic", 1, null, null),
            GenreGroup("House", 1, null, null),
            GenreGroup("Indie", 1, null, null),
            GenreGroup("Jazz", 1, null, null),
            GenreGroup("K-Pop", 1, null, null),
            GenreGroup("Latin", 1, null, null)
        )
        val sections = FastScrollSections.fromGenres(genres, SortOption.TITLE)
        assertEquals(12, sections.size)
        assertEquals("Ambient", sections[0].popupLabel)
        assertEquals("Latin", sections[11].popupLabel)
    }

    @Test
    fun fromLibraryList_naturalOrder_lettersThenNumbersThenSymbols() {
        val songs = listOf(
            song(1, "Alpha"),
            song(2, "Beta"),
            song(3, "Delta"),
            song(4, "Epsilon"),
            song(5, "Gamma"),
            song(6, "夜鷹 (Yodaka)"),
            song(7, "Zeta"),
            song(8, "1999"),
            song(9, "2001"),
            song(10, "50 Cent"),
            song(11, "...Special"),
            song(12, "★★★ Stars")
        )
        val sortedSongs = GetLibrarySongsUseCase().execute(songs, "", SortOption.TITLE)
        val model = LibraryListModel.of(sortedSongs)
        val sections = FastScrollSections.fromLibraryList(model, SortOption.TITLE)

        val labels = sections.map { it.label }
        assertEquals(listOf("A", "B", "D", "E", "G", "Y", "Z", "0-9", "*"), labels)
    }

    @Test
    fun fromLibraryList_withAlbumHeaders_onlyIndexesAlbumHeaders() {
        // Create 2 albums with songs starting with arbitrary letters
        val songs = listOf(
            song(1, "Zara", album = "Abbey Road"),
            song(2, "Xavier", album = "Abbey Road"),
            song(3, "Queen", album = "Abbey Road"),
            song(4, "Paul", album = "Abbey Road"),
            song(5, "John", album = "Abbey Road"),
            song(6, "George", album = "Abbey Road"),
            song(7, "Ringo", album = "Abbey Road"),
            song(8, "Alpha", album = "Back in Black"),
            song(9, "Bravo", album = "Back in Black"),
            song(10, "Charlie", album = "Back in Black"),
            song(11, "Delta", album = "Back in Black"),
            song(12, "Echo", album = "Back in Black"),
            song(13, "Foxtrot", album = "Back in Black")
        )
        val useCase = GetLibrarySongsUseCase()
        val model = useCase.buildListModel(songs, LibraryViewMode.ALBUM_GROUPS, sortOption = SortOption.TITLE)

        val sections = FastScrollSections.fromLibraryList(model, SortOption.TITLE)
        // Only the album headers ("Abbey Road" -> A, "Back in Black" -> B) should be indexed!
        // Song titles like "Zara", "Xavier", etc. should NOT appear as sections!
        assertEquals(listOf("A", "B"), sections.map { it.label })
    }

    private fun dateEpoch(year: Int, month: Int, day: Int, hour: Int = 12): Long {
        return ZonedDateTime.of(year, month, day, hour, 0, 0, 0, ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
    }

    @Test
    fun fromLibraryList_byDateAdded_sameYearDifferentMonths_generatesMonthSections() {
        // 16 songs all added in 2026 across 4 different months
        val songs = listOf(
            song(1, "S1", dateAdded = dateEpoch(2026, 9, 20)),
            song(2, "S2", dateAdded = dateEpoch(2026, 9, 10)),
            song(3, "S3", dateAdded = dateEpoch(2026, 9, 5)),
            song(4, "S4", dateAdded = dateEpoch(2026, 9, 1)),
            song(5, "S5", dateAdded = dateEpoch(2026, 7, 20)),
            song(6, "S6", dateAdded = dateEpoch(2026, 7, 10)),
            song(7, "S7", dateAdded = dateEpoch(2026, 7, 5)),
            song(8, "S8", dateAdded = dateEpoch(2026, 7, 1)),
            song(9, "S9", dateAdded = dateEpoch(2026, 3, 20)),
            song(10, "S10", dateAdded = dateEpoch(2026, 3, 10)),
            song(11, "S11", dateAdded = dateEpoch(2026, 3, 5)),
            song(12, "S12", dateAdded = dateEpoch(2026, 3, 1)),
            song(13, "S13", dateAdded = dateEpoch(2026, 1, 20)),
            song(14, "S14", dateAdded = dateEpoch(2026, 1, 10)),
            song(15, "S15", dateAdded = dateEpoch(2026, 1, 5)),
            song(16, "S16", dateAdded = dateEpoch(2026, 1, 1))
        )
        val sortedSongs = GetLibrarySongsUseCase().execute(songs, "", SortOption.DATE_ADDED)
        val model = LibraryListModel.of(sortedSongs)
        val sections = FastScrollSections.fromLibraryList(model, SortOption.DATE_ADDED)

        assertEquals(listOf("Sep", "Jul", "Mar", "Ene"), sections.map { it.label })
        assertEquals(
            listOf("Septiembre 2026", "Julio 2026", "Marzo 2026", "Enero 2026"),
            sections.map { it.popupLabel }
        )
    }

    @Test
    fun fromLibraryList_byDateAdded_multiYear_generatesYearTransitionAndMonthSections() {
        val songs = listOf(
            song(1, "S1", dateAdded = dateEpoch(2026, 9, 10)),
            song(2, "S2", dateAdded = dateEpoch(2026, 9, 5)),
            song(3, "S3", dateAdded = dateEpoch(2026, 9, 1)),
            song(4, "S4", dateAdded = dateEpoch(2026, 8, 10)),
            song(5, "S5", dateAdded = dateEpoch(2026, 8, 5)),
            song(6, "S6", dateAdded = dateEpoch(2026, 8, 1)),
            song(7, "S7", dateAdded = dateEpoch(2025, 12, 10)),
            song(8, "S8", dateAdded = dateEpoch(2025, 12, 5)),
            song(9, "S9", dateAdded = dateEpoch(2025, 12, 1)),
            song(10, "S10", dateAdded = dateEpoch(2025, 11, 10)),
            song(11, "S11", dateAdded = dateEpoch(2025, 11, 5)),
            song(12, "S12", dateAdded = dateEpoch(2025, 11, 1))
        )
        val sortedSongs = GetLibrarySongsUseCase().execute(songs, "", SortOption.DATE_ADDED)
        val model = LibraryListModel.of(sortedSongs)
        val sections = FastScrollSections.fromLibraryList(model, SortOption.DATE_ADDED)

        assertEquals(listOf("Sep", "Ago", "Dic", "Nov"), sections.map { it.label })
        assertEquals("Septiembre 2026", sections[0].popupLabel)
        assertEquals("Agosto 2026", sections[1].popupLabel)
        assertEquals("Diciembre 2025", sections[2].popupLabel)
        assertEquals("Noviembre 2025", sections[3].popupLabel)
    }

    @Test
    fun fromLibraryList_byDateAdded_sameMonthDifferentDays_generatesDaySections() {
        // 12 songs all added in September 2026 across 4 distinct days
        val songs = listOf(
            song(1, "S1", dateAdded = dateEpoch(2026, 9, 20)),
            song(2, "S2", dateAdded = dateEpoch(2026, 9, 20)),
            song(3, "S3", dateAdded = dateEpoch(2026, 9, 20)),
            song(4, "S4", dateAdded = dateEpoch(2026, 9, 15)),
            song(5, "S5", dateAdded = dateEpoch(2026, 9, 15)),
            song(6, "S6", dateAdded = dateEpoch(2026, 9, 15)),
            song(7, "S7", dateAdded = dateEpoch(2026, 9, 10)),
            song(8, "S8", dateAdded = dateEpoch(2026, 9, 10)),
            song(9, "S9", dateAdded = dateEpoch(2026, 9, 10)),
            song(10, "S10", dateAdded = dateEpoch(2026, 9, 5)),
            song(11, "S11", dateAdded = dateEpoch(2026, 9, 5)),
            song(12, "S12", dateAdded = dateEpoch(2026, 9, 5))
        )
        val sortedSongs = GetLibrarySongsUseCase().execute(songs, "", SortOption.DATE_ADDED)
        val model = LibraryListModel.of(sortedSongs)
        val sections = FastScrollSections.fromLibraryList(model, SortOption.DATE_ADDED)

        assertTrue(sections.size >= 2)
        // The days should be indexed
        val dayLabels = sections.map { it.label }
        assertTrue(dayLabels.contains("15") || dayLabels.contains("Hoy") || dayLabels.contains("Ayer"))
        assertTrue(dayLabels.contains("10") || dayLabels.contains("Hoy") || dayLabels.contains("Ayer"))
        assertTrue(dayLabels.contains("5") || dayLabels.contains("Hoy") || dayLabels.contains("Ayer"))
    }

    @Test
    fun fromLibraryList_byDateAdded_sameDayDifferentHours_generatesHourSections() {
        // 12 songs all on 2026-09-15 across 3 distinct hours
        val songs = listOf(
            song(1, "S1", dateAdded = dateEpoch(2026, 9, 15, 18)),
            song(2, "S2", dateAdded = dateEpoch(2026, 9, 15, 18)),
            song(3, "S3", dateAdded = dateEpoch(2026, 9, 15, 18)),
            song(4, "S4", dateAdded = dateEpoch(2026, 9, 15, 18)),
            song(5, "S5", dateAdded = dateEpoch(2026, 9, 15, 14)),
            song(6, "S6", dateAdded = dateEpoch(2026, 9, 15, 14)),
            song(7, "S7", dateAdded = dateEpoch(2026, 9, 15, 14)),
            song(8, "S8", dateAdded = dateEpoch(2026, 9, 15, 14)),
            song(9, "S9", dateAdded = dateEpoch(2026, 9, 15, 10)),
            song(10, "S10", dateAdded = dateEpoch(2026, 9, 15, 10)),
            song(11, "S11", dateAdded = dateEpoch(2026, 9, 15, 10)),
            song(12, "S12", dateAdded = dateEpoch(2026, 9, 15, 10))
        )
        val sortedSongs = GetLibrarySongsUseCase().execute(songs, "", SortOption.DATE_ADDED)
        val model = LibraryListModel.of(sortedSongs)
        val sections = FastScrollSections.fromLibraryList(model, SortOption.DATE_ADDED)

        assertEquals(listOf("18h", "14h", "10h"), sections.map { it.label })
    }

    @Test
    fun fromLibraryList_byDateAdded_withUndatedSongs_putsSinFechaAtEnd() {
        val songs = listOf(
            song(1, "S1", dateAdded = dateEpoch(2026, 9, 20)),
            song(2, "S2", dateAdded = dateEpoch(2026, 9, 10)),
            song(3, "S3", dateAdded = dateEpoch(2026, 9, 5)),
            song(4, "S4", dateAdded = dateEpoch(2026, 9, 1)),
            song(5, "S5", dateAdded = dateEpoch(2026, 7, 20)),
            song(6, "S6", dateAdded = dateEpoch(2026, 7, 10)),
            song(7, "S7", dateAdded = dateEpoch(2026, 7, 5)),
            song(8, "S8", dateAdded = dateEpoch(2026, 7, 1)),
            song(9, "S9", dateAdded = dateEpoch(2026, 3, 20)),
            song(10, "S10", dateAdded = dateEpoch(2026, 3, 10)),
            song(11, "S11", dateAdded = 0L),
            song(12, "S12", dateAdded = 0L)
        )
        val sortedSongs = GetLibrarySongsUseCase().execute(songs, "", SortOption.DATE_ADDED)
        val model = LibraryListModel.of(sortedSongs)
        val sections = FastScrollSections.fromLibraryList(model, SortOption.DATE_ADDED)

        assertTrue(sections.size >= 2)
        val lastSection = sections.last()
        assertEquals("#", lastSection.label)
        assertEquals("Sin fecha", lastSection.popupLabel)
    }

    @Test
    fun fromAlbums_byDateAdded_generatesSections() {
        val albums = listOf(
            Album("A1", "Artist", 1, null, "Rock", dateAdded = dateEpoch(2026, 9, 1)),
            Album("A2", "Artist", 1, null, "Rock", dateAdded = dateEpoch(2026, 9, 2)),
            Album("A3", "Artist", 1, null, "Rock", dateAdded = dateEpoch(2026, 9, 3)),
            Album("A4", "Artist", 1, null, "Rock", dateAdded = dateEpoch(2026, 8, 1)),
            Album("A5", "Artist", 1, null, "Rock", dateAdded = dateEpoch(2026, 8, 2)),
            Album("A6", "Artist", 1, null, "Rock", dateAdded = dateEpoch(2026, 8, 3)),
            Album("A7", "Artist", 1, null, "Rock", dateAdded = dateEpoch(2026, 7, 1)),
            Album("A8", "Artist", 1, null, "Rock", dateAdded = dateEpoch(2026, 7, 2)),
            Album("A9", "Artist", 1, null, "Rock", dateAdded = dateEpoch(2026, 7, 3)),
            Album("A10", "Artist", 1, null, "Rock", dateAdded = dateEpoch(2026, 6, 1)),
            Album("A11", "Artist", 1, null, "Rock", dateAdded = dateEpoch(2026, 6, 2)),
            Album("A12", "Artist", 1, null, "Rock", dateAdded = dateEpoch(2026, 6, 3))
        ).sortedByDescending { it.dateAdded }

        val sections = FastScrollSections.fromAlbums(albums, SortOption.DATE_ADDED)
        assertEquals(listOf("Sep", "Ago", "Jul", "Jun"), sections.map { it.label })
    }

    @Test
    fun findClosestSectionIndex_exactHitOnLetterCenters_returnsExactLetter() {
        // 26 letters (A=0, B=1, ... K=10, L=11, M=12, N=13, O=14 ... Z=25)
        // Each letter has a measured slot center in pixels
        val count = 26
        val slotHeight = 20f
        val padding = 10f
        val centers = FloatArray(count) { i -> padding + (i + 0.5f) * slotHeight }

        // User taps directly on visual "K" (index 10)
        val touchYK = centers[10]
        val selectedK = FastScrollSections.findClosestSectionIndex(
            touchY = touchYK,
            itemCenters = centers,
            sectionsCount = count,
            railHeight = 540f,
            verticalPaddingPx = padding
        )
        assertEquals(10, selectedK)

        // User taps directly on visual "O" (index 14)
        val touchYO = centers[14]
        val selectedO = FastScrollSections.findClosestSectionIndex(
            touchY = touchYO,
            itemCenters = centers,
            sectionsCount = count,
            railHeight = 540f,
            verticalPaddingPx = padding
        )
        assertEquals(14, selectedO)
    }

    @Test
    fun findClosestSectionIndex_midpointTransitions() {
        val count = 26
        val centers = FloatArray(count) { i -> (i + 0.5f) * 20f }

        // Just above midpoint between index 10 (K) and index 11 (L)
        val justAboveMidpoint = (centers[10] + centers[11]) / 2f - 0.5f
        assertEquals(10, FastScrollSections.findClosestSectionIndex(justAboveMidpoint, centers, count))

        // Just below midpoint between index 10 (K) and index 11 (L)
        val justBelowMidpoint = (centers[10] + centers[11]) / 2f + 0.5f
        assertEquals(11, FastScrollSections.findClosestSectionIndex(justBelowMidpoint, centers, count))
    }

    @Test
    fun findClosestSectionIndex_clampsAboveAndBelow() {
        val count = 10
        val centers = FloatArray(count) { i -> 20f + (i + 0.5f) * 30f }

        // Touch above the top of the rail
        assertEquals(0, FastScrollSections.findClosestSectionIndex(-50f, centers, count, 400f, 20f))
        // Touch below the bottom of the rail
        assertEquals(count - 1, FastScrollSections.findClosestSectionIndex(999f, centers, count, 400f, 20f))
    }

    @Test
    fun findClosestSectionIndex_fallbackWhenCentersUninitialized() {
        val count = 26
        val emptyCenters = FloatArray(count)
        val railHeight = 520f
        val padding = 0f

        // Index 10 in 26 letters spans from (10/26)*520 = 200 to (11/26)*520 = 220. Center is 210.
        val index10 = FastScrollSections.findClosestSectionIndex(
            touchY = 210f,
            itemCenters = emptyCenters,
            sectionsCount = count,
            railHeight = railHeight,
            verticalPaddingPx = padding
        )
        assertEquals(10, index10)
    }

    @Test
    fun dedicatedGutterWidth_reservesSpaceOnActiveSideWhenEnabled() {
        // Disabled: 0.dp on both sides
        assertEquals(0.dp, FastScrollDefaults.dedicatedStartGutterWidth(enabled = false, side = FastScrollSide.LEFT, sectionsCount = 10))
        assertEquals(0.dp, FastScrollDefaults.dedicatedEndGutterWidth(enabled = false, side = FastScrollSide.LEFT, sectionsCount = 10))
        assertEquals(0.dp, FastScrollDefaults.dedicatedStartGutterWidth(enabled = false, side = FastScrollSide.RIGHT, sectionsCount = 10))
        assertEquals(0.dp, FastScrollDefaults.dedicatedEndGutterWidth(enabled = false, side = FastScrollSide.RIGHT, sectionsCount = 10))

        // When sectionsCount <= 1: 0.dp on both sides
        assertEquals(0.dp, FastScrollDefaults.dedicatedStartGutterWidth(enabled = true, side = FastScrollSide.LEFT, sectionsCount = 0))
        assertEquals(0.dp, FastScrollDefaults.dedicatedStartGutterWidth(enabled = true, side = FastScrollSide.LEFT, sectionsCount = 1))
        assertEquals(0.dp, FastScrollDefaults.dedicatedEndGutterWidth(enabled = true, side = FastScrollSide.RIGHT, sectionsCount = 0))
        assertEquals(0.dp, FastScrollDefaults.dedicatedEndGutterWidth(enabled = true, side = FastScrollSide.RIGHT, sectionsCount = 1))

        // Left side active with >= 2 items: 36.dp dedicated space on start, 0.dp on end
        assertEquals(36.dp, FastScrollDefaults.dedicatedStartGutterWidth(enabled = true, side = FastScrollSide.LEFT, sectionsCount = 2))
        assertEquals(0.dp, FastScrollDefaults.dedicatedEndGutterWidth(enabled = true, side = FastScrollSide.LEFT, sectionsCount = 2))
        assertEquals(36.dp, FastScrollDefaults.dedicatedStartGutterWidth(FastScrollSettings(enabled = true, side = FastScrollSide.LEFT), sectionsCount = 26))
        assertEquals(0.dp, FastScrollDefaults.dedicatedEndGutterWidth(FastScrollSettings(enabled = true, side = FastScrollSide.LEFT), sectionsCount = 26))

        // Right side active with >= 2 items: 36.dp dedicated space on end, 0.dp on start
        assertEquals(0.dp, FastScrollDefaults.dedicatedStartGutterWidth(enabled = true, side = FastScrollSide.RIGHT, sectionsCount = 2))
        assertEquals(36.dp, FastScrollDefaults.dedicatedEndGutterWidth(enabled = true, side = FastScrollSide.RIGHT, sectionsCount = 2))
        assertEquals(0.dp, FastScrollDefaults.dedicatedStartGutterWidth(FastScrollSettings(enabled = true, side = FastScrollSide.RIGHT), sectionsCount = 26))
        assertEquals(36.dp, FastScrollDefaults.dedicatedEndGutterWidth(FastScrollSettings(enabled = true, side = FastScrollSide.RIGHT), sectionsCount = 26))

        // Backwards-compatible dedicatedGutterWidth matches start gutter
        assertEquals(36.dp, FastScrollDefaults.dedicatedGutterWidth(enabled = true, side = FastScrollSide.LEFT, sectionsCount = 10))
        assertEquals(0.dp, FastScrollDefaults.dedicatedGutterWidth(enabled = true, side = FastScrollSide.RIGHT, sectionsCount = 10))
    }
}
