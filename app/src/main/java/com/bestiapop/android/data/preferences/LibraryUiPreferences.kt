package com.bestiapop.android.data.preferences

const val DEFAULT_SORT_OPTION_NAME = "TITLE"
const val DEFAULT_VIEW_MODE_NAME = "ALBUM_GROUPS"

const val NAV_LIBRARY = 0
const val NAV_PLAYLISTS = 1
const val NAV_DOWNLOADS = 2
const val NAV_WIFI = 3
const val NAV_SETTINGS = 4

const val LIBRARY_TAB_SONGS = 0
const val LIBRARY_TAB_ALBUMS = 1
const val LIBRARY_TAB_ARTISTS = 2

const val PLAYLIST_DETAIL_NONE = "none"
const val PLAYLIST_DETAIL_LOCAL = "local"
const val PLAYLIST_DETAIL_LB = "lb"
const val PLAYLIST_DETAIL_CF = "cf"

private val VALID_SORT_OPTION_NAMES = setOf("TITLE", "ARTIST", "ALBUM", "GENRE", "DATE_ADDED")
private val VALID_VIEW_MODE_NAMES = setOf("FLAT", "ALBUM_GROUPS")
private val VALID_PLAYLIST_DETAIL_KINDS = setOf(
    PLAYLIST_DETAIL_NONE,
    PLAYLIST_DETAIL_LOCAL,
    PLAYLIST_DETAIL_LB,
    PLAYLIST_DETAIL_CF
)

data class LibraryDisplaySettings(
    val sortOptionName: String = DEFAULT_SORT_OPTION_NAME,
    val viewModeName: String = DEFAULT_VIEW_MODE_NAME
)

data class UiNavSnapshot(
    val navIndex: Int = NAV_LIBRARY,
    val libraryTab: Int = LIBRARY_TAB_SONGS,
    val libraryArtistName: String? = null,
    val libraryAlbumName: String? = null,
    val playlistDetailKind: String = PLAYLIST_DETAIL_NONE,
    val playlistLocalId: Long? = null,
    val playlistLbMbid: String? = null
)

data class PrunedLibraryStack(
    val albumName: String?,
    val artistName: String?
)

object LibraryUiPreferencesCodec {
    fun sanitizeSortOptionName(name: String?): String =
        name?.takeIf { it in VALID_SORT_OPTION_NAMES } ?: DEFAULT_SORT_OPTION_NAME

    fun sanitizeViewModeName(name: String?): String =
        name?.takeIf { it in VALID_VIEW_MODE_NAMES } ?: DEFAULT_VIEW_MODE_NAME

    fun sanitizeNavIndex(index: Int?): Int =
        index?.takeIf { it in NAV_LIBRARY..NAV_SETTINGS } ?: NAV_LIBRARY

    fun sanitizeLibraryTab(tab: Int?): Int =
        tab?.takeIf { it in LIBRARY_TAB_SONGS..LIBRARY_TAB_ARTISTS } ?: LIBRARY_TAB_SONGS

    fun blankToNull(value: String?): String? =
        value?.trim()?.takeIf { it.isNotEmpty() }

    fun sanitizeNavSnapshot(
        navIndex: Int? = null,
        libraryTab: Int? = null,
        libraryArtistName: String? = null,
        libraryAlbumName: String? = null,
        playlistDetailKind: String? = null,
        playlistLocalId: Long? = null,
        playlistLbMbid: String? = null
    ): UiNavSnapshot {
        val kind = playlistDetailKind?.takeIf { it in VALID_PLAYLIST_DETAIL_KINDS }
            ?: PLAYLIST_DETAIL_NONE
        return pruneOrphanPlaylistDetail(
            UiNavSnapshot(
                navIndex = sanitizeNavIndex(navIndex),
                libraryTab = sanitizeLibraryTab(libraryTab),
                libraryArtistName = blankToNull(libraryArtistName),
                libraryAlbumName = blankToNull(libraryAlbumName),
                playlistDetailKind = kind,
                playlistLocalId = playlistLocalId?.takeIf { it > 0L },
                playlistLbMbid = blankToNull(playlistLbMbid)
            )
        )
    }

    fun pruneOrphanPlaylistDetail(snapshot: UiNavSnapshot): UiNavSnapshot {
        return when (snapshot.playlistDetailKind) {
            PLAYLIST_DETAIL_LOCAL -> if (snapshot.playlistLocalId == null) {
                snapshot.copy(
                    playlistDetailKind = PLAYLIST_DETAIL_NONE,
                    playlistLbMbid = null
                )
            } else {
                snapshot.copy(playlistLbMbid = null)
            }
            PLAYLIST_DETAIL_LB -> if (snapshot.playlistLbMbid == null) {
                snapshot.copy(
                    playlistDetailKind = PLAYLIST_DETAIL_NONE,
                    playlistLocalId = null
                )
            } else {
                snapshot.copy(playlistLocalId = null)
            }
            PLAYLIST_DETAIL_CF -> snapshot.copy(
                playlistLocalId = null,
                playlistLbMbid = null
            )
            else -> snapshot.copy(
                playlistDetailKind = PLAYLIST_DETAIL_NONE,
                playlistLocalId = null,
                playlistLbMbid = null
            )
        }
    }

    fun pruneLibraryStack(
        albumName: String?,
        artistName: String?,
        albumExists: (String) -> Boolean,
        artistExists: (String) -> Boolean
    ): PrunedLibraryStack {
        val album = albumName?.takeIf(albumExists)
        val artist = artistName?.takeIf(artistExists)
        return PrunedLibraryStack(albumName = album, artistName = artist)
    }
}
