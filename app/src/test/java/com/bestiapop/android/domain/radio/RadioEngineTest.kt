package com.bestiapop.android.domain.radio

import com.bestiapop.android.data.model.PlayableItem
import com.bestiapop.android.data.model.Song
import com.bestiapop.android.data.model.toPlayable
import com.bestiapop.android.domain.usecase.MatchListenBrainzTracksUseCase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class LocalMetadataRadioTest {

    private fun song(
        id: Long,
        title: String,
        artist: String,
        album: String = "Album",
        genre: String = "Rock",
        year: Int = 2000
    ) = Song(
        id = id,
        uriString = "file:///song/$id",
        title = title,
        artist = artist,
        album = album,
        genre = genre,
        year = year,
        durationMs = 180_000L
    )

    @Test
    fun sameArtistScoresHigherThanGenreOnly() {
        val seed = song(1, "Seed", "Artist A", genre = "Rock", year = 2000).toPlayable()
        val sameArtist = song(2, "Other", "Artist A", genre = "Jazz", year = 1990)
        val sameGenre = song(3, "Other2", "Artist B", genre = "Rock", year = 1990)
        val radio = LocalMetadataRadio(random = Random(0))

        val result = radio.suggest(
            seed = seed,
            library = listOf(sameArtist, sameGenre, seed.song),
            excludeKeys = emptySet(),
            limit = 10
        )

        assertEquals("Other", result.first().title)
        assertTrue(result.any { it.title == "Other2" })
    }

    @Test
    fun excludesSeedAndCooldownKeys() {
        val seed = song(1, "Seed", "Artist A").toPlayable()
        val other = song(2, "Keep", "Artist A")
        val cooldown = song(3, "Skip", "Artist A")
        val radio = LocalMetadataRadio(random = Random(0))
        val exclude = setOf(MatchListenBrainzTracksUseCase.matchKey("Artist A", "Skip"))

        val result = radio.suggest(
            seed = seed,
            library = listOf(seed.song, other, cooldown),
            excludeKeys = exclude,
            limit = 10
        )

        assertEquals(1, result.size)
        assertEquals("Keep", result.single().title)
        assertFalse(result.any { it.title == "Seed" })
        assertFalse(result.any { it.title == "Skip" })
    }

    @Test
    fun capsTracksPerAlbum() {
        val seed = song(1, "Seed", "Artist A", album = "Other").toPlayable()
        val albumTracks = (2L..6L).map {
            song(it, "Track$it", "Artist A", album = "Same Album")
        }
        val radio = LocalMetadataRadio(random = Random(0), maxPerAlbum = 2)

        val result = radio.suggest(
            seed = seed,
            library = albumTracks + seed.song,
            excludeKeys = emptySet(),
            limit = 10
        )

        val fromSame = result.count { it.song.album == "Same Album" }
        assertEquals(2, fromSame)
    }
}

class RadioEngineTest {

    private fun song(id: Long, title: String, artist: String) = Song(
        id = id,
        uriString = "file:///song/$id",
        title = title,
        artist = artist,
        album = "Album",
        genre = "Rock",
        year = 2000,
        durationMs = 180_000L
    )

    private fun failingLb() = ListenBrainzRadio(
        lookupMetadata = { _, _, _ ->
            com.bestiapop.android.data.listenbrainz.LbApiResult.Failure("offline")
        },
        fetchLbRadio = { _, _, _ ->
            com.bestiapop.android.data.listenbrainz.LbApiResult.Failure("offline")
        },
        fetchRecordingMetadata = { _, _ ->
            com.bestiapop.android.data.listenbrainz.LbApiResult.Failure("offline")
        }
    )

    private fun lbWithLibraryMatchAndRemote() = ListenBrainzRadio(
        lookupMetadata = { _, _, _ ->
            com.bestiapop.android.data.listenbrainz.LbApiResult.Success(
                com.bestiapop.android.data.listenbrainz.LbMetadataLookup(
                    artistMbids = listOf("artist-mbid"),
                    recordingMbid = null,
                    artistCreditName = "Artist A",
                    recordingName = "Seed"
                )
            )
        },
        fetchLbRadio = { _, _, _ ->
            com.bestiapop.android.data.listenbrainz.LbApiResult.Success(
                listOf(
                    com.bestiapop.android.data.listenbrainz.LbRadioRecording(
                        recordingMbid = "rec-1",
                        similarArtistMbid = null,
                        similarArtistName = "Artist A"
                    ),
                    com.bestiapop.android.data.listenbrainz.LbRadioRecording(
                        recordingMbid = "rec-2",
                        similarArtistMbid = null,
                        similarArtistName = "Remote Artist"
                    )
                )
            )
        },
        fetchRecordingMetadata = { _, _ ->
            com.bestiapop.android.data.listenbrainz.LbApiResult.Success(
                mapOf(
                    "rec-1" to com.bestiapop.android.data.listenbrainz.LbRecordingMetadata(
                        recordingMbid = "rec-1",
                        title = "B",
                        artist = "Artist A"
                    ),
                    "rec-2" to com.bestiapop.android.data.listenbrainz.LbRecordingMetadata(
                        recordingMbid = "rec-2",
                        title = "Remote Song",
                        artist = "Remote Artist"
                    )
                )
            )
        }
    )

