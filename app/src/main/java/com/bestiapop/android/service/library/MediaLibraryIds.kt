package com.bestiapop.android.service.library

import java.nio.charset.StandardCharsets
import java.util.Base64

internal sealed interface MediaLibraryTarget {
    data object Root : MediaLibraryTarget
    data object Songs : MediaLibraryTarget
    data object Albums : MediaLibraryTarget
    data object Artists : MediaLibraryTarget
    data object Playlists : MediaLibraryTarget
    data class Song(val id: Long) : MediaLibraryTarget
    data class Album(val key: String) : MediaLibraryTarget
    data class Artist(val name: String) : MediaLibraryTarget
    data class Playlist(val id: Long) : MediaLibraryTarget
}

internal object MediaLibraryIds {
    const val ROOT = "bestiapop:root"
    const val SONGS = "bestiapop:cat:songs"
    const val ALBUMS = "bestiapop:cat:albums"
    const val ARTISTS = "bestiapop:cat:artists"
    const val PLAYLISTS = "bestiapop:cat:playlists"

    fun song(id: Long): String = "bestiapop:song:$id"
    fun album(key: String): String = "bestiapop:album:${encode(key)}"
    fun artist(name: String): String = "bestiapop:artist:${encode(name)}"
    fun playlist(id: Long): String = "bestiapop:playlist:$id"

    fun parse(mediaId: String): MediaLibraryTarget? = when (mediaId) {
        ROOT -> MediaLibraryTarget.Root
        SONGS -> MediaLibraryTarget.Songs
        ALBUMS -> MediaLibraryTarget.Albums
        ARTISTS -> MediaLibraryTarget.Artists
        PLAYLISTS -> MediaLibraryTarget.Playlists
        else -> parseDynamic(mediaId)
    }

    private fun parseDynamic(mediaId: String): MediaLibraryTarget? {
        val parts = mediaId.split(':', limit = 3)
        if (parts.size != 3 || parts[0] != "bestiapop") return null
        return when (parts[1]) {
            "song" -> parts[2].toLongOrNull()?.takeIf { it > 0L }?.let(MediaLibraryTarget::Song)
            "album" -> decode(parts[2])?.let(MediaLibraryTarget::Album)
            "artist" -> decode(parts[2])?.let(MediaLibraryTarget::Artist)
            "playlist" ->
                parts[2].toLongOrNull()?.takeIf { it > 0L }?.let(MediaLibraryTarget::Playlist)
            else -> null
        }
    }

    private fun encode(value: String): String =
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(value.toByteArray(StandardCharsets.UTF_8))

    private fun decode(value: String): String? = runCatching {
        String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8)
            .takeIf(String::isNotBlank)
    }.getOrNull()
}
