package com.bestiapop.android.domain.util

import com.bestiapop.android.data.model.Album

/**
 * Returns another album that [proposedName] would collide with, or null if safe to rename.
 * Match is on [Album.name] or [Album.displayName], after [normalizeAlbumName], ignoreCase.
 * Prefer the conflicting album with the most songs (stable tie-break by name).
 */
fun findAlbumMergeTarget(
    albums: List<Album>,
    sourceAlbumKey: String,
    proposedName: String
): Album? {
    val name = normalizeAlbumName(proposedName).ifBlank { sourceAlbumKey }
    return albums
        .filter { album ->
            !albumNamesMatch(album.name, sourceAlbumKey) &&
                (albumNamesMatch(album.name, name) || albumNamesMatch(album.displayName, name))
        }
        .maxWithOrNull(compareBy<Album> { it.songCount }.thenBy { it.name })
}

/**
 * All album keys in [albumKeys] that normalize to the same title as [targetName],
 * excluding the exact [excludeKey] string (typically the canonical target key).
 */
fun findEquivalentAlbumKeys(
    albumKeys: Collection<String>,
    targetName: String,
    excludeKey: String? = null
): List<String> {
    return albumKeys
        .distinct()
        .filter { key ->
            key != excludeKey && albumNamesMatch(key, targetName)
        }
}
