package com.bestiapop.android.data.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpotifyApiClientTest {
    @Test
    fun parseUserProfile_extractsFieldsCorrectly() {
        val json =
            """
            {
                "id": "bestia_user_1",
                "display_name": "Bestia User",
                "images": [
                    { "url": "https://i.scdn.co/image/user1" }
                ]
            }
            """.trimIndent()

        val profile = SpotifyApiClient.parseUserProfile(json)
        assertEquals("bestia_user_1", profile.id)
        assertEquals("Bestia User", profile.displayName)
        assertEquals("https://i.scdn.co/image/user1", profile.avatarUrl)
    }

    @Test
    fun parseUserProfile_fallbackWhenDisplayNameMissing() {
        val json =
            """
            {
                "id": "bestia_user_2",
                "images": []
            }
            """.trimIndent()

        val profile = SpotifyApiClient.parseUserProfile(json)
        assertEquals("bestia_user_2", profile.id)
        assertEquals("bestia_user_2", profile.displayName)
        assertNull(profile.avatarUrl)
    }

    @Test
    fun parsePlaylistsResponse_extractsItemsAndNextUrl() {
        val json =
            """
            {
                "items": [
                    {
                        "id": "pl_1",
                        "name": "Rock Nacional",
                        "description": "Lo mejor del rock",
                        "images": [{ "url": "https://i.scdn.co/image/pl1" }],
                        "tracks": { "total": 42 },
                        "public": true,
                        "owner": { "display_name": "Rockero" }
                    },
                    {
                        "id": "pl_2",
                        "name": "Acústicos",
                        "images": [],
                        "tracks": { "total": 15 },
                        "public": false,
                        "owner": { "display_name": "Rockero" }
                    }
                ],
                "next": "https://api.spotify.com/v1/me/playlists?offset=50&limit=50"
            }
            """.trimIndent()

        val (playlists, nextUrl) = SpotifyApiClient.parsePlaylistsResponse(json)
        assertEquals(2, playlists.size)

        val first = playlists[0]
        assertEquals("pl_1", first.id)
        assertEquals("Rock Nacional", first.name)
        assertEquals("Lo mejor del rock", first.description)
        assertEquals("https://i.scdn.co/image/pl1", first.coverUrl)
        assertEquals(42, first.trackCount)
        assertTrue(first.isPublic)

        val second = playlists[1]
        assertEquals("pl_2", second.id)
        assertEquals("Acústicos", second.name)
        assertNull(second.coverUrl)
        assertEquals(15, second.trackCount)
        assertFalse(second.isPublic)

        assertEquals("https://api.spotify.com/v1/me/playlists?offset=50&limit=50", nextUrl)
    }

    @Test
    fun parseTrackItemsResponse_extractsTrackIdentitiesWithFallbacks() {
        val json =
            """
            {
                "items": [
                    {
                        "track": {
                            "name": "De Música Ligera",
                            "artists": [
                                { "name": "Soda Stereo" }
                            ],
                            "album": {
                                "name": "Canción Animal",
                                "images": [
                                    { "url": "https://i.scdn.co/image/animal" }
                                ]
                            },
                            "duration_ms": 212000
                        }
                    },
                    {
                        "track": {
                            "name": "Mil Horas",
                            "artists": [
                                { "name": "Los Abuelos de la Nada" },
                                { "name": "Andrés Calamaro" }
                            ],
                            "album": {
                                "name": ""
                            },
                            "duration_ms": 170000
                        }
                    }
                ],
                "next": null
            }
            """.trimIndent()

        val (tracks, nextUrl) =
            SpotifyApiClient.parseTrackItemsResponse(
                jsonString = json,
                fallbackAlbum = "Rock 80s",
                fallbackCover = "https://i.scdn.co/image/fallback",
                startingTrackNumber = 10,
            )

        assertNull(nextUrl)
        assertEquals(2, tracks.size)

        val track1 = tracks[0]
        assertEquals("De Música Ligera", track1.title)
        assertEquals("Soda Stereo", track1.artist)
        assertEquals("Canción Animal", track1.album)
        assertEquals("https://i.scdn.co/image/animal", track1.artworkUri)
        assertEquals(212000L, track1.durationMs)
        assertEquals(10, track1.trackNumber)

        val track2 = tracks[1]
        assertEquals("Mil Horas", track2.title)
        assertEquals("Los Abuelos de la Nada, Andrés Calamaro", track2.artist)
        assertEquals("Rock 80s", track2.album)
        assertEquals("https://i.scdn.co/image/fallback", track2.artworkUri)
        assertEquals(170000L, track2.durationMs)
        assertEquals(11, track2.trackNumber)
    }

    @Test
    fun isTrustedSpotifyUrl_acceptsValidEndpoints() {
        assertTrue(SpotifyApiClient.isTrustedSpotifyUrl("https://api.spotify.com/v1/me"))
        assertTrue(SpotifyApiClient.isTrustedSpotifyUrl("https://api.spotify.com/v1/playlists/123/tracks?limit=100"))
        assertTrue(SpotifyApiClient.isTrustedSpotifyUrl("https://API.SPOTIFY.COM/v1/me"))
    }

    @Test
    fun isTrustedSpotifyUrl_rejectsInsecureOrUntrustedUrls() {
        assertFalse(SpotifyApiClient.isTrustedSpotifyUrl("http://api.spotify.com/v1/me"))
        assertFalse(SpotifyApiClient.isTrustedSpotifyUrl("https://evil.com/v1/me"))
        assertFalse(SpotifyApiClient.isTrustedSpotifyUrl("https://api.spotify.com.evil.com/v1/me"))
        assertFalse(SpotifyApiClient.isTrustedSpotifyUrl("https://user:pass@api.spotify.com/v1/me"))
        assertFalse(SpotifyApiClient.isTrustedSpotifyUrl("not-a-valid-url"))
    }
}
