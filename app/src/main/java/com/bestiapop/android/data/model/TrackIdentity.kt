package com.bestiapop.android.data.model

import com.bestiapop.android.domain.util.IdentifyRanking

/** Shared catalog/library identity fields. Persistable library rows stay flat on [Song]. */
interface TrackMeta {
    val title: String
    val artist: String
    val album: String
    val artworkUri: String?
    val durationMs: Long
    val trackNumber: Int
}

data class TrackIdentity(
    override val title: String,
    override val artist: String = "",
    override val album: String = "",
    override val artworkUri: String? = null,
    override val durationMs: Long = 0L,
    override val trackNumber: Int = 0
) : TrackMeta

const val DEFAULT_CATALOG_USER_AGENT =
    "Mozilla/5.0 (SmartHub; SMART-TV; U; Linux/SmartTV) AppleWebKit/538.1 (KHTML, like Gecko) TV Safari/538.1"

/** Prefer non-blank / positive fields from this identity; fill gaps from [other]. */
fun TrackIdentity.mergePreferring(other: TrackIdentity): TrackIdentity = copy(
    title = title.ifBlank { other.title },
    artist = artist.ifBlank { other.artist },
    album = album.ifBlank { other.album },
    artworkUri = artworkUri?.takeIf { it.isNotBlank() } ?: other.artworkUri,
    durationMs = if (durationMs > 0L) durationMs else other.durationMs,
    trackNumber = if (trackNumber > 0) trackNumber else other.trackNumber
)

fun Song.toIdentity(): TrackIdentity = TrackIdentity(
    title = title,
    artist = artist,
    album = album,
    artworkUri = artworkUri,
    durationMs = durationMs,
    trackNumber = trackNumber
)

/** Apply shared identity fields; leaves genre/year/lyrics/uri/folder untouched. */
fun Song.withIdentity(identity: TrackIdentity): Song = copy(
    title = identity.title,
    artist = identity.artist,
    album = identity.album,
    artworkUri = identity.artworkUri,
    durationMs = identity.durationMs,
    trackNumber = identity.trackNumber
)

fun OnlineCatalogTrack.withIdentity(
    transform: TrackIdentity.() -> TrackIdentity
): OnlineCatalogTrack = copy(identity = identity.transform())

/**
 * When cycling YouTube matches ("Buscar otro"), keep useful album/art/title/artist from [previous].
 */
fun OnlineCatalogTrack.preferMetaFrom(previous: TrackMeta): OnlineCatalogTrack = withIdentity {
    copy(
        album = previous.album.takeIf { it.isNotBlank() && !IdentifyRanking.isGenericAlbum(it) } ?: album,
        artworkUri = artworkUri ?: previous.artworkUri,
        title = title.ifBlank { previous.title },
        artist = artist.ifBlank { previous.artist }
    )
}

/** Default YouTube / catalog search string from artist + title. */
fun youtubeSearchQuery(artist: String, title: String): String = "$artist $title".trim()

fun TrackMeta.youtubeSearchQuery(): String = youtubeSearchQuery(artist, title)

/**
 * L2: catalog download input from identity.
 * [id] defaults to [youtubeSearchQuery] when null/blank.
 */
fun TrackIdentity.toCatalogTrack(
    id: String? = null,
    provider: String,
    audioUrl: String = ""
): OnlineCatalogTrack = OnlineCatalogTrack(
    identity = this,
    id = id?.takeIf { it.isNotBlank() } ?: youtubeSearchQuery(),
    audioUrl = audioUrl,
    provider = provider
)

/** Catalog download input for ListenBrainz rows (pending / unmatched discover). */
fun TrackIdentity.toListenBrainzCatalogTrack(mbid: String?): OnlineCatalogTrack =
    toCatalogTrack(
        id = mbid?.takeIf { it.isNotBlank() },
        provider = "ListenBrainz"
    )
