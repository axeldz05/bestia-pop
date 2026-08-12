package com.bestiapop.android.data.network

import com.bestiapop.android.testutil.MediumTest
import com.bestiapop.android.testutil.MockWebServerRule
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.experimental.categories.Category
import java.util.concurrent.TimeUnit

@Category(MediumTest::class)
class MetadataFetcherIntegrationTest {

    @get:Rule
    val server = MockWebServerRule()

    @Before
    fun setUp() {
        val localBaseUrl = server.url("/").toString()
        MetadataFetcher.configureForTest(
            http = OkHttpClient.Builder()
                .callTimeout(1, TimeUnit.SECONDS)
                .build(),
            endpoints = MetadataFetcherEndpoints(
                deezerBaseUrl = localBaseUrl,
                itunesBaseUrl = localBaseUrl,
                lyricsBaseUrl = localBaseUrl
            )
        )
    }

    @After
    fun tearDown() {
        MetadataFetcher.resetTestOverrides()
    }

    @Test
    fun searchTrack_returnsCatalogIdentityAndEncodedTrackNumber() = runBlocking {
        enqueueJson(
            """
            {
              "data": [{
                "id": 101,
                "title": "Track A",
                "duration": 215,
                "track_position": 7,
                "disk_number": 2,
                "artist": {"name": "Artist A"},
                "album": {
                  "title": "Album A",
                  "cover_xl": "https://fixtures.invalid/cover-a.jpg",
                  "release_date": "2025-04-03"
                }
              }]
            }
            """
        )

        val tracks = MetadataFetcher.searchOnlineCatalog("  anonymous song  ", limit = 8)

        assertEquals(1, tracks.size)
        with(tracks.single()) {
            assertEquals("101", id)
            assertEquals("Track A", title)
            assertEquals("Artist A", artist)
            assertEquals("Album A", album)
            assertEquals(2007, trackNumber)
            assertEquals(2025, year)
            assertEquals("Artist A Track A", audioUrl)
        }
        with(server.takeRequest()) {
            assertEquals("/search", requestUrl?.encodedPath)
            assertEquals("anonymous song", requestUrl?.queryParameter("q"))
            assertEquals("8", requestUrl?.queryParameter("limit"))
            assertEquals("0", requestUrl?.queryParameter("index"))
            assertEquals("Mozilla/5.0", getHeader("User-Agent"))
        }
    }

    @Test
    fun searchTrack_emptyOrHttpErrorOnLaterPage_returnsEmpty() = runBlocking {
        enqueueJson("""{"data":[]}""")
        assertTrue(MetadataFetcher.searchOnlineCatalog("missing", index = 25).isEmpty())

        server.enqueue(MockResponse().setResponseCode(500))
        assertTrue(MetadataFetcher.searchOnlineCatalog("broken", index = 25).isEmpty())

        assertEquals("missing", server.takeRequest().requestUrl?.queryParameter("q"))
        assertEquals("broken", server.takeRequest().requestUrl?.queryParameter("q"))
    }

    @Test
    fun searchAlbum_returnsDisplayableAlbum() = runBlocking {
        enqueueJson(
            """
            {
              "data": [{
                "id": 202,
                "title": "Album B",
                "artist": {"name": "Artist B"},
                "cover_big": "https://fixtures.invalid/album-b.jpg",
                "nb_tracks": 9
              }]
            }
            """
        )

        val albums = MetadataFetcher.searchAlbums("album query")

        assertEquals(1, albums.size)
        with(albums.single()) {
            assertEquals("202", id)
            assertEquals("Album B", title)
            assertEquals("Artist B", artist)
            assertEquals(9, trackCount)
        }
        assertEquals("/search/album", server.takeRequest().requestUrl?.encodedPath)
    }

