package com.bestiapop.android.data.model

/**
 * Unified queue item: local library song or ephemeral remote stream.
 * Never persist [ResolvedStream.audioUrl] in Room (CDN URLs expire).
 */
sealed class PlayableItem {
    abstract val title: String
    abstract val artist: String
    abstract val artworkUri: String?
    abstract val durationMs: Long
    abstract val mediaId: String

    data class Local(val song: Song) : PlayableItem() {
        override val title: String get() = song.title
        override val artist: String get() = song.artist
        override val artworkUri: String? get() = song.artworkUri
        override val durationMs: Long get() = song.durationMs
        override val mediaId: String get() = song.uriString
    }

    data class Remote(
        override val title: String,
        override val artist: String,
        val album: String? = null,
        override val artworkUri: String? = null,
        override val durationMs: Long = 0,
        val recordingMbid: String? = null,
        val youtubeQueryOrId: String? = null,
        val resolved: ResolvedStream? = null
    ) : PlayableItem() {
        override val mediaId: String
            get() {
                val videoId = resolved?.videoId?.takeIf { it.isNotBlank() }
                if (videoId != null) return "remote:$videoId"
                val query = youtubeQueryOrId?.takeIf { it.isNotBlank() }
                    ?: "$artist|$title"
                return "remote:${query.lowercase().hashCode().toUInt().toString(16)}"
            }

        /** L2: map ephemeral remote to catalog download input (no CDN URL persisted). */
        fun toOnlineCatalogTrack(provider: String = "YouTube"): OnlineCatalogTrack = OnlineCatalogTrack(
            id = youtubeQueryOrId?.takeIf { it.isNotBlank() }
                ?: "$artist $title".trim(),
            title = title,
            artist = artist,
            album = album.orEmpty(),
            artworkUrl = artworkUri,
            durationMs = durationMs,
            audioUrl = "",
            provider = provider
        )
    }

    companion object {
        /** L2: build a Remote with default YouTube query `"$artist $title"`. */
        fun remoteFrom(
            artist: String,
            title: String,
            album: String? = null,
            artworkUri: String? = null,
            durationMs: Long = 0,
            recordingMbid: String? = null,
            youtubeQueryOrId: String? = null,
            resolved: ResolvedStream? = null
        ): Remote {
            val defaultQuery = "$artist $title".trim().takeIf { it.isNotBlank() }
            return Remote(
                title = title,
                artist = artist,
                album = album,
                artworkUri = artworkUri,
                durationMs = durationMs,
                recordingMbid = recordingMbid,
                youtubeQueryOrId = youtubeQueryOrId?.takeIf { it.isNotBlank() } ?: defaultQuery,
                resolved = resolved
            )
        }

        /** L2: library hit → Local; else ephemeral Remote (default YT query). */
        fun fromLibraryOrRemote(
            local: Song?,
            artist: String,
            title: String,
            album: String? = null,
            artworkUri: String? = null,
            recordingMbid: String? = null
        ): PlayableItem {
            val song = local
            return if (song != null) {
                song.toPlayable()
            } else {
                remoteFrom(
                    artist = artist,
                    title = title,
                    album = album,
                    artworkUri = artworkUri,
                    recordingMbid = recordingMbid
                )
            }
        }
    }
}

data class ResolvedStream(
    val audioUrl: String,
    val userAgent: String,
    val videoId: String,
    val resolvedAtEpochMs: Long
)

fun Song.toPlayable(): PlayableItem.Local = PlayableItem.Local(this)

fun List<Song>.toPlayableItems(): List<PlayableItem> = map { it.toPlayable() }
