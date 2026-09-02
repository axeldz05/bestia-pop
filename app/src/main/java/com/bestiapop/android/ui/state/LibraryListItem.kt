package com.bestiapop.android.ui.state

import androidx.compose.runtime.Immutable
import com.bestiapop.android.data.model.Song
import com.bestiapop.android.ui.SortOption
import com.bestiapop.android.ui.components.SortEmphasizedTexts
import com.bestiapop.android.ui.components.sortEmphasisFor
import com.bestiapop.android.ui.components.sortEmphasisForLastPlayed

/**
 * Compact LazyColumn index for the library song list.
 * Sort/group runs once; Compose materializes only visible rows via [itemAt].
 */
@Immutable
class LibraryListModel internal constructor(
    val songsVisual: List<Song>,
    val segments: List<LibraryAlbumSegment>,
    private val inheritedArtworkBySongId: Map<Long, String>,
    val sortOption: SortOption,
    val emphasizeLastPlayed: Boolean,
    val albumNames: Set<String>,
    val songsById: Map<Long, Song>,
    private val slots: IntArray
) {
    val size: Int get() = slots.size
    val isEmpty: Boolean get() = slots.isEmpty()

    fun keyAt(index: Int): Any {
        val slot = slots[index]
        return if (slot >= 0) {
            songsVisual[slot].id
        } else {
            "header_${segments[headerIndex(slot)].groupingKey}"
        }
    }

    fun contentTypeAt(index: Int): String =
        if (slots[index] >= 0) {
            LibraryListItem.CONTENT_TYPE_SONG
        } else {
            LibraryListItem.CONTENT_TYPE_ALBUM_HEADER
        }

    fun itemAt(index: Int): LibraryListItem {
        val slot = slots[index]
        if (slot >= 0) {
            val song = songsVisual[slot]
            return LibraryListItem.SongRow(
                song = song,
                index = slot,
                emphasis = rowEmphasis(song),
                artworkUri = song.artworkUri ?: inheritedArtworkBySongId[song.id]
            )
        }
        val segment = segments[headerIndex(slot)]
        return LibraryListItem.AlbumHeader(
            albumName = segment.albumName,
            displayName = segment.displayName,
            artistName = segment.artistName,
            artworkUri = segment.artworkUri,
            songCount = segment.count,
            songIds = segment.songIds,
            groupingKey = segment.groupingKey,
            sortHint = segment.sortHint
        )
    }

    fun collapsed(collapsedAlbumNames: Set<String>): LibraryListModel {
        if (collapsedAlbumNames.isEmpty() || segments.isEmpty()) return this
        return LibraryListModel(
            songsVisual = songsVisual,
            segments = segments,
            inheritedArtworkBySongId = inheritedArtworkBySongId,
            sortOption = sortOption,
            emphasizeLastPlayed = emphasizeLastPlayed,
            albumNames = albumNames,
            songsById = songsById,
            slots = buildSlots(segments, collapsedAlbumNames)
        )
    }

    fun toListItems(): List<LibraryListItem> = List(size, ::itemAt)

    private fun rowEmphasis(song: Song): SortEmphasizedTexts =
        if (emphasizeLastPlayed) {
            sortEmphasisForLastPlayed(song)
        } else {
            sortEmphasisFor(song, sortOption)
        }

    companion object {
        val EMPTY = LibraryListModel(
            songsVisual = emptyList(),
            segments = emptyList(),
            inheritedArtworkBySongId = emptyMap(),
            sortOption = SortOption.TITLE,
            emphasizeLastPlayed = false,
            albumNames = emptySet(),
            songsById = emptyMap(),
            slots = IntArray(0)
        )

        fun of(
            songsVisual: List<Song>,
            segments: List<LibraryAlbumSegment> = emptyList(),
            inheritedArtworkBySongId: Map<Long, String> = emptyMap(),
            sortOption: SortOption = SortOption.TITLE,
            emphasizeLastPlayed: Boolean = false
        ): LibraryListModel {
            if (songsVisual.isEmpty() && segments.isEmpty()) return EMPTY
            val names = LinkedHashSet<String>(segments.size)
            for (segment in segments) {
                if (segment.albumName.isNotBlank()) names.add(segment.albumName)
            }
            val byId = HashMap<Long, Song>(songsVisual.size * 2)
            for (song in songsVisual) byId[song.id] = song
            return LibraryListModel(
                songsVisual = songsVisual,
                segments = segments,
                inheritedArtworkBySongId = inheritedArtworkBySongId,
                sortOption = sortOption,
                emphasizeLastPlayed = emphasizeLastPlayed,
                albumNames = names,
                songsById = byId,
                slots = buildSlots(segments, emptySet(), songsVisual.size)
            )
        }

        internal fun headerIndex(slot: Int): Int = -slot - 1

        internal fun headerSlot(segmentIndex: Int): Int = -segmentIndex - 1

        internal fun buildSlots(
            segments: List<LibraryAlbumSegment>,
            collapsedAlbumNames: Set<String>,
            flatCount: Int = 0
        ): IntArray {
            if (segments.isEmpty()) {
                return IntArray(flatCount) { it }
            }
            var size = 0
            for (segment in segments) {
                size += 1
                if (!segment.matchesCollapsed(collapsedAlbumNames)) size += segment.count
            }
            val slots = IntArray(size)
            var i = 0
            for ((segIndex, segment) in segments.withIndex()) {
                slots[i++] = headerSlot(segIndex)
                if (segment.matchesCollapsed(collapsedAlbumNames)) continue
                var songOffset = 0
                while (songOffset < segment.count) {
                    slots[i++] = segment.start + songOffset
                    songOffset++
                }
            }
            return slots
        }
    }
}

