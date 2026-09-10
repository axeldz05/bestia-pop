package com.bestiapop.android.domain.util

import org.junit.Assert.assertEquals
import org.junit.Test

class MetadataSplitterTest {

    @Test
    fun splitArtists_preservesBandsAsSingleEntities() {
        // MASS OF THE FERMENTING DREGS is a Japanese rock band, not a compound artist
        assertEquals(
            listOf("MASS OF THE FERMENTING DREGS"),
            MetadataSplitter.splitArtists("MASS OF THE FERMENTING DREGS")
        )
        // Bands with punctuation preserved naturally without hardcoded whitelists
        assertEquals(
            listOf("Simon & Garfunkel"),
            MetadataSplitter.splitArtists("Simon & Garfunkel")
        )
        assertEquals(
            listOf("Earth, Wind & Fire"),
            MetadataSplitter.splitArtists("Earth, Wind & Fire")
        )
        assertEquals(
            listOf("Tyler, The Creator"),
            MetadataSplitter.splitArtists("Tyler, The Creator")
        )
        assertEquals(
            listOf("AC/DC"),
            MetadataSplitter.splitArtists("AC/DC")
        )
        assertEquals(
            listOf("Florence + The Machine"),
            MetadataSplitter.splitArtists("Florence + The Machine")
        )
        assertEquals(
            listOf("Tom Petty and the Heartbreakers"),
            MetadataSplitter.splitArtists("Tom Petty and the Heartbreakers")
        )
    }

    @Test
    fun splitArtists_splitsCollaborationsDynamicallyUsingKnownArtists() {
        val known = listOf("Queen", "David Bowie", "Daft Punk", "The Weeknd")

        // Dynamically detected because both Queen and David Bowie exist as known artists
        assertEquals(
            listOf("Queen", "David Bowie"),
            MetadataSplitter.splitArtists("Queen & David Bowie", knownArtists = known)
        )

        // Band is preserved even when known artists list is passed because its parts don't match
        assertEquals(
            listOf("Simon & Garfunkel"),
            MetadataSplitter.splitArtists("Simon & Garfunkel", knownArtists = known)
        )
        assertEquals(
            listOf("MASS OF THE FERMENTING DREGS"),
            MetadataSplitter.splitArtists("MASS OF THE FERMENTING DREGS", knownArtists = known)
        )
    }

    @Test
    fun splitArtists_splitsExplicitCollaborationSyntax() {
        assertEquals(
            listOf("Daft Punk", "Pharrell Williams"),
            MetadataSplitter.splitArtists("Daft Punk feat. Pharrell Williams")
        )
        assertEquals(
            listOf("Gorillaz", "Del the Funky Homosapien"),
            MetadataSplitter.splitArtists("Gorillaz ft. Del the Funky Homosapien")
        )
        assertEquals(
            listOf("Daft Punk", "Pharrell Williams", "Nile Rodgers"),
            MetadataSplitter.splitArtists("Daft Punk feat. Pharrell Williams & Nile Rodgers")
        )
        assertEquals(
            listOf("Coldplay", "BTS"),
            MetadataSplitter.splitArtists("Coldplay; BTS")
        )
        assertEquals(
            listOf("Artist A", "Artist B"),
            MetadataSplitter.splitArtists("Artist A / Artist B")
        )
        assertEquals(
            listOf("MASS OF THE FERMENTING DREGS", "Guest Artist"),
            MetadataSplitter.splitArtists("MASS OF THE FERMENTING DREGS feat. Guest Artist")
        )
    }

    @Test
    fun splitGenres_splitsDelimitedGenres() {
        assertEquals(
            listOf("Indie Rock", "Shoegaze", "Post-Hardcore"),
            MetadataSplitter.splitGenres("Indie Rock / Shoegaze, Post-Hardcore")
        )
        assertEquals(
            listOf("Electronic", "Synthpop"),
            MetadataSplitter.splitGenres("Electronic; Synthpop")
        )
        // Preserves Rock & Roll and R&B without breaking on &
        assertEquals(
            listOf("Rock & Roll"),
            MetadataSplitter.splitGenres("Rock & Roll")
        )
    }

    @Test
    fun splitArtistsAndGenres_handlesEmptyAndWhitespace() {
        assertEquals(MetadataSplitter.splitArtists(""), emptyList<String>())
        assertEquals(MetadataSplitter.splitArtists("   "), emptyList<String>())
        assertEquals(MetadataSplitter.splitArtists("Unknown Artist"), emptyList<String>())
        assertEquals(MetadataSplitter.splitGenres(""), emptyList<String>())
        assertEquals(MetadataSplitter.splitGenres("Unknown Genre"), emptyList<String>())
        assertEquals(MetadataSplitter.splitGenres("Music"), emptyList<String>())
    }
}
