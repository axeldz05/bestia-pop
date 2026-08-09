package com.bestiapop.android.ui.state

import com.bestiapop.android.data.preferences.PLAYLIST_DETAIL_CF
import com.bestiapop.android.data.preferences.PLAYLIST_DETAIL_LB
import com.bestiapop.android.data.preferences.PLAYLIST_DETAIL_LOCAL
import com.bestiapop.android.data.preferences.PLAYLIST_DETAIL_NONE
import com.bestiapop.android.data.preferences.UiNavSnapshot

sealed interface PlaylistDetailNav {
    data object None : PlaylistDetailNav
    data class Local(val id: Long) : PlaylistDetailNav
    data class ListenBrainz(val mbid: String) : PlaylistDetailNav
    data object CfRecommendations : PlaylistDetailNav

    companion object {
        fun fromSnapshot(snapshot: UiNavSnapshot): PlaylistDetailNav =
            when (snapshot.playlistDetailKind) {
                PLAYLIST_DETAIL_LOCAL ->
                    snapshot.playlistLocalId?.let { Local(it) } ?: None
                PLAYLIST_DETAIL_LB ->
                    snapshot.playlistLbMbid?.let { ListenBrainz(it) } ?: None
                PLAYLIST_DETAIL_CF -> CfRecommendations
                else -> None
            }
    }
}

fun PlaylistDetailNav.kindName(): String = when (this) {
    PlaylistDetailNav.None -> PLAYLIST_DETAIL_NONE
    is PlaylistDetailNav.Local -> PLAYLIST_DETAIL_LOCAL
    is PlaylistDetailNav.ListenBrainz -> PLAYLIST_DETAIL_LB
    PlaylistDetailNav.CfRecommendations -> PLAYLIST_DETAIL_CF
}

fun PlaylistDetailNav.localIdOrNull(): Long? =
    (this as? PlaylistDetailNav.Local)?.id

fun PlaylistDetailNav.lbMbidOrNull(): String? =
    (this as? PlaylistDetailNav.ListenBrainz)?.mbid