    @Test
    fun knownModeDoesNotCallListenBrainz() = runBlocking {
        var lbCalls = 0
        val lb = ListenBrainzRadio(
            lookupMetadata = { _, _, _ ->
                lbCalls++
                com.bestiapop.android.data.listenbrainz.LbApiResult.Failure("should not call")
            },
            fetchLbRadio = { _, _, _ ->
                lbCalls++
                com.bestiapop.android.data.listenbrainz.LbApiResult.Failure("should not call")
            },
            fetchRecordingMetadata = { _, _ ->
                lbCalls++
                com.bestiapop.android.data.listenbrainz.LbApiResult.Failure("should not call")
            }
        )
        val engine = RadioEngine(
            localRadio = LocalMetadataRadio(random = Random(1)),
            listenBrainzRadio = lb
        )
        val seed = song(1, "Seed", "Artist A").toPlayable()
        val library = listOf(seed.song, song(2, "B", "Artist A"), song(3, "C", "Artist A"))

        val result = engine.suggest(
            seed = seed,
            library = library,
            mode = RadioMode.KNOWN,
            excludeKeys = emptySet(),
            limit = 10,
            lbToken = "token",
            lbAvailable = true
        )

        assertEquals(0, lbCalls)
        assertTrue(result.items.isNotEmpty())
        assertTrue(result.items.all { it is PlayableItem.Local })
        assertFalse(result.usedOnlineDiscovery)
        assertFalse(result.onlineDiscoveryFailed)
    }

    @Test
    fun newModeReturnsEmptyWhenOnlineProvidersFail() = runBlocking {
        val engine = RadioEngine(
            localRadio = LocalMetadataRadio(random = Random(2)),
            listenBrainzRadio = failingLb()
        )
        val seed = song(1, "Seed", "Artist A").toPlayable()
        val library = listOf(seed.song, song(2, "B", "Artist A"))

        val result = engine.suggest(
            seed = seed,
            library = library,
            mode = RadioMode.NEW,
            excludeKeys = emptySet(),
            limit = 5,
            lbToken = "token",
            lbAvailable = true,
            networkAvailable = true
        )

        assertTrue(result.items.isEmpty())
        assertFalse(result.usedOnlineDiscovery)
        assertTrue(result.onlineDiscoveryFailed)
    }

    @Test
    fun newModeSkipsLibraryMatchesKeepsOnlyRemotes() = runBlocking {
        val engine = RadioEngine(
            localRadio = LocalMetadataRadio(random = Random(3)),
            listenBrainzRadio = lbWithLibraryMatchAndRemote()
        )
        val seed = song(1, "Seed", "Artist A").toPlayable()
        val library = listOf(seed.song, song(2, "B", "Artist A"), song(3, "C", "Artist A"))

        val result = engine.suggest(
            seed = seed,
            library = library,
            mode = RadioMode.NEW,
            excludeKeys = emptySet(),
            limit = 10,
            lbToken = "token",
            lbAvailable = true,
            networkAvailable = true
        )

        assertTrue(result.items.all { it is PlayableItem.Remote })
        assertFalse(result.items.any { it.title == "B" })
        assertTrue(result.items.any { it.title == "Remote Song" })
        assertTrue(result.usedOnlineDiscovery)
        assertFalse(result.onlineDiscoveryFailed)
    }

