package com.bestiapop.android.data.preferences

const val DEFAULT_SORT_OPTION_NAME = "TITLE"
const val DEFAULT_SORT_DIRECTION_NAME = "ASC"
const val DEFAULT_VIEW_MODE_NAME = "ALBUM_GROUPS"
const val DEFAULT_BROWSE_FILTER_NAME = "SONGS"

const val NAV_LIBRARY = 0
const val NAV_PLAYLISTS = 1
const val NAV_DOWNLOADS = 2
const val NAV_WIFI = 3
const val NAV_SETTINGS = 4

/** Legacy tab ints (pre–browse-filter). Kept for DataStore migration only. */
const val LIBRARY_TAB_SONGS = 0
const val LIBRARY_TAB_ALBUMS = 1
const val LIBRARY_TAB_ARTISTS = 2

const val PLAYLIST_DETAIL_NONE = "none"
const val PLAYLIST_DETAIL_LOCAL = "local"
const val PLAYLIST_DETAIL_LB = "lb"
const val PLAYLIST_DETAIL_CF = "cf"

private val VALID_SORT_OPTION_NAMES = setOf("TITLE", "ARTIST", "ALBUM", "GENRE", "DATE_ADDED")
private val VALID_SORT_DIRECTION_NAMES = setOf("ASC", "DESC")
private val VALID_VIEW_MODE_NAMES = setOf("FLAT", "ALBUM_GROUPS")
private val VALID_BROWSE_FILTER_NAMES = setOf("SONGS", "ALBUMS", "ARTISTS", "GENRES", "RECENT")
private val VALID_PLAYLIST_DETAIL_KINDS = setOf(
    PLAYLIST_DETAIL_NONE,
    PLAYLIST_DETAIL_LOCAL,
    PLAYLIST_DETAIL_LB,
    PLAYLIST_DETAIL_CF
)

data class LibraryDisplaySettings(
    val sortOptionName: String = DEFAULT_SORT_OPTION_NAME,
    val sortDirectionName: String = DEFAULT_SORT_DIRECTION_NAME,
    val viewModeName: String = DEFAULT_VIEW_MODE_NAME
)

data class UiNavSnapshot(
    val navIndex: Int = NAV_LIBRARY,
    val browseFilterName: String = DEFAULT_BROWSE_FILTER_NAME,
    val libraryArtistName: String? = null,
    val libraryAlbumName: String? = null,
    val libraryGenreName: String? = null,
    val playlistDetailKind: String = PLAYLIST_DETAIL_NONE,
    val playlistLocalId: Long? = null,
    val playlistLbMbid: String? = null
)

data class PrunedLibraryStack(
    val albumName: String?,
    val artistName: String?,
    val genreName: String? = null
)

object LibraryUiPreferencesCodec {
    fun sanitizeSortOptionName(name: String?): String =
        name?.takeIf { it in VALID_SORT_OPTION_NAMES } ?: DEFAULT_SORT_OPTION_NAME

    /** DATE_ADDED / RECENT-style sorts default DESC; others ASC. */
    fun defaultSortDirectionName(sortOptionName: String?): String =
        if (sanitizeSortOptionName(sortOptionName) == "DATE_ADDED") "DESC" else "ASC"

    fun sanitizeSortDirectionName(name: String?, sortOptionName: String? = null): String =
        name?.takeIf { it in VALID_SORT_DIRECTION_NAMES }
            ?: defaultSortDirectionName(sortOptionName)

    fun sanitizeViewModeName(name: String?): String =
        name?.takeIf { it in VALID_VIEW_MODE_NAMES } ?: DEFAULT_VIEW_MODE_NAME

    fun sanitizeNavIndex(index: Int?): Int =
        index?.takeIf { it in NAV_LIBRARY..NAV_SETTINGS } ?: NAV_LIBRARY

    /** @deprecated Prefer [sanitizeBrowseFilterName]. */
    fun sanitizeLibraryTab(tab: Int?): Int =
        tab?.takeIf { it in LIBRARY_TAB_SONGS..LIBRARY_TAB_ARTISTS } ?: LIBRARY_TAB_SONGS

    /**
     * Prefer explicit filter name; else map legacy tab 0/1/2 → SONGS/ALBUMS/ARTISTS.
     * Unknown names → SONGS.
     */
    fun sanitizeBrowseFilterName(name: String?, legacyTab: Int? = null): String {
        name?.takeIf { it in VALID_BROWSE_FILTER_NAMES }?.let { return it }
        return when (sanitizeLibraryTab(legacyTab)) {
            LIBRARY_TAB_ALBUMS -> "ALBUMS"
            LIBRARY_TAB_ARTISTS -> "ARTISTS"
            else -> DEFAULT_BROWSE_FILTER_NAME
        }
    }

    fun browseFilterNameToLegacyTab(name: String): Int = when (name) {
        "ALBUMS" -> LIBRARY_TAB_ALBUMS
        "ARTISTS" -> LIBRARY_TAB_ARTISTS
        else -> LIBRARY_TAB_SONGS
    }

    fun blankToNull(value: String?): String? =
        value?.trim()?.takeIf { it.isNotEmpty() }

    fun sanitizeNavSnapshot(
        navIndex: Int? = null,
        browseFilterName: String? = null,
        libraryTab: Int? = null,
        libraryArtistName: String? = null,
        libraryAlbumName: String? = null,
        libraryGenreName: String? = null,
        playlistDetailKind: String? = null,
        playlistLocalId: Long? = null,
        playlistLbMbid: String? = null
    ): UiNavSnapshot {
        val kind = playlistDetailKind?.takeIf { it in VALID_PLAYLIST_DETAIL_KINDS }
            ?: PLAYLIST_DETAIL_NONE
        return pruneOrphanPlaylistDetail(
            UiNavSnapshot(
                navIndex = sanitizeNavIndex(navIndex),
                browseFilterName = sanitizeBrowseFilterName(browseFilterName, libraryTab),
                libraryArtistName = blankToNull(libraryArtistName),
                libraryAlbumName = blankToNull(libraryAlbumName),
                libraryGenreName = blankToNull(libraryGenreName),
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
        genreName: String? = null,
        albumExists: (String) -> Boolean,
        artistExists: (String) -> Boolean,
        genreExists: (String) -> Boolean = { false }
    ): PrunedLibraryStack {
        val album = albumName?.takeIf(albumExists)
        val artist = artistName?.takeIf(artistExists)
        val genre = genreName?.takeIf(genreExists)
        return PrunedLibraryStack(albumName = album, artistName = artist, genreName = genre)
    }
}
