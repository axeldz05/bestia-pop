package com.bestiapop.android.data.preferences

import com.bestiapop.android.data.model.TrackIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EqualizerSettingsTest {
    @Test
    fun centerFrequenciesForBandCount_returnsExactCount() {
        for (count in 5..12) {
            val freqs = centerFrequenciesForBandCount(count)
            assertEquals("Should return exactly $count frequencies", count, freqs.size)
            // Should be strictly ascending
            for (i in 0 until freqs.size - 1) {
                assertTrue("Frequencies must be strictly ascending", freqs[i] < freqs[i + 1])
            }
        }
    }

    @Test
    fun default5Bands_hasStandardFrequencies() {
        val freqs = centerFrequenciesForBandCount(5)
        assertEquals(listOf(60, 250, 1000, 4000, 16000), freqs)
    }

    @Test
    fun formatEqualizerFrequency_formatsCorrectly() {
        assertEquals("60 Hz", formatEqualizerFrequency(60))
        assertEquals("250 Hz", formatEqualizerFrequency(250))
        assertEquals("1 kHz", formatEqualizerFrequency(1000))
        assertEquals("1.6 kHz", formatEqualizerFrequency(1600))
        assertEquals("4 kHz", formatEqualizerFrequency(4000))
        assertEquals("16 kHz", formatEqualizerFrequency(16000))
    }

    @Test
    fun encodeAndDecodeBandGains_roundtripsAccurately() {
        val original = listOf(-6.0f, -2.5f, 0.0f, 3.5f, 8.0f)
        val encoded = encodeBandGains(original)
        val decoded = decodeBandGains(encoded, 5)

        assertEquals(original.size, decoded.size)
        for (i in original.indices) {
            assertEquals(original[i], decoded[i], 0.01f)
        }
    }

    @Test
    fun decodeBandGains_handlesCountMismatchGracefully() {
        val encoded = encodeBandGains(listOf(2f, 4f, 6f, 8f, 10f))
        // Expecting 8 bands
        val decoded8 = decodeBandGains(encoded, 8)
        assertEquals(8, decoded8.size)
        assertEquals(2f, decoded8[0], 0.01f)
        assertEquals(0f, decoded8[5], 0.01f)

        // Expecting 3 bands (clamped to min 5)
        val decodedMin = decodeBandGains(encoded, 3)
        assertEquals(5, decodedMin.size)
    }

    @Test
    fun evaluatePresetGains_samplesCurvesAcrossFrequencies() {
        val rockPreset = EQUALIZER_PRESETS.first { it.name == "Rock" }
        val freqs5 = centerFrequenciesForBandCount(5)
        val gains5 = evaluatePresetGains(rockPreset, freqs5)

        assertEquals(5, gains5.size)
        // Bass should be boosted
        assertTrue("Rock bass should be boosted", gains5[0] > 0f)
        // Mid should be dipped
        assertTrue("Rock 1kHz mid should be <= 0", gains5[2] <= 0f)
        // Treble should be boosted
        assertTrue("Rock treble should be boosted", gains5[4] > 0f)

        val freqs12 = centerFrequenciesForBandCount(12)
        val gains12 = evaluatePresetGains(rockPreset, freqs12)
        assertEquals(12, gains12.size)
    }

    @Test
    fun adaptGainsForNewBandCount_preservesCustomCurve() {
        val oldGains = listOf(6f, 3f, 0f, 0f, 0f)
        val newGains =
            adaptGainsForNewBandCount(
                currentGains = oldGains,
                currentCount = 5,
                newCount = 10,
                presetName = EQUALIZER_PRESET_CUSTOM,
            )

        assertEquals(10, newGains.size)
        // Low frequencies in new count should also be boosted
        assertTrue("Low frequencies should retain boost", newGains[0] > 0f)
    }

    @Test
    fun createRuleForTarget_createsValidRules() {
        val track =
            TrackIdentity(
                title = "Bohemian Rhapsody",
                artist = "Queen",
                album = "A Night at the Opera",
            )
        val settings =
            EqualizerSettings(
                bandCount = 8,
                bandGainsDb = listOf(4f, 2f, 0f, -1f, 1f, 3f, 4f, 2f),
                presetName = "Rock",
            )

        val songRule = createRuleForTarget(EqualizerTargetType.SONG, track, settings)
        assertNotNull(songRule)
        assertEquals("Bohemian Rhapsody", songRule?.targetName)
        assertEquals(EqualizerTargetType.SONG, songRule?.targetType)
        assertEquals(8, songRule?.bandCount)

        val albumRule = createRuleForTarget(EqualizerTargetType.ALBUM, track, settings)
        assertNotNull(albumRule)
        assertEquals("A Night at the Opera", albumRule?.targetName)
        assertEquals(EqualizerTargetType.ALBUM, albumRule?.targetType)

        val artistRule = createRuleForTarget(EqualizerTargetType.ARTIST, track, settings)
        assertNotNull(artistRule)
        assertEquals("Queen", artistRule?.targetName)
        assertEquals(EqualizerTargetType.ARTIST, artistRule?.targetType)
    }

    @Test
    fun dynamicEqualizerRule_matchesTrackCorrectly() {
        val track =
            TrackIdentity(
                title = "Starman",
                artist = "David Bowie",
                album = "Ziggy Stardust",
            )

        val songRule =
            DynamicEqualizerRule(
                targetType = EqualizerTargetType.SONG,
                targetKey =
                    com.bestiapop.android.domain.util.TrackMatchKeys
                        .matchKey("David Bowie", "Starman"),
                targetName = "Starman",
            )
        assertTrue("Song rule should match Starman by David Bowie", songRule.matchesTrack(track))

        val albumRule =
            DynamicEqualizerRule(
                targetType = EqualizerTargetType.ALBUM,
                targetKey =
                    com.bestiapop.android.domain.util.TrackMatchKeys
                        .matchKey("David Bowie", "Ziggy Stardust"),
                targetName = "Ziggy Stardust",
            )
        assertTrue("Album rule should match album Ziggy Stardust", albumRule.matchesTrack(track))

        val artistRule =
            DynamicEqualizerRule(
                targetType = EqualizerTargetType.ARTIST,
                targetKey =
                    com.bestiapop.android.domain.util.TrackMatchKeys
                        .normalize("David Bowie"),
                targetName = "David Bowie",
            )
        assertTrue("Artist rule should match David Bowie", artistRule.matchesTrack(track))
    }

    @Test
    fun resolveActiveRule_respectsPrecedence() {
        val track =
            TrackIdentity(
                title = "Heroes",
                artist = "David Bowie",
                album = "Heroes",
            )

        val songRule =
            DynamicEqualizerRule(
                targetType = EqualizerTargetType.SONG,
                targetKey =
                    com.bestiapop.android.domain.util.TrackMatchKeys
                        .matchKey("David Bowie", "Heroes"),
                targetName = "Heroes (Song)",
            )
        val albumRule =
            DynamicEqualizerRule(
                targetType = EqualizerTargetType.ALBUM,
                targetKey =
                    com.bestiapop.android.domain.util.TrackMatchKeys
                        .matchKey("David Bowie", "Heroes"),
                targetName = "Heroes (Album)",
            )
        val artistRule =
            DynamicEqualizerRule(
                targetType = EqualizerTargetType.ARTIST,
                targetKey =
                    com.bestiapop.android.domain.util.TrackMatchKeys
                        .normalize("David Bowie"),
                targetName = "David Bowie (Artist)",
            )

        // When all 3 are present, Song has highest priority
        val resolvedAll = resolveActiveRule(track, listOf(artistRule, albumRule, songRule))
        assertEquals("Heroes (Song)", resolvedAll?.targetName)

        // When Song is absent, Album has priority over Artist
        val resolvedAlbumArtist = resolveActiveRule(track, listOf(artistRule, albumRule))
        assertEquals("Heroes (Album)", resolvedAlbumArtist?.targetName)

        // When only Artist is present, Artist is chosen
        val resolvedArtist = resolveActiveRule(track, listOf(artistRule))
        assertEquals("David Bowie (Artist)", resolvedArtist?.targetName)
    }

    @Test
    fun encodeAndDecodeDynamicRules_roundtripsCorrectly() {
        val rules =
            listOf(
                DynamicEqualizerRule(
                    id = "rule-1",
                    targetType = EqualizerTargetType.SONG,
                    targetKey = "queen|bohemian rhapsody",
                    targetName = "Bohemian Rhapsody",
                    targetArtist = "Queen",
                    bandCount = 5,
                    bandGainsDb = listOf(4f, 2f, 0f, -1f, 3f),
                    presetName = "Rock",
                ),
                DynamicEqualizerRule(
                    id = "rule-2",
                    targetType = EqualizerTargetType.ARTIST,
                    targetKey = "daft punk",
                    targetName = "Daft Punk",
                    targetArtist = "",
                    bandCount = 10,
                    bandGainsDb = List(10) { 2.5f },
                    presetName = "Electrónica",
                ),
            )

        val encoded = encodeDynamicRules(rules)
        val decoded = decodeDynamicRules(encoded)

        assertEquals(2, decoded.size)
        assertEquals("rule-1", decoded[0].id)
        assertEquals(EqualizerTargetType.SONG, decoded[0].targetType)
        assertEquals("Bohemian Rhapsody", decoded[0].targetName)
        assertEquals("Queen", decoded[0].targetArtist)
        assertEquals(5, decoded[0].bandCount)
        assertEquals(4f, decoded[0].bandGainsDb[0], 0.01f)

        assertEquals("rule-2", decoded[1].id)
        assertEquals(EqualizerTargetType.ARTIST, decoded[1].targetType)
        assertEquals(10, decoded[1].bandCount)
        assertEquals(2.5f, decoded[1].bandGainsDb[0], 0.01f)
    }
}
