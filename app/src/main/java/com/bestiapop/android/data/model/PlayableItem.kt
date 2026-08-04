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
