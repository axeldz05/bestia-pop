package com.bestiapop.android.domain.util

import com.bestiapop.android.data.model.IdentifyApplyFields
import com.bestiapop.android.data.model.IdentifyCandidate
import com.bestiapop.android.data.model.OnlineCatalogTrack
import com.bestiapop.android.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IdentifyApplyChangesTest {

    private fun song(
        title: String = "Creep",
        artist: String = "Unknown Artist",
        album: String = "YouTube Music",
        year: Int = 0,
        trackNumber: Int = 0,
        artworkUri: String? = null
    ) = Song(
        id = 1L,
        uriString = "file:///tmp/creep.mp3",
        title = title,
        artist = artist,
        album = album,
        year = year,
        trackNumber = trackNumber,
        artworkUri = artworkUri,
        durationMs = 238_000L
    )

    private fun candidate(
        title: String = "Creep",
        artist: String = "Radiohead",
        album: String = "Pablo Honey",
        year: Int = 1993,
        trackNumber: Int = 2,
        artworkUri: String? = "https://img.example/a.jpg"
    ) = IdentifyCandidate(
        track = OnlineCatalogTrack(
            id = "$artist|$title",
            title = title,
            artist = artist,
            album = album,
            artworkUri = artworkUri,
            durationMs = 240_000L,
            audioUrl = "",
            provider = "Deezer",
            trackNumber = trackNumber,
            year = year
        ),
        score = 0.8f
    )

    @Test
    fun allFields_listsDifferingMetadata() {
        val changes = identifyApplyChanges(song(), candidate(), IdentifyApplyFields.ALL)
        val labels = changes.map { it.field.chipLabel }
        assertTrue(labels.contains("Artista"))
        assertTrue(labels.contains("Álbum"))
        assertTrue(labels.contains("Año"))
        assertTrue(labels.contains("Pista"))
        assertTrue(labels.contains("Portada"))
        assertEquals(
            "Artista: Unknown Artist → Radiohead",
            changes.first { it.field.chipLabel == "Artista" }.format()
        )
        assertEquals("Año: → 1993", changes.first { it.field.chipLabel == "Año" }.format())
        assertEquals("Portada", changes.first { it.field.chipLabel == "Portada" }.format())
    }

    @Test
    fun applyFields_omitsUntickedAndUnchanged() {
        val sameTitle = identifyApplyChanges(
            song(title = "Creep", artist = "Radiohead", album = "Pablo Honey", year = 1993),
            candidate(),
            IdentifyApplyFields.ALL.copy(artwork = false, trackNumber = false)
        )
        assertTrue(sameTitle.none { it.field.chipLabel == "Título" })
        assertTrue(sameTitle.none { it.field.chipLabel == "Artista" })
        assertTrue(sameTitle.none { it.field.chipLabel == "Álbum" })
        assertTrue(sameTitle.none { it.field.chipLabel == "Año" })
        assertTrue(sameTitle.none { it.field.chipLabel == "Portada" })
        assertTrue(sameTitle.none { it.field.chipLabel == "Pista" })
        assertEquals("Sin cambios en los campos elegidos", formatIdentifyApplyChanges(sameTitle))
    }

    @Test
    fun genericCandidateAlbum_isNotAChange() {
        val changes = identifyApplyChanges(
            song(album = "YouTube Music"),
            candidate(album = "YouTube"),
            IdentifyApplyFields.NONE.copy(album = true)
        )
        assertTrue(changes.isEmpty())
    }

    @Test
    fun albumSpellingVariants_areNotAChange() {
        val changes = identifyApplyChanges(
            song(album = "eureka", artist = "Radiohead"),
            candidate(album = "Eureka", artist = "Radiohead"),
            IdentifyApplyFields.NONE.copy(album = true)
        )
        assertTrue(changes.isEmpty())
    }

    @Test
    fun yearAndTrack_onlyWhenCandidateHasValues() {
        val noYear = identifyApplyChanges(
            song(year = 1993, trackNumber = 2),
            candidate(year = 0, trackNumber = 0),
            IdentifyApplyFields.NONE.copy(year = true, trackNumber = true)
        )
        assertTrue(noYear.isEmpty())

        val yearChange = identifyApplyChanges(
            song(year = 1992, trackNumber = 1),
            candidate(year = 1993, trackNumber = 2),
            IdentifyApplyFields.NONE.copy(year = true, trackNumber = true)
        )
        assertEquals("Año: 1992 → 1993", yearChange.first { it.field.chipLabel == "Año" }.format())
        assertEquals("Pista: 1 → 2", yearChange.first { it.field.chipLabel == "Pista" }.format())
    }

    @Test
    fun artwork_onlyWhenCandidateUriDiffers() {
        val same = identifyApplyChanges(
            song(artworkUri = "https://img.example/a.jpg"),
            candidate(artworkUri = "https://img.example/a.jpg"),
            IdentifyApplyFields.NONE.copy(artwork = true)
        )
        assertTrue(same.isEmpty())

        val missingCandidate = identifyApplyChanges(
            song(artworkUri = "file:///local.jpg"),
            candidate(artworkUri = null),
            IdentifyApplyFields.NONE.copy(artwork = true)
        )
        assertTrue(missingCandidate.isEmpty())
    }

    @Test
    fun duration_isNeverListed() {
        val changes = identifyApplyChanges(song(), candidate(year = 0, trackNumber = 0, artworkUri = null), IdentifyApplyFields.ALL)
        assertTrue(changes.none { it.format().contains("duración", ignoreCase = true) })
    }
}
