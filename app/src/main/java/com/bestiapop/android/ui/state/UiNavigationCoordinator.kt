package com.bestiapop.android.ui.state

import com.bestiapop.android.data.model.Playlist
import com.bestiapop.android.data.model.PlaylistMessages
import com.bestiapop.android.data.model.Song
import com.bestiapop.android.data.preferences.LibraryPreferencesRepository
import com.bestiapop.android.data.preferences.LibraryStackLookups
import com.bestiapop.android.data.preferences.LibraryUiPreferencesCodec
import com.bestiapop.android.data.preferences.NAV_DOWNLOADS
import com.bestiapop.android.data.preferences.NAV_LIBRARY
import com.bestiapop.android.data.preferences.NAV_PLAYLISTS
import com.bestiapop.android.data.preferences.NAV_SETTINGS
import com.bestiapop.android.data.preferences.UiNavSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Coordinates UI navigation states, tab changes, library drill-downs, settings jumps,
 * and persistence of the user navigation snapshot.
 */
class UiNavigationCoordinator(
    private val scope: CoroutineScope,
    private val libraryPreferences: LibraryPreferencesRepository,
    private val onClearSearchQuery: () -> Unit,
    private val onCloseDiscoverSessionUi: () -> Unit,
    private val onOpenListenBrainzPlaylist: (String) -> Unit,
    private val onOpenCfRecommendations: () -> Unit,
    private val onRestoreListenBrainzPlaylist: suspend (String) -> Boolean,
    private val onRestoreCfRecommendations: suspend () -> Boolean,
    private val isLbPlaylistDetailLoaded: () -> Boolean,
    private val isCfRecommendationsLoaded: () -> Boolean,
    private val toast: (String) -> Unit
) {
    private val _navigation = MutableStateFlow(UiNavigationState())
    val navigation: StateFlow<UiNavigationState> = _navigation.asStateFlow()

    private val _selectedNavIndex = MutableStateFlow(NAV_LIBRARY)
    val selectedNavIndex: StateFlow<Int> = _selectedNavIndex.asStateFlow()

    private var uiPrefsHydrated = false

    /** Tab to persist. Transient jumps move the live index without touching this. */
    private var persistedNavIndex = NAV_LIBRARY

    /** Tab to come back to after a transient jump into Settings. */
    private var navIndexBeforeTransient: Int? = null

    private val _pendingSettingsSection = MutableStateFlow<String?>(null)
    val pendingSettingsSection: StateFlow<String?> = _pendingSettingsSection.asStateFlow()

    fun updateNavigation(
        transform: (UiNavigationState) -> UiNavigationState
    ): Boolean {
        while (true) {
            val current = _navigation.value
            val updated = transform(current)
            if (updated == current) return false
            if (_navigation.compareAndSet(current, updated)) {
                if (_selectedNavIndex.value != updated.selectedNavIndex) {
                    _selectedNavIndex.value = updated.selectedNavIndex
                }
                return true
            }
        }
    }

    fun setSelectedNavIndex(index: Int, persist: Boolean = true) {
        val sanitized = LibraryUiPreferencesCodec.sanitizeNavIndex(index)
        navIndexBeforeTransient = null
        if (_selectedNavIndex.value == sanitized && _navigation.value.selectedNavIndex == sanitized) {
            if (sanitized == NAV_PLAYLISTS) maybeRestoreDiscoverDetail()
            return
        }
        _selectedNavIndex.value = sanitized
        updateNavigation { it.copy(selectedNavIndex = sanitized) }
        if (persist) {
            persistedNavIndex = sanitized
            persistNavSnapshot()
        }
        if (sanitized == NAV_PLAYLISTS) maybeRestoreDiscoverDetail()
    }

    fun openDownloadsTabTransient() {
        updateNavigation { it.copy(selectedNavIndex = NAV_DOWNLOADS) }
    }

    fun openDownloadSettings() {
        openSettingsSection("downloads")
    }

    fun openPlaybackSettings() {
        openSettingsSection("playback")
    }

    private fun openSettingsSection(section: String) {
        navIndexBeforeTransient = _navigation.value.selectedNavIndex
        _pendingSettingsSection.value = section
        updateNavigation { it.copy(selectedNavIndex = NAV_SETTINGS) }
    }

    /** True when it consumed a pending transient jump and restored the previous tab. */
    fun returnFromTransientSettings(): Boolean {
        val previous = navIndexBeforeTransient ?: return false
        navIndexBeforeTransient = null
        updateNavigation { it.copy(selectedNavIndex = previous) }
        return true
    }

    fun consumePendingSettingsSection(): String? {
        val v = _pendingSettingsSection.value
        _pendingSettingsSection.value = null
        return v
    }

    fun setLibraryBrowseFilter(filter: LibraryBrowseFilter) {
        if (updateNavigation { it.copy(libraryBrowseFilter = filter, libraryStack = LibraryBrowseStack.EMPTY) }) {
            persistNavSnapshot()
        }
    }

    fun openLibraryAlbum(name: String, fromNestedParent: Boolean = false) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        if (updateNavigation {
                it.copy(libraryStack = it.libraryStack.openAlbum(trimmed, fromNestedParent))
            }
        ) {
            persistNavSnapshot()
        }
    }

    fun openLibraryArtist(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        if (updateNavigation { it.copy(libraryStack = it.libraryStack.openArtist(trimmed)) }) {
            persistNavSnapshot()
        }
    }

    fun openLibraryGenre(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        if (updateNavigation { it.copy(libraryStack = it.libraryStack.openGenre(trimmed)) }) {
            persistNavSnapshot()
        }
    }

    fun closeLibraryAlbum() {
        if (updateNavigation { it.copy(libraryStack = it.libraryStack.closeAlbum()) }) {
            persistNavSnapshot()
        }
    }

    fun closeLibraryArtist() {
        if (updateNavigation { it.copy(libraryStack = it.libraryStack.closeArtist()) }) {
            persistNavSnapshot()
        }
    }

    fun closeLibraryGenre() {
        if (updateNavigation { it.copy(libraryStack = it.libraryStack.closeGenre()) }) {
            persistNavSnapshot()
        }
    }

    fun popLibraryNested() {
        if (updateNavigation { it.copy(libraryStack = it.libraryStack.pop()) }) {
            persistNavSnapshot()
        }
    }

    fun renameRestoredLibraryAlbum(sourceKey: String, targetKey: String) {
        if (updateNavigation {
                it.copy(libraryStack = it.libraryStack.renameAlbum(sourceKey, targetKey))
            }
        ) {
            persistNavSnapshot()
        }
    }

    fun openLocalPlaylist(id: Long) {
        onCloseDiscoverSessionUi()
        onClearSearchQuery()
        persistedNavIndex = NAV_LIBRARY
        _selectedNavIndex.value = NAV_LIBRARY
        updateNavigation {
            it.copy(
                selectedNavIndex = NAV_LIBRARY,
                libraryBrowseFilter = LibraryBrowseFilter.PLAYLISTS,
                libraryStack = LibraryBrowseStack(),
                playlistDetail = PlaylistDetailNav.Local(id)
            )
        }
        persistNavSnapshot()
    }

    fun openListenBrainzPlaylistDetail(mbid: String) {
        onCloseDiscoverSessionUi()
        updateNavigation { it.copy(playlistDetail = PlaylistDetailNav.ListenBrainz(mbid)) }
        persistNavSnapshot()
        onOpenListenBrainzPlaylist(mbid)
    }

    fun openCfRecommendationsDetail() {
        onCloseDiscoverSessionUi()
        updateNavigation { it.copy(playlistDetail = PlaylistDetailNav.CfRecommendations) }
        persistNavSnapshot()
        onOpenCfRecommendations()
    }

    fun closePlaylistDetail() {
        onCloseDiscoverSessionUi()
        if (updateNavigation { it.copy(playlistDetail = PlaylistDetailNav.None) }) {
            persistNavSnapshot()
        }
    }

    fun dismissDiscoverDetails() {
        val detail = _navigation.value.playlistDetail
        if (detail is PlaylistDetailNav.ListenBrainz || detail is PlaylistDetailNav.CfRecommendations) {
            closePlaylistDetail()
        } else {
            onCloseDiscoverSessionUi()
        }
    }

    fun applyNavSnapshot(nav: UiNavSnapshot) {
        _navigation.value = UiNavigationState.fromSnapshot(nav)
        _selectedNavIndex.value = nav.navIndex
        persistedNavIndex = nav.navIndex
        uiPrefsHydrated = true
    }

    fun persistNavSnapshot() {
        if (!uiPrefsHydrated) return
        // persistedNavIndex, not the live one: a transient tab would otherwise become the next cold-start tab.
        val snapshot = _navigation.value.toSnapshot(persistedNavIndex)
        scope.launch(Dispatchers.IO) { libraryPreferences.setNavSnapshot(snapshot) }
    }

    fun pruneRestoredLibraryStack(songs: List<Song>) {
        val lookups = LibraryStackLookups.fromSongs(songs)
        val stack = _navigation.value.libraryStack
        val pruned = LibraryUiPreferencesCodec.pruneLibraryStack(
            albumName = stack.albumName,
            artistName = stack.artistName,
            genreName = stack.genreName,
            albumExists = lookups.albumExists,
            artistExists = lookups.artistExists,
            genreExists = lookups.genreExists
        )
        if (updateNavigation { it.copy(libraryStack = it.libraryStack.applyPruned(pruned)) }) {
            persistNavSnapshot()
        }
    }

    fun pruneRestoredLocalPlaylist(playlists: List<Playlist>) {
        val detail = _navigation.value.playlistDetail as? PlaylistDetailNav.Local ?: return
        if (playlists.none { it.id == detail.id }) {
            updateNavigation { it.copy(playlistDetail = PlaylistDetailNav.None) }
            persistNavSnapshot()
        }
    }

    fun maybeRestoreDiscoverDetail() {
        val detail = _navigation.value.playlistDetail
        if (detail !is PlaylistDetailNav.ListenBrainz && detail !is PlaylistDetailNav.CfRecommendations) {
            return
        }
        val needsFetch = when (detail) {
            is PlaylistDetailNav.ListenBrainz -> !isLbPlaylistDetailLoaded()
            PlaylistDetailNav.CfRecommendations -> !isCfRecommendationsLoaded()
            else -> false
        }
        if (!needsFetch) return
        scope.launch { restoreDiscoverDetailOrFallback() }
    }

    private suspend fun restoreDiscoverDetailOrFallback() {
        when (val detail = _navigation.value.playlistDetail) {
            is PlaylistDetailNav.ListenBrainz -> {
                val ok = onRestoreListenBrainzPlaylist(detail.mbid)
                if (!ok) fallbackDiscoverRestore(announce = true)
            }

            PlaylistDetailNav.CfRecommendations -> {
                val ok = onRestoreCfRecommendations()
                if (!ok) fallbackDiscoverRestore(announce = true)
            }

            else -> Unit
        }
    }

    private fun fallbackDiscoverRestore(announce: Boolean) {
        closePlaylistDetail()
        if (announce) toast(PlaylistMessages.openFailed)
    }
}
