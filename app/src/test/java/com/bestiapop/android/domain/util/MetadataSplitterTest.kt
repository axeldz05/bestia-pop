package com.bestiapop.android.domain.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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

    // --- Identity Key tests ---

    @Test
    fun artistIdentityKey_foldsDiacriticsAndCaseAndConnectors() {
        // Case + diacritics: "Diego Sáenz" and "Diego Saenz" share the same key
        assertEquals(
            MetadataSplitter.artistIdentityKey("Diego Sáenz"),
            MetadataSplitter.artistIdentityKey("Diego Saenz")
        )
        // "Raúl Carnota" vs "Raul Carnota"
        assertEquals(
            MetadataSplitter.artistIdentityKey("Raúl Carnota"),
            MetadataSplitter.artistIdentityKey("Raul Carnota")
        )
        // ALL CAPS vs mixed case
        assertEquals(
            MetadataSplitter.artistIdentityKey("ASIAN KUNG-FU GENERATION"),
            MetadataSplitter.artistIdentityKey("Asian Kung-Fu Generation")
        )
    }

    @Test
    fun artistIdentityKey_unifiesMixedScriptNames() {
        // Mixed script (CJK + Latin) → Latin portion becomes canonical key
        assertEquals(
            MetadataSplitter.artistIdentityKey("Elephant Gym 大象體操"),
            MetadataSplitter.artistIdentityKey("Elephant Gym")
        )
        // Simplified vs traditional Chinese with same Latin part → same key
        assertEquals(
            MetadataSplitter.artistIdentityKey("Elephant Gym 大象體操"),
            MetadataSplitter.artistIdentityKey("Elephant Gym 大象体操")
        )
    }

    @Test
    fun artistIdentityKey_keepsPureNonLatinDistinct() {
        // Pure CJK names without Latin content keep their original-script key
        // They cannot dynamically resolve to a Latin romanization
        assertNotEquals(
            MetadataSplitter.artistIdentityKey("きのこ帝国"),
            MetadataSplitter.artistIdentityKey("Kinokoteikoku")
        )
    }

    @Test
    fun genreIdentityKey_doesNotFalselyMergeBlues() {
        // "blues" must NOT be stemmed to "blue"
        assertNotEquals(
            MetadataSplitter.genreIdentityKey("Blues"),
            MetadataSplitter.genreIdentityKey("Blue")
        )
        // But "Blues Rock" and "Blues-Rock" should still unify via connector normalization
        assertEquals(
            MetadataSplitter.genreIdentityKey("Blues Rock"),
            MetadataSplitter.genreIdentityKey("Blues-Rock")
        )
    }

    @Test
    fun genreIdentityKey_normalizesConnectorsAndOrderAndCase() {
        // "Pop Rock" vs "Rock & Pop" vs "pop-rock" vs "rock pop"
        val popRockKey = MetadataSplitter.genreIdentityKey("Pop Rock")
        assertEquals(popRockKey, MetadataSplitter.genreIdentityKey("Rock & Pop"))
        assertEquals(popRockKey, MetadataSplitter.genreIdentityKey("pop-rock"))
        assertEquals(popRockKey, MetadataSplitter.genreIdentityKey("rock pop"))

        // Lemmatization: "Soundtracks" → "Soundtrack"
        assertEquals(
            MetadataSplitter.genreIdentityKey("Soundtrack"),
            MetadataSplitter.genreIdentityKey("Soundtracks")
        )
        assertEquals(
            MetadataSplitter.genreIdentityKey("Electronic"),
            MetadataSplitter.genreIdentityKey("Electronica")
        )
    }

    @Test
    fun genreIdentityKey_caseInsensitive() {
        assertEquals(
            MetadataSplitter.genreIdentityKey("Alternative Rock"),
            MetadataSplitter.genreIdentityKey("alternative rock")
        )
        assertEquals(
            MetadataSplitter.genreIdentityKey("Blues Rock"),
            MetadataSplitter.genreIdentityKey("Blues-Rock")
        )
    }

    // --- Display name selector tests ---

    @Test
    fun preferredArtistDisplayName_prefersOriginalScript() {
        assertEquals(
            "きのこ帝国",
            MetadataSplitter.preferredArtistDisplayName(listOf("Kinokoteikoku", "きのこ帝国"))
        )
        assertEquals(
            "アトラスサウンドチーム",
            MetadataSplitter.preferredArtistDisplayName(listOf("Atlus Sound Team", "アトラスサウンドチーム"))
        )
        assertEquals(
            "Elephant Gym 大象體操",
            MetadataSplitter.preferredArtistDisplayName(listOf("Elephant Gym", "Elephant Gym 大象體操"))
        )
    }

    @Test
    fun preferredArtistDisplayName_prefersDiacriticsOverPlain() {
        assertEquals(
            "Diego Sáenz",
            MetadataSplitter.preferredArtistDisplayName(listOf("Diego Saenz", "Diego Sáenz"))
        )
        assertEquals(
            "Raúl Carnota",
            MetadataSplitter.preferredArtistDisplayName(listOf("Raul Carnota", "Raúl Carnota"))
        )
    }

    @Test
    fun preferredArtistDisplayName_penalizesAllCaps() {
        assertEquals(
            "Asian Kung-Fu Generation",
            MetadataSplitter.preferredArtistDisplayName(
                listOf("ASIAN KUNG-FU GENERATION", "Asian Kung-Fu Generation")
            )
        )
    }

    @Test
    fun preferredGenreDisplayName_appliesTitleCase() {
        assertEquals("Pop Rock", MetadataSplitter.preferredGenreDisplayName(listOf("pop rock")))
        assertEquals("Alternative Rock", MetadataSplitter.preferredGenreDisplayName(listOf("alternative rock")))
    }

    @Test
    fun preferredGenreDisplayName_prefersSingularOverPlural() {
        assertEquals(
            "Soundtrack",
            MetadataSplitter.preferredGenreDisplayName(listOf("Soundtracks", "Soundtrack"))
        )
    }

    @Test
    fun preferredGenreDisplayName_prefersSpaceSeparatedOverConnector() {
        assertEquals(
            "Pop Rock",
            MetadataSplitter.preferredGenreDisplayName(listOf("Rock & Pop", "Pop Rock"))
        )
        assertEquals(
            "Blues Rock",
            MetadataSplitter.preferredGenreDisplayName(listOf("Blues-Rock", "Blues Rock"))
        )
    }

    @Test
    fun preferredArtistDisplayName_prefersTitleCaseOverLowercase() {
        assertEquals(
            "Asian Kung-Fu Generation",
            MetadataSplitter.preferredArtistDisplayName(
                listOf("asian kung-fu generation", "Asian Kung-Fu Generation")
            )
        )
    }

    @Test
    fun preferredGenreDisplayName_prefersSingularEvenWhenPluralIsTitleCase() {
        assertEquals(
            "Soundtrack",
            MetadataSplitter.preferredGenreDisplayName(listOf("soundtrack", "Soundtracks"))
        )
    }

    @Test
    fun genreIdentityKey_handlesSlashesAndConnectorsWithoutSpaces() {
        assertEquals(
            MetadataSplitter.genreIdentityKey("Pop Rock"),
            MetadataSplitter.genreIdentityKey("Pop/Rock")
        )
        assertEquals(
            MetadataSplitter.genreIdentityKey("Pop Rock"),
            MetadataSplitter.genreIdentityKey("Pop&Rock")
        )
    }

    @Test
    fun artistIdentityKey_handlesConnectorsWithoutSpaces() {
        assertEquals(
            MetadataSplitter.artistIdentityKey("Simon & Garfunkel"),
            MetadataSplitter.artistIdentityKey("Simon&Garfunkel")
        )
    }

    // --- Semicolon header dedup ---

    @Test
    fun splitArtists_discardsRedundantCompositeHeader() {
        // Real-world case: "Walter Ríos, Ulises Butrón & Popi Spatocco;Ulises Butrón;Popi Spatocco;Walter Ríos"
        val result = MetadataSplitter.splitArtists(
            "Walter Ríos, Ulises Butrón & Popi Spatocco;Ulises Butrón;Popi Spatocco;Walter Ríos"
        )
        // The composite header should be discarded, keeping only the individual artists
        assertEquals(3, result.size)
        assertEquals(
            setOf("Ulises Butrón", "Popi Spatocco", "Walter Ríos"),
            result.toSet()
        )
    }
}
