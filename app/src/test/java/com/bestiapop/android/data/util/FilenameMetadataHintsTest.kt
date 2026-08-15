package com.bestiapop.android.data.util

import com.bestiapop.android.domain.util.identifySearchTexts
import com.bestiapop.android.domain.util.isTrackNumberLabel
import com.bestiapop.android.domain.util.parseFilenameMetadataHints
import com.bestiapop.android.domain.util.resolveWeakIdentityHints
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FilenameMetadataHintsTest {

    @Test
    fun parse_splitsFirstUnderscore() {
        val hints = parseFilenameMetadataHints("Radiohead_Creep")
        assertEquals("Radiohead", hints.artist)
        assertEquals("Creep", hints.title)
    }

    @Test
    fun parse_underscoresBecomeSpacesInParts() {
        val hints = parseFilenameMetadataHints("The_Beatles_Hey_Jude")
        assertNull(hints.artist)
        assertEquals("The Beatles Hey Jude", hints.title)
    }

    @Test
    fun parse_apostropheHole_howlinWolf() {
        val hints = parseFilenameMetadataHints("Howlin__Wolf_Evil")
        assertEquals("Howlin Wolf", hints.artist)
        assertEquals("Evil", hints.title)
    }

    @Test
    fun parse_collapsesRomanizedHolesAndRestoresContraction() {
        val hints = parseFilenameMetadataHints("_______I_m__Waiting_for_the_Sun")
        assertNull(hints.artist)
        assertEquals("I'm Waiting for the Sun", hints.title)
    }

    @Test
    fun parse_discTrackPrefix_andKhzSuffix() {
        val hints = parseFilenameMetadataHints("1-03_Insisto__16-44.1_kHz_")
        assertNull(hints.artist)
        assertEquals("Insisto", hints.title)
        assertEquals(3, hints.trackNumber)
    }

    @Test
    fun parse_discTrackDotTitle_restoresContraction() {
        val hints = parseFilenameMetadataHints("1-18._I_ll_Face_Myself")
        assertEquals("I'll Face Myself", hints.title)
        assertEquals(18, hints.trackNumber)
    }

    @Test
    fun identifySearchTexts_gluesAccentHoles_andKeepsDistinctiveTail() {
        val glued = identifySearchTexts("Ciro Y Los Persas F cil")
        assertTrue(glued.any { it.contains("Fcil") })

        val tail = identifySearchTexts(
            "And So I Watch You From Afar Mother Belfast Part 2"
        )
        assertTrue(tail.any { it.contains("Mother Belfast") })
    }

    @Test
    fun parse_noUnderscore_titleOnly() {
        val hints = parseFilenameMetadataHints("SomeTrack")
        assertNull(hints.artist)
        assertEquals("SomeTrack", hints.title)
    }

    @Test
    fun parse_trackNumberUnderscoreDash_titleOnly() {
        val hints = parseFilenameMetadataHints("02_-_A_Game_of_Inches")
        assertNull(hints.artist)
        assertEquals("A Game of Inches", hints.title)
        assertEquals(2, hints.trackNumber)
    }

    @Test
    fun parse_trackNumberSpacedDash() {
        val hints = parseFilenameMetadataHints("02 - A Game of Inches")
        assertNull(hints.artist)
        assertEquals("A Game of Inches", hints.title)
        assertEquals(2, hints.trackNumber)
    }

    @Test
    fun parse_trackNumberDotTitle() {
        val hints = parseFilenameMetadataHints("03. Creep")
        assertNull(hints.artist)
        assertEquals("Creep", hints.title)
        assertEquals(3, hints.trackNumber)
    }

    @Test
    fun parse_trackNumber_embeddedArtistTitle() {
        val hints = parseFilenameMetadataHints(
            "02_-_El_Cuarteto_de_Nos_-_Emilio_García_-_Cuna_de_colores"
        )
        assertEquals("El Cuarteto de Nos", hints.artist)
        assertEquals("Cuna de colores", hints.title)
        assertEquals(2, hints.trackNumber)
    }

    @Test
    fun parse_artistDashTitle() {
        val hints = parseFilenameMetadataHints("Radiohead - Creep")
        assertEquals("Radiohead", hints.artist)
        assertEquals("Creep", hints.title)
    }

    @Test
    fun parse_doesNotTreatBandWithDigitsAsTrack() {
        val hints = parseFilenameMetadataHints("65daysofstatic_Retreat")
        assertEquals("65daysofstatic", hints.artist)
        assertEquals("Retreat", hints.title)
    }

    @Test
    fun resolveWeak_numericArtist_stripsTitleJunk() {
        val hints = resolveWeakIdentityHints("02", "- A Game of Inches")
        assertNull(hints.artist)
        assertEquals("A Game of Inches", hints.title)
        assertEquals(2, hints.trackNumber)
    }

    @Test
    fun resolveWeak_numericArtist_embeddedArtistTitle() {
        val hints = resolveWeakIdentityHints(
            "02",
            "- El Cuarteto de Nos - Emilio García - Cuna de colores"
        )
        assertEquals("El Cuarteto de Nos", hints.artist)
        assertEquals("Cuna de colores", hints.title)
    }

    @Test
    fun isTrackNumberLabel_detectsRipsNotBands() {
        assertTrue(isTrackNumberLabel("02"))
        assertTrue(isTrackNumberLabel("1-12"))
        assertFalse(isTrackNumberLabel("65daysofstatic"))
        assertFalse(isTrackNumberLabel("Radiohead"))
    }

    @Test
    fun looksLikeStoragePath_detectsSafAndBestiaPop() {
        assertTrue(looksLikeStoragePath("primary%3AMusic%2FBestiaPop%2FSong"))
        assertTrue(looksLikeStoragePath("content://com.android.externalstorage.documents/tree/primary%3AMusic"))
        assertTrue(looksLikeStoragePath("Music/BestiaPop/Daft_Punk_Digital_Love"))
        assertFalse(looksLikeStoragePath("Digital Love"))
        assertFalse(looksLikeStoragePath("Radiohead_Creep"))
    }

    @Test
    fun applyFilenameHints_fillsUnknownArtist() {
        val tagged = AudioFileMetadata(
            title = "Radiohead_Creep",
            artist = "Unknown Artist",
            album = "Unknown Album",
            genre = "Music",
            durationMs = 1000L,
            artworkUri = null
        )
        val result = AudioFileMetadata.applyFilenameHints(tagged, "Radiohead_Creep")
        assertEquals("Radiohead", result.artist)
        assertEquals("Creep", result.title)
        assertEquals("Unknown Album", result.album)
    }

    @Test
    fun applyFilenameHints_treatsYouTubeArtistAsWeak() {
        val tagged = AudioFileMetadata(
            title = "Artist - Song",
            artist = "YouTube Artist",
            album = "Unknown Album",
            genre = "Music",
            durationMs = 1000L,
            artworkUri = null
        )
        val result = AudioFileMetadata.applyFilenameHints(tagged, "Artist_Song")
        assertEquals("Artist", result.artist)
        assertEquals("Song", result.title)
    }

    @Test
    fun resolveWeak_youtubeArtist_splitsTitle() {
        val hints = resolveWeakIdentityHints("YouTube Artist", "Radiohead - Creep")
        assertEquals("Radiohead", hints.artist)
        assertEquals("Creep", hints.title)
    }

    @Test
    fun resolveWeak_enlaceWeb_splitsTitle() {
        val hints = resolveWeakIdentityHints("Enlace Web", "Daft Punk - Digital Love")
        assertEquals("Daft Punk", hints.artist)
        assertEquals("Digital Love", hints.title)
    }

    @Test
    fun applyFilenameHints_keepsKnownTags() {
        val tagged = AudioFileMetadata(
            title = "Creep",
            artist = "Radiohead",
            album = "Pablo Honey",
            genre = "Rock",
            durationMs = 1000L,
            artworkUri = null
        )
        val result = AudioFileMetadata.applyFilenameHints(tagged, "Radiohead_Creep")
        assertEquals("Radiohead", result.artist)
        assertEquals("Creep", result.title)
        assertEquals("Pablo Honey", result.album)
    }

    @Test
    fun applyFilenameHints_fixesNumericArtistRip() {
        val tagged = AudioFileMetadata(
            title = "- A Game of Inches",
            artist = "02",
            album = "Unknown Album",
            genre = "Music",
            durationMs = 1000L,
            artworkUri = null
        )
        val result = AudioFileMetadata.applyFilenameHints(tagged, "02_-_A_Game_of_Inches")
        assertEquals("Unknown Artist", result.artist)
        assertEquals("A Game of Inches", result.title)
        assertEquals(2, result.trackNumber)
    }

    @Test
    fun applyFilenameHints_numericArtist_embeddedArtistInTitle() {
        val tagged = AudioFileMetadata(
            title = "- El Cuarteto de Nos - Cuna de colores",
            artist = "02",
            album = "Unknown Album",
            genre = "Music",
            durationMs = 1000L,
            artworkUri = null
        )
        val result = AudioFileMetadata.applyFilenameHints(
            tagged,
            "02_-_El_Cuarteto_de_Nos_-_Cuna_de_colores"
        )
        assertEquals("El Cuarteto de Nos", result.artist)
        assertEquals("Cuna de colores", result.title)
    }
}