    @Test
    fun searchPlaylist_returnsDisplayablePlaylist() = runBlocking {
        enqueueJson(
            """
            {
              "data": [{
                "id": 303,
                "title": "Playlist C",
                "user": {"name": "Curator C"},
                "picture_xl": "https://fixtures.invalid/playlist-c.jpg",
                "nb_tracks": 12
              }]
            }
            """
        )

        val playlists = MetadataFetcher.searchPlaylists("playlist query")

        assertEquals(1, playlists.size)
        with(playlists.single()) {
            assertEquals("303", id)
            assertEquals("Playlist C", title)
            assertEquals("Curator C", creator)
            assertEquals(12, trackCount)
        }
        assertEquals("/search/playlist", server.takeRequest().requestUrl?.encodedPath)
    }

    @Test
    fun albumAndPlaylistHttpErrors_returnEmpty() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500))
        server.enqueue(MockResponse().setResponseCode(500))
        server.enqueue(MockResponse().setResponseCode(500))

        assertTrue(MetadataFetcher.searchAlbums("unavailable album").isEmpty())
        assertTrue(MetadataFetcher.searchPlaylists("unavailable playlist").isEmpty())

        assertEquals("/search/album", server.takeRequest().requestUrl?.encodedPath)
        assertEquals("/search", server.takeRequest().requestUrl?.encodedPath)
        assertEquals("/search/playlist", server.takeRequest().requestUrl?.encodedPath)
    }

    @Test
    fun genresAndGlobalChart_useBrowseEndpoints() = runBlocking {
        enqueueJson(
            """
            {
              "data": [{
                "id": 404,
                "name": "Genre D",
                "picture_big": "https://fixtures.invalid/genre-d.jpg"
              }]
            }
            """
        )
        enqueueJson(
            """
            {
              "data": [{
                "id": 405,
                "title": "Chart Track",
                "duration": 180,
                "artist": {"name": "Chart Artist"},
                "album": {"title": "Chart Album"}
              }]
            }
            """
        )

        val genres = MetadataFetcher.listGenres()
        val chart = MetadataFetcher.fetchChartTracks(limit = 6)

        assertEquals("Genre D", genres.single().name)
        assertEquals("Chart Track", chart.single().title)
        assertEquals("/genre", server.takeRequest().requestUrl?.encodedPath)
        with(server.takeRequest()) {
            assertEquals("/chart/0/tracks", requestUrl?.encodedPath)
            assertEquals("6", requestUrl?.queryParameter("limit"))
        }
    }

    @Test
    fun genresAndGlobalChart_httpErrorsReturnEmpty() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500))
        server.enqueue(MockResponse().setResponseCode(500))

        assertTrue(MetadataFetcher.listGenres().isEmpty())
        assertTrue(MetadataFetcher.fetchChartTracks().isEmpty())

        assertEquals("/genre", server.takeRequest().requestUrl?.encodedPath)
        assertEquals("/chart/0/tracks", server.takeRequest().requestUrl?.encodedPath)
    }

    @Test
    fun genreChartEmpty_fallsBackToGenreSearch() = runBlocking {
        enqueueJson("""{"data":[]}""")
        enqueueJson(
            """
            {
              "data": [{
                "id": 505,
                "title": "Genre Track",
                "artist": {"name": "Genre Artist"},
                "album": {"title": "Genre Album"}
              }]
            }
            """
        )

        val tracks = MetadataFetcher.searchTracksByGenre(
            genreId = 88,
            genreName = "Genre E",
            limit = 4
        )

        assertEquals("Genre Track", tracks.single().title)
        assertEquals("/chart/88/tracks", server.takeRequest().requestUrl?.encodedPath)
        with(server.takeRequest()) {
            assertEquals("/search", requestUrl?.encodedPath)
            assertEquals("genre:\"Genre E\"", requestUrl?.queryParameter("q"))
            assertEquals("4", requestUrl?.queryParameter("limit"))
        }
    }

    private fun enqueueJson(body: String) {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(body.trimIndent())
        )
    }
}
