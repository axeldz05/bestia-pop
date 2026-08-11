package com.bestiapop.android.ui.state

import com.bestiapop.android.data.model.PlayableItem
import com.bestiapop.android.domain.radio.RadioMode
import com.bestiapop.android.domain.util.TrackMatchKeys

/**
 * UI state for multi-select “Similares” preview (editable candidate list → playlist / play).
 * Null VM flow = dialog closed.
 */
data class SimilarPlaylistPreviewState(
    val items: List<PlayableItem>,
    val selectedKeys: Set<String>,
    val mode: RadioMode,
    val loading: Boolean,
    val seedCount: Int,
    val playlistName: String,
    val usedOnline: Boolean = false,
    val failedOnline: Boolean = false
) {
    val selectedItems: List<PlayableItem>
        get() = items.filter { previewKey(it) in selectedKeys }

    companion object {
        fun previewKey(item: PlayableItem): String {
            val match = TrackMatchKeys.matchKey(item.artist, item.title)
            return match.ifEmpty { item.mediaId }
        }

        fun keysOf(items: List<PlayableItem>): Set<String> =
            items.mapTo(LinkedHashSet()) { previewKey(it) }
    }
}
