package com.bestiapop.android.data.network

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Test

class CatalogTrackNumberParseTest {

    @Test
    fun parseDeezerSearchTracks_readsPositionAndDisc() {
        val data = JSONArray(
            """
            [{
              "id": "1",
              "title": "Harder Better Faster Stronger",
              "duration": 224,
              "track_position": 4,
              "disk_number": 2,
              "artist": {"name": "Daft Punk"},
              "album": {"title": "Discovery", "cover_xl": "", "cover_big": ""}
            }]
            """.trimIndent()
        )
        val tracks = MetadataFetcher.parseDeezerSearchTracks(data)
        assertEquals(1, tracks.size)
        assertEquals(2004, tracks[0].trackNumber)
    }

    @Test
    fun parseItunesSongResults_readsTrackAndDisc() {
        val results = JSONArray(
            """
            [{
              "trackId": "99",
              "trackName": "Creep",
              "artistName": "Radiohead",
              "collectionName": "Pablo Honey",
              "artworkUrl100": "",
              "trackTimeMillis": 238000,
              "trackNumber": 4,
              "discNumber": 1
            }]
            """.trimIndent()
        )
        val tracks = MetadataFetcher.parseItunesSongResults(results)
        assertEquals(1, tracks.size)
        assertEquals(4, tracks[0].trackNumber)
    }

    @Test
    fun parseItunesAlbumLookupTracks_ignoresCollectionAndParsesTracks() {
        val results = JSONArray(
            """
            [
              {
                "wrapperType": "collection",
                "collectionId": 12345,
                "collectionName": "Magic Disk",
                "artistName": "Asian Kung-Fu Generation"
              },
              {
                "wrapperType": "track",
                "trackId": 101,
                "trackName": "Love Song of New Century",
                "artistName": "Asian Kung-Fu Generation",
                "collectionName": "Magic Disk",
                "trackNumber": 1,
                "discNumber": 1,
                "trackTimeMillis": 240000
              },
              {
                "wrapperType": "track",
                "trackId": 102,
                "trackName": "Magic Disk",
                "artistName": "Asian Kung-Fu Generation",
                "collectionName": "Magic Disk",
                "trackNumber": 2,
                "discNumber": 1,
                "trackTimeMillis": 210000
              }
            ]
            """.trimIndent()
        )
        val candidates = MetadataFetcher.parseItunesAlbumLookupTracks(
            results = results,
            expectedAlbumTitle = "Magic Disk",
            expectedArtist = "Asian Kung-Fu Generation"
        )
        assertEquals(2, candidates.size)
        assertEquals("Love Song of New Century", candidates[0].title)
        assertEquals(1, candidates[0].trackNumber)
        assertEquals("Magic Disk", candidates[1].title)
        assertEquals(2, candidates[1].trackNumber)
    }

    @Test
    fun parseDeezerAlbumTracks_readsTrackPositionsAndSetsAlbum() {
        val data = JSONArray(
            """
            [
              {
                "id": 201,
                "title": "Love Song of New Century",
                "track_position": 1,
                "disk_number": 1,
                "duration": 240,
                "artist": { "name": "ASIAN KUNG-FU GENERATION" }
              }
            ]
            """.trimIndent()
        )
        val candidates = MetadataFetcher.parseDeezerAlbumTracks(
            data = data,
            albumTitle = "Magic Disk",
            artistName = "Asian Kung-Fu Generation"
        )
        assertEquals(1, candidates.size)
        assertEquals("Love Song of New Century", candidates[0].title)
        assertEquals(1, candidates[0].trackNumber)
        assertEquals("Magic Disk", candidates[0].album)
    }

    @Test
    fun mergeAlbumTrackCandidates_fillsGapsWithoutDuplicateRows() {
        val deezerData = JSONArray(
            """
            [
              {
                "id": 201,
                "title": "Love Song of New Century",
                "track_position": 1,
                "disk_number": 1,
                "duration": 240,
                "artist": { "name": "ASIAN KUNG-FU GENERATION" }
              }
            ]
            """.trimIndent()
        )
        val itunesResults = JSONArray(
            """
            [
              {
                "wrapperType": "track",
                "trackId": 101,
                "trackName": "Love Song of New Century",
                "artistName": "Asian Kung-Fu Generation",
                "collectionName": "Magic Disk",
                "trackNumber": 1,
                "discNumber": 1
              },
              {
                "wrapperType": "track",
                "trackId": 102,
                "trackName": "Magic Disk",
                "artistName": "Asian Kung-Fu Generation",
                "collectionName": "Magic Disk",
                "trackNumber": 2,
                "discNumber": 1
              }
            ]
            """.trimIndent()
        )
        val deezerCandidates = MetadataFetcher.parseDeezerAlbumTracks(deezerData, "Magic Disk", "Asian Kung-Fu Generation")
        val itunesCandidates = MetadataFetcher.parseItunesAlbumLookupTracks(itunesResults, "Magic Disk", "Asian Kung-Fu Generation")

        val merged = MetadataFetcher.mergeAlbumTrackCandidates(deezerCandidates, itunesCandidates)

        // Must have exactly 2 tracks, not 3 (no duplicate row for track 1)
        assertEquals(2, merged.size)
        assertEquals(1, merged[0].trackNumber)
        assertEquals("Love Song of New Century", merged[0].title)
        // Track 1 merged alternate candidate source
        assertEquals(2, merged[0].candidates.size)

        // Track 2 filled from iTunes
        assertEquals(2, merged[1].trackNumber)
        assertEquals("Magic Disk", merged[1].title)
        assertEquals(1, merged[1].candidates.size)
    }
}