@Immutable
data class LibraryAlbumSegment(
    val albumName: String,
    val displayName: String,
    val artistName: String,
    val artworkUri: String?,
    val groupingKey: String,
    val sortHint: String?,
    val start: Int,
    val count: Int,
    val songIds: List<Long>
) {
    fun matchesCollapsed(collapsed: Set<String>): Boolean =
        collapsed.contains(albumName) ||
            (groupingKey.isNotBlank() && collapsed.contains(groupingKey))
}

/**
 * Materialized LazyColumn entry. Prefer [LibraryListModel.itemAt] so the catalog
 * does not allocate one of these per library song before first paint.
 */
@Immutable
sealed interface LibraryListItem {
    val key: Any
    val contentType: String

    @Immutable
    data class AlbumHeader(
        /** Grouping key (`Song.album`); what album edits and menus address. */
        val albumName: String,
        /**
         * What to show: the [com.bestiapop.android.data.model.AlbumOverride] name when there is one.
         * Without it, renaming an album without propagating showed the new name under the Álbumes chip
         * and the old one in these headers.
         */
        val displayName: String,
        val artistName: String,
        val artworkUri: String?,
        val songCount: Int,
        val songIds: List<Long>,
        val groupingKey: String = albumName,
        val sortHint: String? = null
    ) : LibraryListItem {
        override val key: Any get() = "header_$groupingKey"
        override val contentType: String get() = CONTENT_TYPE_ALBUM_HEADER
    }

    @Immutable
    data class SongRow(
        val song: Song,
        /** Play index into the visual song order ([LibraryListModel.songsVisual]). */
        val index: Int,
        val emphasis: SortEmphasizedTexts,
        /** List thumbnail; may inherit album art without copying [song]. */
        val artworkUri: String? = song.artworkUri
    ) : LibraryListItem {
        override val key: Any get() = song.id
        override val contentType: String get() = CONTENT_TYPE_SONG
    }

    companion object {
        const val CONTENT_TYPE_SONG = "song"
        const val CONTENT_TYPE_ALBUM_HEADER = "album_header"
    }
}
