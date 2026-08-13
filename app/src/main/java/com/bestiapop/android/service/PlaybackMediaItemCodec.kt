package com.bestiapop.android.service

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.bestiapop.android.data.model.PlayableItem
import com.bestiapop.android.data.model.ResolvedStream
import com.bestiapop.android.data.model.Song
import com.bestiapop.android.data.model.TrackIdentity
import com.bestiapop.android.data.model.toIdentity
import com.bestiapop.android.data.util.TrackIdentityJson
import org.json.JSONObject

internal data class PlaybackMediaItemPortablePayload(
    val version: Int,
    val kind: String,
    val queueEntryId: String,
    val identity: TrackIdentity,
    val localSongId: Long? = null,
    val localUri: String? = null,
    val recordingMbid: String? = null,
    val queryOrId: String? = null,
    val videoId: String? = null,
    val userAgent: String? = null,
    val resolvedAtEpochMs: Long = 0L
)

/**
 * Stable Media3 boundary for BestiaPop queue entries.
 *
 * Portable identity and resolver inputs live in metadata extras. The expiring CDN URL is only the
 * MediaItem URI, so rebuilding a queue or persisting extras cannot accidentally retain it.
 */
object PlaybackMediaItemCodec {
    const val VERSION = 1

    private const val KIND_LOCAL = "local"
    private const val KIND_REMOTE = "remote"

    private const val EXTRA_VERSION = "bestiapop.playback.codecVersion"
    private const val EXTRA_KIND = "bestiapop.playback.kind"
    private const val EXTRA_QUEUE_ENTRY_ID = "bestiapop.playback.queueEntryId"
    private const val EXTRA_IDENTITY_JSON = "bestiapop.playback.identity"
    private const val EXTRA_LOCAL_SONG_ID = "bestiapop.playback.localSongId"
    private const val EXTRA_LOCAL_URI = "bestiapop.playback.localUri"
    private const val EXTRA_RECORDING_MBID = "bestiapop.playback.recordingMbid"
    private const val EXTRA_QUERY_OR_ID = "bestiapop.playback.queryOrId"
    private const val EXTRA_VIDEO_ID = "bestiapop.playback.videoId"
    private const val EXTRA_USER_AGENT = "bestiapop.playback.userAgent"
    private const val EXTRA_RESOLVED_AT = "bestiapop.playback.resolvedAt"

    fun encode(
        item: PlayableItem,
        localPlayableUri: (Song) -> Uri
    ): MediaItem = when (item) {
        is PlayableItem.Local -> encodeLocal(item, localPlayableUri(item.song))
        is PlayableItem.Remote -> encodeRemote(item)
    }

    /** Pure, JVM-testable representation of codec extras. It has no field capable of holding CDN. */
    internal fun portablePayload(item: PlayableItem): PlaybackMediaItemPortablePayload =
        when (item) {
            is PlayableItem.Local -> PlaybackMediaItemPortablePayload(
                version = VERSION,
                kind = KIND_LOCAL,
                queueEntryId = item.queueEntryId,
                identity = item.song.toIdentity(),
                localSongId = item.song.id,
                localUri = item.song.uriString
            )
            is PlayableItem.Remote -> PlaybackMediaItemPortablePayload(
                version = VERSION,
                kind = KIND_REMOTE,
                queueEntryId = item.queueEntryId,
                identity = item.identity,
                recordingMbid = item.recordingMbid,
                queryOrId = item.youtubeQueryOrId,
                videoId = item.resolved?.videoId,
                userAgent = item.resolved?.userAgent,
                resolvedAtEpochMs = item.resolved?.resolvedAtEpochMs ?: 0L
            )
        }

    internal fun restore(
        payload: PlaybackMediaItemPortablePayload,
        mediaUri: String? = null,
        library: List<Song> = emptyList()
    ): PlayableItem? {
        if (payload.version != VERSION || payload.queueEntryId.isBlank()) return null
        return when (payload.kind) {
            KIND_LOCAL -> {
                val uri = payload.localUri?.takeIf { it.isNotBlank() } ?: return null
                val song = library.firstOrNull {
                    payload.localSongId != null && payload.localSongId > 0L &&
                        it.id == payload.localSongId
                } ?: library.firstOrNull { it.uriString == uri }
                    ?: Song(
                        id = payload.localSongId ?: 0L,
                        uriString = uri,
                        title = payload.identity.title,
                        artist = payload.identity.artist.ifBlank { "Unknown Artist" },
                        album = payload.identity.album.ifBlank { "Unknown Album" },
                        artworkUri = payload.identity.artworkUri,
                        durationMs = payload.identity.durationMs,
                        trackNumber = payload.identity.trackNumber
                    )
                PlayableItem.Local(song, payload.queueEntryId)
            }
            KIND_REMOTE -> {
                val videoId = payload.videoId?.takeIf { it.isNotBlank() }
                val userAgent = payload.userAgent?.takeIf { it.isNotBlank() }
                val audioUrl = mediaUri?.takeIf { it.isNotBlank() }
                val resolved = when {
                    audioUrl != null && videoId != null && userAgent != null -> ResolvedStream(
                        audioUrl = audioUrl,
                        userAgent = userAgent,
                        videoId = videoId,
                        resolvedAtEpochMs = payload.resolvedAtEpochMs
                    )
                    videoId != null -> ResolvedStream(
                        audioUrl = "",
                        userAgent = userAgent.orEmpty(),
                        videoId = videoId,
                        resolvedAtEpochMs = 0L
                    )
                    else -> null
                }
                PlayableItem.Remote(
                    identity = payload.identity,
                    recordingMbid = payload.recordingMbid,
                    youtubeQueryOrId = payload.queryOrId ?: videoId,
                    resolved = resolved,
                    queueEntryId = payload.queueEntryId
                )
            }
            else -> null
        }
    }

