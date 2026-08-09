package com.bestiapop.android.data.model

/**
 * Unified queue item: local library song or ephemeral remote stream.
 * Never persist [ResolvedStream.audioUrl] in Room (CDN URLs expire).
 */
sealed class PlayableItem : TrackMeta {
    abstract val mediaId: String

    data class Local(val song: Song) : PlayableItem(), TrackMeta by song {
        override val mediaId: String get() = song.uriString
    }

    data class Remote(
        val identity: TrackIdentity,
        val recordingMbid: String? = null,
        val youtubeQueryOrId: String? = null,
        val resolved: ResolvedStream? = null
    ) : PlayableItem(), TrackMeta by identity {
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
            identity = identity,
            id = youtubeQueryOrId?.takeIf { it.isNotBlank() }
                ?: "$artist $title".trim(),
            audioUrl = "",
            provider = provider
        )

        fun withIdentity(transform: TrackIdentity.() -> TrackIdentity): Remote =
            copy(identity = identity.transform())
    }

    companion object {
        /** L2: build a Remote from a portable identity. */
        fun remoteFrom(
            identity: TrackIdentity,
            recordingMbid: String? = null,
            youtubeQueryOrId: String? = null,
            resolved: ResolvedStream? = null
        ): Remote {
            val defaultQuery = "${identity.artist} ${identity.title}".trim().takeIf { it.isNotBlank() }
            return Remote(
                identity = identity,
                recordingMbid = recordingMbid,
                youtubeQueryOrId = youtubeQueryOrId?.takeIf { it.isNotBlank() } ?: defaultQuery,
                resolved = resolved
            )
        }

        /** L1/L2: build a Remote with default YouTube query `"$artist $title"`. */
        fun remoteFrom(
            artist: String,
            title: String,
            album: String? = null,
            artworkUri: String? = null,
            durationMs: Long = 0,
            trackNumber: Int = 0,
            recordingMbid: String? = null,
            youtubeQueryOrId: String? = null,
            resolved: ResolvedStream? = null
        ): Remote = remoteFrom(
            identity = TrackIdentity(
                title = title,
                artist = artist,
                album = album.orEmpty(),
                artworkUri = artworkUri,
                durationMs = durationMs,
                trackNumber = trackNumber
            ),
            recordingMbid = recordingMbid,
            youtubeQueryOrId = youtubeQueryOrId,
            resolved = resolved
        )

        /** L2: library hit → Local; else ephemeral Remote from identity. */
        fun fromLibraryOrRemote(
            local: Song?,
            identity: TrackIdentity,
            recordingMbid: String? = null
        ): PlayableItem = if (local != null) {
            local.toPlayable()
        } else {
            remoteFrom(identity = identity, recordingMbid = recordingMbid)
        }

        /** L2: library hit → Local; else ephemeral Remote (default YT query). */
        fun fromLibraryOrRemote(
            local: Song?,
            artist: String,
            title: String,
            album: String? = null,
            artworkUri: String? = null,
            recordingMbid: String? = null
        ): PlayableItem = fromLibraryOrRemote(
            local = local,
            identity = TrackIdentity(
                title = title,
                artist = artist,
                album = album.orEmpty(),
                artworkUri = artworkUri
            ),
            recordingMbid = recordingMbid
        )
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
