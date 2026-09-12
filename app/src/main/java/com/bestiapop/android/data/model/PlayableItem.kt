package com.bestiapop.android.data.model

import java.util.UUID

private val queueEntryCounter = java.util.concurrent.atomic.AtomicLong(1L)
private val processInstancePrefix = "${System.currentTimeMillis().toString(36)}-${java.util.concurrent.ThreadLocalRandom.current().nextInt(0, 0xFFFF).toString(16)}"

fun newQueueEntryId(): String = "q-${processInstancePrefix}-${queueEntryCounter.getAndIncrement().toString(36)}"

/**
 * Unified queue item: local library song or ephemeral remote stream.
 * Never persist [ResolvedStream.audioUrl] in Room (CDN URLs expire).
 */
sealed class PlayableItem : TrackMeta {
    abstract val mediaId: String
    /** Ephemeral identity of this occurrence in the playback queue; never persisted. */
    abstract val queueEntryId: String

    data class Local(
        val song: Song,
        override val queueEntryId: String = newQueueEntryId(),
        val resolvedArtworkUri: String? = null
    ) : PlayableItem(), TrackMeta by song {
        override val mediaId: String get() = song.uriString
        override val artworkUri: String? get() = resolvedArtworkUri ?: song.artworkUri
    }

    data class Remote(
        val identity: TrackIdentity,
        val recordingMbid: String? = null,
        val youtubeQueryOrId: String? = null,
        val resolved: ResolvedStream? = null,
        val lyrics: String? = null,
        override val queueEntryId: String = newQueueEntryId()
    ) : PlayableItem(), TrackMeta by identity {
        override val mediaId: String
            get() {
                val query = youtubeQueryOrId?.takeIf { it.isNotBlank() }
                    ?: "$artist|$title"
                return "remote:${query.lowercase().hashCode().toUInt().toString(16)}"
            }

        /** L2: map ephemeral remote to catalog download input (no CDN URL persisted). */
        fun toOnlineCatalogTrack(provider: String = "YouTube"): OnlineCatalogTrack =
            identity.toCatalogTrack(
                id = youtubeQueryOrId?.takeIf { it.isNotBlank() },
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
            val defaultQuery = identity.youtubeSearchQuery().takeIf { it.isNotBlank() }
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
            recordingMbid: String? = null,
            youtubeQueryOrId: String? = null
        ): PlayableItem = if (local != null) {
            local.toPlayableItem()
        } else {
            remoteFrom(
                identity = identity,
                recordingMbid = recordingMbid,
                youtubeQueryOrId = youtubeQueryOrId
            )
        }

        /** L2: library hit → Local; else ephemeral Remote (default YT query). */
        fun fromLibraryOrRemote(
            local: Song?,
            artist: String,
            title: String,
            album: String? = null,
            artworkUri: String? = null,
            recordingMbid: String? = null,
            youtubeQueryOrId: String? = null
        ): PlayableItem = fromLibraryOrRemote(
            local = local,
            identity = TrackIdentity(
                title = title,
                artist = artist,
                album = album.orEmpty(),
                artworkUri = artworkUri
            ),
            recordingMbid = recordingMbid,
            youtubeQueryOrId = youtubeQueryOrId
        )
    }
}

data class ResolvedStream(
    val audioUrl: String,
    val userAgent: String,
    val videoId: String,
    val resolvedAtEpochMs: Long
)

private inline fun <T, R> List<T>.mapFast(transform: (T) -> R): List<R> {
    if (isEmpty()) return emptyList()
    val out = ArrayList<R>(size)
    for (i in indices) {
        out.add(transform(this[i]))
    }
    return out
}

fun Song.toPlayable(artworkUri: String? = null): PlayableItem.Local =
    PlayableItem.Local(this, resolvedArtworkUri = artworkUri)

fun Song.toPlayableItem(
    queueEntryId: String = newQueueEntryId(),
    artworkUri: String? = null
): PlayableItem = if (isRemote) {
    PlayableItem.remoteFrom(
        identity = toIdentity().copy(artworkUri = artworkUri ?: this.artworkUri),
        youtubeQueryOrId = if (uriString.startsWith("remote://yt/")) uriString.removePrefix("remote://yt/") else null
    ).copy(queueEntryId = queueEntryId)
} else {
    PlayableItem.Local(this, queueEntryId = queueEntryId, resolvedArtworkUri = artworkUri)
}

fun List<Song>.toPlayableItems(artworkLookup: ((Song) -> String?)? = null): List<PlayableItem> =
    mapFast { it.toPlayableItem(artworkUri = artworkLookup?.invoke(it)) }

/** Transforms songs into playable items with fresh queue IDs in a single pass. */
fun List<Song>.toPlayableItemsWithFreshIds(artworkLookup: ((Song) -> String?)? = null): List<PlayableItem> =
    mapFast { it.toPlayableItem(queueEntryId = newQueueEntryId(), artworkUri = artworkLookup?.invoke(it)) }

/** Every occurrence entering a queue gets its own identity, even if the same object repeats. */
fun List<PlayableItem>.withFreshQueueEntryIds(): List<PlayableItem> =
    mapFast { item ->
        when (item) {
            is PlayableItem.Local -> item.copy(queueEntryId = newQueueEntryId())
            is PlayableItem.Remote -> item.copy(queueEntryId = newQueueEntryId())
        }
    }

/** Ensures every item in the queue has a unique non-empty queueEntryId without duplicate list allocation if already present. */
fun List<PlayableItem>.ensureFreshQueueEntryIds(): List<PlayableItem> {
    if (isEmpty()) return emptyList()
    var needsFresh = false
    for (i in indices) {
        if (this[i].queueEntryId.isBlank()) {
            needsFresh = true
            break
        }
    }
    return if (needsFresh) withFreshQueueEntryIds() else this
}

/** Compatibility alias for callers that previously refreshed Remote slots only. */
fun List<PlayableItem>.withFreshRemoteQueueEntryIds(): List<PlayableItem> =
    withFreshQueueEntryIds()

fun PlayableItem.matchesSong(song: Song): Boolean {
    if (this is PlayableItem.Local) {
        if (song.id > 0L && this.song.id == song.id) return true
        if (this.song.uriString == song.uriString || mediaId == song.uriString) return true
    }
    return mediaId == song.uriString
}

fun PlayableItem.matchesItem(other: PlayableItem): Boolean {
    if (mediaId == other.mediaId) return true
    val local = this as? PlayableItem.Local ?: return false
    val otherLocal = other as? PlayableItem.Local ?: return false
    if (local.song.id > 0L && local.song.id == otherLocal.song.id) return true
    return local.song.uriString == otherLocal.song.uriString
}

/** Exact occurrence before or after copy, resolve, or reorder. */
fun List<PlayableItem>.indexOfQueueEntry(original: PlayableItem): Int =
    indexOfFirst { it.queueEntryId == original.queueEntryId }

/**
 * Exact queue occurrence of a Remote before or after resolve. [PlayableItem.Remote.queueEntryId]
 * survives `copy(resolved = …)`.
 */
fun List<PlayableItem>.indexOfRemoteSlot(
    original: PlayableItem.Remote
): Int = indexOfFirst {
    it is PlayableItem.Remote && it.queueEntryId == original.queueEntryId
}
