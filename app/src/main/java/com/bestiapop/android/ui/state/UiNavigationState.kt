package com.bestiapop.android.ui.state

import com.bestiapop.android.data.preferences.NAV_LIBRARY
import com.bestiapop.android.data.preferences.PrunedLibraryStack
import com.bestiapop.android.data.preferences.UiNavSnapshot

data class LibraryBrowseStack(
    val artistName: String? = null,
    val albumName: String? = null,
    val genreName: String? = null
) {
    fun openAlbum(name: String, fromNestedParent: Boolean): LibraryBrowseStack =
        if (fromNestedParent) {
            copy(albumName = name)
        } else {
            LibraryBrowseStack(albumName = name)
        }

    fun openArtist(name: String): LibraryBrowseStack =
        LibraryBrowseStack(artistName = name)

    fun openGenre(name: String): LibraryBrowseStack =
        LibraryBrowseStack(genreName = name)

    fun closeAlbum(): LibraryBrowseStack = copy(albumName = null)

    fun closeArtist(): LibraryBrowseStack = copy(artistName = null, albumName = null)

    fun closeGenre(): LibraryBrowseStack = copy(genreName = null, albumName = null)

    fun pop(): LibraryBrowseStack = when {
        albumName != null -> closeAlbum()
        artistName != null -> closeArtist()
        genreName != null -> closeGenre()
        else -> this
    }

    fun renameAlbum(sourceKey: String, targetKey: String): LibraryBrowseStack =
        if (albumName.equals(sourceKey, ignoreCase = true)) copy(albumName = targetKey) else this

    fun applyPruned(pruned: PrunedLibraryStack): LibraryBrowseStack = copy(
        albumName = pruned.albumName,
        artistName = pruned.artistName,
        genreName = pruned.genreName
    )
}

data class UiNavigationState(
    val selectedNavIndex: Int = NAV_LIBRARY,
    val libraryBrowseFilter: LibraryBrowseFilter = LibraryBrowseFilter.SONGS,
    val libraryStack: LibraryBrowseStack = LibraryBrowseStack(),
    val playlistDetail: PlaylistDetailNav = PlaylistDetailNav.None
) {
    fun toSnapshot(persistedNavIndex: Int = selectedNavIndex): UiNavSnapshot = UiNavSnapshot(
        navIndex = persistedNavIndex,
        browseFilterName = libraryBrowseFilter.name,
        libraryArtistName = libraryStack.artistName,
        libraryAlbumName = libraryStack.albumName,
        libraryGenreName = libraryStack.genreName,
        playlistDetailKind = playlistDetail.kindName(),
        playlistLocalId = playlistDetail.localIdOrNull(),
        playlistLbMbid = playlistDetail.lbMbidOrNull()
    )

    companion object {
        fun fromSnapshot(snapshot: UiNavSnapshot): UiNavigationState = UiNavigationState(
            selectedNavIndex = snapshot.navIndex,
            libraryBrowseFilter = LibraryBrowseFilter.entries
                .find { it.name == snapshot.browseFilterName }
                ?: LibraryBrowseFilter.SONGS,
            libraryStack = LibraryBrowseStack(
                artistName = snapshot.libraryArtistName,
                albumName = snapshot.libraryAlbumName,
                genreName = snapshot.libraryGenreName
            ),
            playlistDetail = PlaylistDetailNav.fromSnapshot(snapshot)
        )
    }
}