    @Test
    fun bothInterleavesRemoteThenLocal() = runBlocking {
        val engine = RadioEngine(
            localRadio = LocalMetadataRadio(random = Random(3)),
            listenBrainzRadio = lbWithLibraryMatchAndRemote()
        )
        val seed = song(1, "Seed", "Artist A").toPlayable()
        val library = listOf(seed.song, song(2, "B", "Artist A"), song(3, "C", "Artist A"))

        val result = engine.suggest(
            seed = seed,
            library = library,
            mode = RadioMode.BOTH,
            excludeKeys = emptySet(),
            limit = 4,
            lbToken = "token",
            lbAvailable = true,
            networkAvailable = true
        )

        assertTrue(result.items.size >= 2)
        assertTrue(result.items.first() is PlayableItem.Remote)
        val types = result.items.map { it is PlayableItem.Remote }
        // When both pools have items: R, L, R, L…
        if (result.items.count { it is PlayableItem.Remote } >= 1 &&
            result.items.count { it is PlayableItem.Local } >= 1
        ) {
            assertTrue(types[0])
            assertFalse(types[1])
        }
        assertTrue(result.usedOnlineDiscovery)
    }

    @Test
    fun bothFallsBackToLocalWhenOnlineFails() = runBlocking {
        val engine = RadioEngine(
            localRadio = LocalMetadataRadio(random = Random(2)),
            listenBrainzRadio = failingLb()
        )
        val seed = song(1, "Seed", "Artist A").toPlayable()
        val library = listOf(seed.song, song(2, "B", "Artist A"))

        val result = engine.suggest(
            seed = seed,
            library = library,
            mode = RadioMode.BOTH,
            excludeKeys = emptySet(),
            limit = 5,
            lbToken = "token",
            lbAvailable = true,
            networkAvailable = true
        )

        assertEquals(1, result.items.size)
        assertEquals("B", result.items.single().title)
        assertTrue(result.items.all { it is PlayableItem.Local })
        assertFalse(result.usedOnlineDiscovery)
    }

    @Test
    fun newFillsWithCfWhenLbInsufficient() = runBlocking {
        val lb = ListenBrainzRadio(
            lookupMetadata = { _, _, _ ->
                com.bestiapop.android.data.listenbrainz.LbApiResult.Success(
                    com.bestiapop.android.data.listenbrainz.LbMetadataLookup(
                        artistMbids = listOf("artist-mbid"),
                        recordingMbid = null,
                        artistCreditName = "Artist A",
                        recordingName = "Seed"
                    )
                )
            },
            fetchLbRadio = { _, _, _ ->
                com.bestiapop.android.data.listenbrainz.LbApiResult.Success(
                    listOf(
                        com.bestiapop.android.data.listenbrainz.LbRadioRecording(
                            recordingMbid = "lb-rec",
                            similarArtistMbid = null,
                            similarArtistName = "LB Artist"
                        )
                    )
                )
            },
            fetchRecordingMetadata = { _, _ ->
                com.bestiapop.android.data.listenbrainz.LbApiResult.Success(
                    mapOf(
                        "lb-rec" to com.bestiapop.android.data.listenbrainz.LbRecordingMetadata(
                            recordingMbid = "lb-rec",
                            title = "LB Song",
                            artist = "LB Artist"
                        )
                    )
                )
            }
        )
        val cf = CfRecommendationsRadio(
            fetchCf = { _, _, _, _, _ ->
                com.bestiapop.android.data.listenbrainz.LbApiResult.Success(
                    com.bestiapop.android.data.listenbrainz.CfRecommendationsPayload(
                        userName = "user",
                        recordings = listOf(
                            com.bestiapop.android.data.listenbrainz.CfRecommendedRecording("cf-1", 9.0),
                            com.bestiapop.android.data.listenbrainz.CfRecommendedRecording("cf-2", 8.0)
                        )
                    )
                )
            },
            fetchRecordingMetadata = { _, _ ->
                com.bestiapop.android.data.listenbrainz.LbApiResult.Success(
                    mapOf(
                        "cf-1" to com.bestiapop.android.data.listenbrainz.LbRecordingMetadata(
                            recordingMbid = "cf-1",
                            title = "CF One",
                            artist = "CF Artist"
                        ),
                        "cf-2" to com.bestiapop.android.data.listenbrainz.LbRecordingMetadata(
                            recordingMbid = "cf-2",
                            title = "CF Two",
                            artist = "CF Artist"
                        )
                    )
                )
            }
        )
        val engine = RadioEngine(
            localRadio = LocalMetadataRadio(random = Random(4)),
            listenBrainzRadio = lb,
            cfRecommendationsRadio = cf
        )
        val seed = song(1, "Seed", "Artist A").toPlayable()
        val library = listOf(seed.song)

        val result = engine.suggest(
            seed = seed,
            library = library,
            mode = RadioMode.NEW,
            excludeKeys = emptySet(),
            limit = 3,
            lbToken = "token",
            lbAvailable = true,
            lbUsername = "user",
            networkAvailable = true
        )

        assertTrue(result.items.all { it is PlayableItem.Remote })
        assertTrue(result.items.any { it.title == "LB Song" })
        assertTrue(result.items.any { it.title.startsWith("CF ") })
        assertEquals(3, result.items.size)
        assertTrue(result.usedOnlineDiscovery)
    }

