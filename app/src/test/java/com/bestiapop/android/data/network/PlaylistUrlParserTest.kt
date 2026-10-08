package com.bestiapop.android.data.network

import com.bestiapop.android.data.model.ParsedLinkTarget
import com.bestiapop.android.data.model.PlaylistPlatform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistUrlParserTest {
    @Test
    fun parse_youtubePlaylist_detectsPlatformAndId() {
        val url = "https://www.youtube.com/playlist?list=PLMC9KNkIncKtPzgY-5rmhvj7fax8fdxoj"
        val parsed = PlaylistUrlParser.parse(url)
        assertTrue(parsed is ParsedLinkTarget.Playlist)
        val playlist = parsed as ParsedLinkTarget.Playlist
        assertEquals(PlaylistPlatform.YOUTUBE, playlist.platform)
        assertEquals("PLMC9KNkIncKtPzgY-5rmhvj7fax8fdxoj", playlist.playlistId)
    }

    @Test
    fun parse_youtubeMusicPlaylist_detectsPlatformAndId() {
        val url = "https://music.youtube.com/playlist?list=PLrEnWoR732-DNr5wF87lPj_5Qx81Y7y6U&si=abcdef"
        val parsed = PlaylistUrlParser.parse(url)
        assertTrue(parsed is ParsedLinkTarget.Playlist)
        val playlist = parsed as ParsedLinkTarget.Playlist
        assertEquals(PlaylistPlatform.YOUTUBE, playlist.platform)
        assertEquals("PLrEnWoR732-DNr5wF87lPj_5Qx81Y7y6U", playlist.playlistId)
    }

    @Test
    fun parse_youtubeVideo_detectsTrack() {
        val url = "https://www.youtube.com/watch?v=dQw4w9WgXcQ"
        val parsed = PlaylistUrlParser.parse(url)
        assertTrue(parsed is ParsedLinkTarget.Track)
        val track = parsed as ParsedLinkTarget.Track
        assertEquals(PlaylistPlatform.YOUTUBE, track.platform)
    }

    @Test
    fun parse_spotifyPlaylist_detectsPlatformAndId() {
        val url = "https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M?si=123456"
        val parsed = PlaylistUrlParser.parse(url)
        assertTrue(parsed is ParsedLinkTarget.Playlist)
        val playlist = parsed as ParsedLinkTarget.Playlist
        assertEquals(PlaylistPlatform.SPOTIFY, playlist.platform)
        assertEquals("37i9dQZF1DXcBWIGoYBM5M", playlist.playlistId)
    }

    @Test
    fun parse_spotifyPlaylist_withLocaleInPath_detectsId() {
        val url = "https://open.spotify.com/intl-es/playlist/37i9dQZF1DXcBWIGoYBM5M"
        val parsed = PlaylistUrlParser.parse(url)
        assertTrue(parsed is ParsedLinkTarget.Playlist)
        val playlist = parsed as ParsedLinkTarget.Playlist
        assertEquals(PlaylistPlatform.SPOTIFY, playlist.platform)
        assertEquals("37i9dQZF1DXcBWIGoYBM5M", playlist.playlistId)
    }

    @Test
    fun parse_spotifyUri_detectsId() {
        val uri = "spotify:playlist:37i9dQZF1DXcBWIGoYBM5M"
        val parsed = PlaylistUrlParser.parse(uri)
        assertTrue(parsed is ParsedLinkTarget.Playlist)
        val playlist = parsed as ParsedLinkTarget.Playlist
        assertEquals(PlaylistPlatform.SPOTIFY, playlist.platform)
        assertEquals("37i9dQZF1DXcBWIGoYBM5M", playlist.playlistId)
    }

    @Test
    fun parse_spotifyTrack_detectsTrack() {
        val url = "https://open.spotify.com/track/11hcBLPtbMp4aQI6zGQLub?si=789"
        val parsed = PlaylistUrlParser.parse(url)
        assertTrue(parsed is ParsedLinkTarget.Track)
        val track = parsed as ParsedLinkTarget.Track
        assertEquals(PlaylistPlatform.SPOTIFY, track.platform)
        assertEquals("11hcBLPtbMp4aQI6zGQLub", track.trackIdOrUrl)
    }

    @Test
    fun parse_deezerPlaylist_detectsPlatformAndId() {
        val url = "https://www.deezer.com/playlist/30595446"
        val parsed = PlaylistUrlParser.parse(url)
        assertTrue(parsed is ParsedLinkTarget.Playlist)
        val playlist = parsed as ParsedLinkTarget.Playlist
        assertEquals(PlaylistPlatform.DEEZER, playlist.platform)
        assertEquals("30595446", playlist.playlistId)
    }

    @Test
    fun parse_deezerPlaylist_withLocale_detectsId() {
        val url = "https://www.deezer.com/es/playlist/30595446"
        val parsed = PlaylistUrlParser.parse(url)
        assertTrue(parsed is ParsedLinkTarget.Playlist)
        val playlist = parsed as ParsedLinkTarget.Playlist
        assertEquals(PlaylistPlatform.DEEZER, playlist.platform)
        assertEquals("30595446", playlist.playlistId)
    }

    @Test
    fun parse_deezerTrack_detectsTrack() {
        val url = "https://www.deezer.com/track/3135556"
        val parsed = PlaylistUrlParser.parse(url)
        assertTrue(parsed is ParsedLinkTarget.Track)
        val track = parsed as ParsedLinkTarget.Track
        assertEquals(PlaylistPlatform.DEEZER, track.platform)
        assertEquals("3135556", track.trackIdOrUrl)
    }

    @Test
    fun parse_directAudioUrl_detectsDirectAudio() {
        val url = "https://example.com/audio/song.mp3"
        val parsed = PlaylistUrlParser.parse(url)
        assertTrue(parsed is ParsedLinkTarget.DirectAudio)
        assertEquals(url, (parsed as ParsedLinkTarget.DirectAudio).url)
    }

    @Test
    fun parse_unsupportedUrl_returnsUnsupported() {
        val url = "https://unknownservice.com/playlist/123"
        val parsed = PlaylistUrlParser.parse(url)
        assertTrue(parsed is ParsedLinkTarget.Unsupported)
    }
}
