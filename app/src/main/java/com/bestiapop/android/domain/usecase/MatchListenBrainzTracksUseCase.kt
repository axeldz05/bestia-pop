package com.bestiapop.android.domain.usecase

import com.bestiapop.android.data.listenbrainz.LbPlaylistDetail
import com.bestiapop.android.data.listenbrainz.MatchedLbPlaylist
import com.bestiapop.android.data.listenbrainz.MatchedLbTrack
import com.bestiapop.android.data.model.Song

class MatchListenBrainzTracksUseCase {

    fun execute(detail: LbPlaylistDetail, library: List<Song>): MatchedLbPlaylist {
        val index = buildLibraryIndex(library)
        val matches = detail.tracks.map { track ->
            MatchedLbTrack(
                track = track,
                localSong = index[matchKey(track.artist, track.title)]
            )
        }
        return MatchedLbPlaylist(detail = detail, matches = matches)
    }

    private fun buildLibraryIndex(library: List<Song>): Map<String, Song> {
        val map = HashMap<String, Song>(library.size)
        for (song in library) {
            val key = matchKey(song.artist, song.title)
            if (key.isNotEmpty() && !map.containsKey(key)) {
                map[key] = song
            }
        }
        return map
    }

    private fun matchKey(artist: String, title: String): String {
        val a = normalize(artist)
        val t = normalize(title)
        if (a.isEmpty() || t.isEmpty()) return ""
        return "$a|$t"
    }

    companion object {
        fun normalize(value: String): String {
            return value
                .lowercase()
                .replace(Regex("[\\p{Punct}\\p{IsPunctuation}]"), " ")
                .replace(Regex("\\s+"), " ")
                .trim()
        }
    }
}