    @Test
    fun newWithoutLbFillsWithDeezer() = runBlocking {
        val deezer = DeezerSimilarRadio(
            resolveArtistId = { 7L },
            fetchArtistRadio = {
                listOf(
                    com.bestiapop.android.data.model.TrackIdentity("Deezer Hit", "Neighbor")
                )
            },
            fetchRelatedArtistIds = { _, _ -> emptyList() },
            fetchArtistTop = { _, _ -> emptyList() },
            fetchItunesArtistSongs = { _, _ -> emptyList() }
        )
        val engine = RadioEngine(
            localRadio = LocalMetadataRadio(random = Random(5)),
            listenBrainzRadio = failingLb(),
            similarProviders = listOf(deezer)
        )
        val seed = song(1, "Seed", "Artist A").toPlayable()
        val library = listOf(seed.song, song(2, "Local Only", "Artist A"))

        val result = engine.suggest(
            seed = seed,
            library = library,
            mode = RadioMode.NEW,
            excludeKeys = emptySet(),
            limit = 5,
            lbToken = null,
            lbAvailable = false,
            networkAvailable = true
        )

        assertTrue(result.items.all { it is PlayableItem.Remote })
        assertTrue(result.items.any { it.title == "Deezer Hit" })
        assertTrue(result.usedOnlineDiscovery)
        assertFalse(result.onlineDiscoveryFailed)
    }

    @Test
    fun newOmitsDeezerTracksAlreadyInLibrary() = runBlocking {
        val deezer = DeezerSimilarRadio(
            resolveArtistId = { 1L },
            fetchArtistRadio = {
                listOf(
                    com.bestiapop.android.data.model.TrackIdentity("In Library", "Artist A"),
                    com.bestiapop.android.data.model.TrackIdentity("Brand New", "Artist B")
                )
            },
            fetchRelatedArtistIds = { _, _ -> emptyList() },
            fetchArtistTop = { _, _ -> emptyList() },
            fetchItunesArtistSongs = { _, _ -> emptyList() }
        )
        val engine = RadioEngine(
            similarProviders = listOf(deezer)
        )
        val seed = song(1, "Seed", "Artist A").toPlayable()
        val library = listOf(seed.song, song(2, "In Library", "Artist A"))

        val result = engine.suggest(
            seed = seed,
            library = library,
            mode = RadioMode.NEW,
            excludeKeys = emptySet(),
            limit = 5,
            networkAvailable = true
        )

        assertEquals(listOf("Brand New"), result.items.map { it.title })
    }

    @Test
    fun coPlaylistBoostRanksCohortHigher() {
        val seedSong = Song(
            id = 1,
            uriString = "file:///song/1",
            title = "Seed",
            artist = "Artist A",
            album = "Album",
            genre = "Rock",
            year = 2000,
            durationMs = 180_000L
        )
        val cohort = Song(
            id = 2,
            uriString = "file:///song/2",
            title = "Cohort",
            artist = "Other Artist",
            album = "X",
            genre = "Jazz",
            year = 1990,
            durationMs = 180_000L
        )
        val sameGenre = Song(
            id = 3,
            uriString = "file:///song/3",
            title = "GenreMate",
            artist = "Other",
            album = "Y",
            genre = "Rock",
            year = 1990,
            durationMs = 180_000L
        )
        val radio = LocalMetadataRadio(random = Random(0))
        val result = radio.suggest(
            seed = seedSong.toPlayable(),
            library = listOf(seedSong, cohort, sameGenre),
            excludeKeys = emptySet(),
            limit = 2,
            coPlaylistSongIds = setOf(2L)
        )
        assertEquals("Cohort", result.first().title)
    }

    @Test
    fun interleaveEquitableStartsWithOnlineAndDrainsRemainder() {
        val online = listOf(
            PlayableItem.remoteFrom(title = "R1", artist = "A"),
            PlayableItem.remoteFrom(title = "R2", artist = "A")
        )
        val offline = listOf(
            song(1, "L1", "A").toPlayable(),
            song(2, "L2", "A").toPlayable(),
            song(3, "L3", "A").toPlayable()
        )
        val out = RadioEngine.interleaveEquitable(online, offline, limit = 5)
        assertEquals(listOf("R1", "L1", "R2", "L2", "L3"), out.map { it.title })
    }
}
