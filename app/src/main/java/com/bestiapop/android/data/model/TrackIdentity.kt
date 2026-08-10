package com.bestiapop.android.data.model

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
