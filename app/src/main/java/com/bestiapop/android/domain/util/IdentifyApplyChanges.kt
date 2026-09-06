package com.bestiapop.android.domain.util

import com.bestiapop.android.data.model.IdentifyApplyField
import com.bestiapop.android.data.model.IdentifyApplyFields
import com.bestiapop.android.data.model.IdentifyCandidate
import com.bestiapop.android.data.model.Song
import com.bestiapop.android.data.model.isEnabled
import com.bestiapop.android.data.util.albumTrackDisplayNumber

/** One metadata field that [applySongIdentity] would rewrite for the chosen candidate. */
data class IdentifyFieldChange(
    val field: IdentifyApplyField,
    val from: String,
    val to: String
) {
    fun format(): String = when {
        from.isBlank() && to.isBlank() -> field.chipLabel
        from.isBlank() -> "${field.chipLabel}: → $to"
        to.isBlank() -> "${field.chipLabel}: $from →"
        else -> "${field.chipLabel}: $from → $to"
    }
}

fun formatIdentifyApplyChanges(changes: List<IdentifyFieldChange>): String =
    if (changes.isEmpty()) {
        "Sin cambios en los campos elegidos"
    } else {
        changes.joinToString(" · ") { it.format() }
    }

/**
 * Fields ticked in [fields] whose applied value would differ from [song].
 * Duration is never included (local duration is kept when > 0).
 */
fun identifyApplyChanges(
    song: Song,
    candidate: IdentifyCandidate,
    fields: IdentifyApplyFields
): List<IdentifyFieldChange> = buildList {
    if (fields.isEnabled(IdentifyApplyField.TITLE)) {
        val written = IdentifyRanking.cleanIdentityTitle(candidate.title, candidate.artist).ifBlank { candidate.title }.trim()
        val current = song.title.trim()
        if (written.isNotEmpty() && written != current) {
            add(IdentifyFieldChange(IdentifyApplyField.TITLE, current, written))
        }
    }
    if (fields.isEnabled(IdentifyApplyField.ARTIST)) {
        val written = candidate.artist.trim()
        val current = song.artist.trim()
        if (written.isNotEmpty() &&
            !IdentifyRanking.isPlaceholderArtist(written) &&
            !artistsCompatible(current, written)
        ) {
            add(IdentifyFieldChange(IdentifyApplyField.ARTIST, current, written))
        }
    }
    if (fields.isEnabled(IdentifyApplyField.ALBUM)) {
        val written = candidate.album.trim()
        val current = song.album.trim()
        if (written.isNotEmpty() &&
            !IdentifyRanking.isGenericAlbum(written) &&
            !albumNamesMatch(current, written)
        ) {
            add(IdentifyFieldChange(IdentifyApplyField.ALBUM, current, written))
        }
    }
    if (fields.isEnabled(IdentifyApplyField.YEAR)) {
        val written = candidate.year.takeIf { it in 1000..9999 }
        val current = song.year.takeIf { it in 1000..9999 }
        if (written != null && written != current) {
            add(
                IdentifyFieldChange(
                    IdentifyApplyField.YEAR,
                    current?.toString().orEmpty(),
                    written.toString()
                )
            )
        }
    }
    if (fields.isEnabled(IdentifyApplyField.TRACK_NUMBER)) {
        val written = albumTrackDisplayNumber(candidate.trackNumber)
        val current = albumTrackDisplayNumber(song.trackNumber)
        if (written > 0 && written != current) {
            add(
                IdentifyFieldChange(
                    IdentifyApplyField.TRACK_NUMBER,
                    if (current > 0) current.toString() else "",
                    written.toString()
                )
            )
        }
    }
    if (fields.isEnabled(IdentifyApplyField.ARTWORK)) {
        val written = candidate.artworkUri?.trim().orEmpty()
        val current = song.artworkUri?.trim().orEmpty()
        if (written.isNotEmpty() && written != current) {
            add(IdentifyFieldChange(IdentifyApplyField.ARTWORK, "", ""))
        }
    }
}