    fun decode(
        mediaItem: MediaItem,
        library: List<Song> = emptyList()
    ): PlayableItem? {
        val extras = mediaItem.mediaMetadata.extras ?: return decodeLegacy(mediaItem, library)
        if (extras.getInt(EXTRA_VERSION, 0) != VERSION) return decodeLegacy(mediaItem, library)
        val queueEntryId = extras.getString(EXTRA_QUEUE_ENTRY_ID)?.takeIf { it.isNotBlank() }
            ?: return null
        val metadata = mediaItem.mediaMetadata
        val decodedIdentity = decodeIdentity(extras)
        val identity = decodedIdentity.copy(
            title = decodedIdentity.title.ifBlank { metadata.title?.toString().orEmpty() },
            artist = decodedIdentity.artist.ifBlank { metadata.artist?.toString().orEmpty() },
            album = decodedIdentity.album.ifBlank { metadata.albumTitle?.toString().orEmpty() },
            artworkUri = decodedIdentity.artworkUri ?: metadata.artworkUri?.toString()
        )
        val tag = mediaItem.streamPlaybackTag()
        val payload = PlaybackMediaItemPortablePayload(
            version = extras.getInt(EXTRA_VERSION, 0),
            kind = extras.getString(EXTRA_KIND).orEmpty(),
            queueEntryId = queueEntryId,
            identity = identity,
            localSongId = extras.getLong(EXTRA_LOCAL_SONG_ID, 0L).takeIf { it > 0L },
            localUri = extras.getString(EXTRA_LOCAL_URI) ?: mediaItem.mediaId,
            recordingMbid = extras.getString(EXTRA_RECORDING_MBID),
            queryOrId = extras.getString(EXTRA_QUERY_OR_ID),
            videoId = extras.getString(EXTRA_VIDEO_ID) ?: tag?.videoId,
            userAgent = extras.getString(EXTRA_USER_AGENT) ?: tag?.userAgent,
            resolvedAtEpochMs = extras.getLong(EXTRA_RESOLVED_AT, 0L)
        )
        return restore(
            payload = payload,
            mediaUri = mediaItem.localConfiguration?.uri?.toString(),
            library = library
        ) ?: decodeLegacy(mediaItem, library)
    }

    /** Test/support hook: codec-owned metadata extras, never the MediaItem URI. */
    internal fun portableExtras(mediaItem: MediaItem): Bundle? = mediaItem.mediaMetadata.extras

    private fun encodeLocal(item: PlayableItem.Local, uri: Uri): MediaItem {
        val payload = portablePayload(item)
        return MediaItem.Builder()
            .setMediaId(item.song.uriString)
            .setUri(uri)
            .setMediaMetadata(
                metadata(
                    identity = payload.identity,
                    extras = baseExtras(payload.kind, payload.queueEntryId).apply {
                        putLong(EXTRA_LOCAL_SONG_ID, payload.localSongId ?: 0L)
                        putString(EXTRA_LOCAL_URI, payload.localUri)
                    }
                )
            )
            .build()
    }

    private fun encodeRemote(item: PlayableItem.Remote): MediaItem {
        val payload = portablePayload(item)
        val resolved = item.resolved
        val extras = baseExtras(payload.kind, payload.queueEntryId).apply {
            payload.recordingMbid?.takeIf { it.isNotBlank() }
                ?.let { putString(EXTRA_RECORDING_MBID, it) }
            payload.queryOrId?.takeIf { it.isNotBlank() }
                ?.let { putString(EXTRA_QUERY_OR_ID, it) }
            payload.videoId?.takeIf { it.isNotBlank() }
                ?.let { putString(EXTRA_VIDEO_ID, it) }
            payload.userAgent?.takeIf { it.isNotBlank() }
                ?.let { putString(EXTRA_USER_AGENT, it) }
            putLong(EXTRA_RESOLVED_AT, payload.resolvedAtEpochMs)
        }
        val builder = MediaItem.Builder()
            .setMediaId(item.mediaId)
            // The CDN URL intentionally has no second representation in any extras.
            .setUri(resolved?.audioUrl?.takeIf { it.isNotBlank() }?.let(Uri::parse) ?: Uri.EMPTY)
            .setMediaMetadata(metadata(item.identity, extras))
        if (resolved != null && resolved.userAgent.isNotBlank()) {
            builder.setStreamPlaybackTag(
                StreamPlaybackTag(
                    userAgent = resolved.userAgent,
                    videoId = resolved.videoId
                )
            )
        }
        return builder.build()
    }

    private fun decodeLegacy(mediaItem: MediaItem, library: List<Song>): PlayableItem? {
        val local = library.firstOrNull { it.uriString == mediaItem.mediaId }
        if (local != null) return PlayableItem.Local(local)
        return null
    }

    private fun baseExtras(kind: String, queueEntryId: String): Bundle =
        Bundle().apply {
            putInt(EXTRA_VERSION, VERSION)
            putString(EXTRA_KIND, kind)
            putString(EXTRA_QUEUE_ENTRY_ID, queueEntryId)
        }

    private fun metadata(identity: TrackIdentity, extras: Bundle): MediaMetadata {
        extras.putString(
            EXTRA_IDENTITY_JSON,
            JSONObject().also { TrackIdentityJson.putInto(it, identity) }.toString()
        )
        return identity.mediaMetadataBuilder()
            .setExtras(extras)
            .build()
    }

    private fun decodeIdentity(extras: Bundle): TrackIdentity =
        runCatching {
            TrackIdentityJson.decode(
                JSONObject(extras.getString(EXTRA_IDENTITY_JSON).orEmpty())
            )
        }.getOrElse { TrackIdentity(title = "") }
}
