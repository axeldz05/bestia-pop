package com.bestiapop.android.ui.state

import androidx.compose.runtime.Immutable
import com.bestiapop.android.data.model.Song
import com.bestiapop.android.data.network.LyricsTranslationResult
import com.bestiapop.android.data.network.LyricsTranslationService
import com.bestiapop.android.data.network.LyricsTranslationSource
import com.bestiapop.android.data.preferences.JapanesePhoneticMode
import com.bestiapop.android.data.preferences.LyricsPreferencesRepository
import com.bestiapop.android.data.preferences.LyricsSettings
import com.bestiapop.android.data.util.LyricsPhoneticProcessor
import com.bestiapop.android.data.util.SongPathNormalizer
import com.bestiapop.android.domain.repository.IMusicRepository
import java.util.Collections
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Bundled translation and phonetic status for lyrics displays like [com.bestiapop.android.ui.screens.NowPlayingScreen].
 */
@Immutable
data class LyricsTranslationState(
    val isTranslationActive: Boolean = false,
    val isFetchingTranslation: Boolean = false,
    val translationSource: LyricsTranslationSource? = null,
    val pendingGoogleTranslatePrompt: Boolean = false,
    val romanizationVersion: Int = 0,
    val translationVersion: Int = 0
)

/**
 * Coordinator for lyrics retrieval (local and remote), translation caching, romanization,
 * and lyrics preferences. Keeps [com.bestiapop.android.ui.MusicPlayerViewModel] lean.
 */
class LyricsCoordinator(
    private val scope: CoroutineScope,
    private val repository: IMusicRepository,
    private val lyricsPreferences: LyricsPreferencesRepository,
    private val updateCurrentSongLyrics: (songId: Long, lyrics: String?) -> Unit,
    private val updateCurrentItemLyrics: (lyrics: String?) -> Unit
) {
    val settings: StateFlow<LyricsSettings> = lyricsPreferences.settingsFlow.stateIn(
        scope = scope,
        started = SharingStarted.Eagerly,
        initialValue = LyricsSettings()
    )

    private val _translationState = MutableStateFlow(LyricsTranslationState())
    val translationState: StateFlow<LyricsTranslationState> = _translationState.asStateFlow()

    val isTranslationActive: StateFlow<Boolean> = _translationState
        .map { it.isTranslationActive }
        .stateIn(scope, SharingStarted.Eagerly, false)

    val isFetchingTranslation: StateFlow<Boolean> = _translationState
        .map { it.isFetchingTranslation }
        .stateIn(scope, SharingStarted.Eagerly, false)

    val translationSource: StateFlow<LyricsTranslationSource?> = _translationState
        .map { it.translationSource }
        .stateIn(scope, SharingStarted.Eagerly, null)

    val pendingGoogleTranslatePrompt: StateFlow<Boolean> = _translationState
        .map { it.pendingGoogleTranslatePrompt }
        .stateIn(scope, SharingStarted.Eagerly, false)

    val romanizationVersion: StateFlow<Int> = _translationState
        .map { it.romanizationVersion }
        .stateIn(scope, SharingStarted.Eagerly, 0)

    val translationVersion: StateFlow<Int> = _translationState
        .map { it.translationVersion }
        .stateIn(scope, SharingStarted.Eagerly, 0)

    private val _isFetching = MutableStateFlow(false)
    val isFetching: StateFlow<Boolean> = _isFetching.asStateFlow()

    private val _fetchError = MutableStateFlow<String?>(null)
    val fetchError: StateFlow<String?> = _fetchError.asStateFlow()

    private val translationCache: MutableMap<Long, LyricsTranslationResult> = Collections.synchronizedMap(
        object : LinkedHashMap<Long, LyricsTranslationResult>(32, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, LyricsTranslationResult>?): Boolean {
                return size > 50
            }
        }
    )

    private val romanizationCache: MutableMap<Long, List<String>> = Collections.synchronizedMap(
        object : LinkedHashMap<Long, List<String>>(32, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, List<String>>?): Boolean {
                return size > 50
            }
        }
    )

    private val lyricsLookupAttempted = object : LinkedHashSet<Long>() {
        override fun add(element: Long): Boolean {
            if (size >= 300) {
                val first = iterator().next()
                remove(first)
            }
            return super.add(element)
        }
    }

    fun setPhoneticGuideEnabled(enabled: Boolean) {
        scope.launch { lyricsPreferences.setPhoneticGuideEnabled(enabled) }
    }

    fun setJapanesePhoneticMode(mode: JapanesePhoneticMode) {
        scope.launch { lyricsPreferences.setJapanesePhoneticMode(mode) }
    }

    fun setAskBeforeGoogleTranslate(ask: Boolean) {
        scope.launch { lyricsPreferences.setAskBeforeGoogleTranslate(ask) }
    }

    fun cancelGoogleTranslatePrompt() {
        _translationState.update { it.copy(pendingGoogleTranslatePrompt = false) }
    }

    fun confirmGoogleTranslate(song: Song, lines: List<String>) {
        _translationState.update { it.copy(pendingGoogleTranslatePrompt = false) }
        translateWithGoogleInternal(song, lines)
    }

    fun toggleLyricsTranslation(song: Song, lines: List<String>) {
        if (_translationState.value.isTranslationActive) {
            _translationState.update { it.copy(isTranslationActive = false) }
            return
        }

        val cached = translationCache[song.id]
        if (cached != null) {
            _translationState.update {
                it.copy(
                    isTranslationActive = true,
                    translationSource = LyricsTranslationSource(cached.sourceName, cached.sourceUrl)
                )
            }
            return
        }

        scope.launch {
            _translationState.update { it.copy(isFetchingTranslation = true) }
            val community = LyricsTranslationService.fetchCommunityTranslation(song.artist, song.title)
            if (community != null) {
                translationCache[song.id] = community
                _translationState.update {
                    it.copy(
                        isFetchingTranslation = false,
                        isTranslationActive = true,
                        translationSource = LyricsTranslationSource(community.sourceName, community.sourceUrl),
                        translationVersion = it.translationVersion + 1
                    )
                }
            } else {
                if (settings.value.askBeforeGoogleTranslate) {
                    _translationState.update {
                        it.copy(
                            isFetchingTranslation = false,
                            pendingGoogleTranslatePrompt = true
                        )
                    }
                } else {
                    _translationState.update { it.copy(isFetchingTranslation = false) }
                    translateWithGoogleInternal(song, lines)
                }
            }
        }
    }

    private fun translateWithGoogleInternal(song: Song, lines: List<String>) {
        scope.launch {
            _translationState.update { it.copy(isFetchingTranslation = true) }
            val gResult = LyricsTranslationService.translateWithGoogle(lines)
            if (gResult != null) {
                translationCache[song.id] = gResult
                _translationState.update {
                    it.copy(
                        isFetchingTranslation = false,
                        isTranslationActive = true,
                        translationSource = LyricsTranslationSource(gResult.sourceName, gResult.sourceUrl),
                        translationVersion = it.translationVersion + 1
                    )
                }
            } else {
                _translationState.update { it.copy(isFetchingTranslation = false) }
            }
        }
    }

    fun ensureRomanization(songId: Long, lines: List<String>) {
        if (romanizationCache.containsKey(songId)) return
        val hasNonLatin = lines.any { LyricsPhoneticProcessor.hasNonLatinScript(it) }
        if (!hasNonLatin) {
            romanizationCache[songId] = emptyList()
            return
        }

        scope.launch {
            val res = LyricsTranslationService.fetchRomanization(lines)
            romanizationCache[songId] = res?.lines ?: emptyList()
            if (res != null) {
                _translationState.update { it.copy(romanizationVersion = it.romanizationVersion + 1) }
            }
        }
    }

    fun getTranslatedLines(songId: Long): List<String>? = translationCache[songId]?.lines

    fun getRomanizedLines(songId: Long): List<String>? =
        romanizationCache[songId]?.takeIf { it.isNotEmpty() }

    fun clearFetchError() {
        _fetchError.value = null
    }

    fun ensureLyrics(song: Song, force: Boolean = false) {
        if (song.id == 0L) return
        val currentLyrics = song.lyrics?.trim()?.takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
        if (currentLyrics != null && !force) return
        if (!force && lyricsLookupAttempted.contains(song.id)) return
        if (_isFetching.value) return

        val isRemoteStream = song.id < 0L

        lyricsLookupAttempted.add(song.id)
        scope.launch {
            _isFetching.value = true
            _fetchError.value = null
            try {
                if (!isRemoteStream) {
                    val localLyrics = repository.findLocalLyrics(song)?.trim()?.takeIf {
                        it.isNotBlank() && !it.equals("null", ignoreCase = true)
                    }
                    if (localLyrics != null) {
                        repository.updateSongLyrics(song.id, localLyrics)
                        updateCurrentSongLyrics(song.id, localLyrics)
                        return@launch
                    }
                }

                val onlineLyrics = repository.fetchSongLyrics(song)?.trim()?.takeIf {
                    it.isNotBlank() && !it.equals("null", ignoreCase = true)
                }
                if (onlineLyrics != null) {
                    if (isRemoteStream) {
                        updateCurrentItemLyrics(onlineLyrics)
                    } else {
                        repository.updateSongLyrics(song.id, onlineLyrics)
                        updateCurrentSongLyrics(song.id, onlineLyrics)
                        repository.saveCompanionLrc(song, onlineLyrics)
                    }
                } else {
                    _fetchError.value = "No se encontró letra"
                }
            } catch (_: Exception) {
                _fetchError.value = "Error al buscar letra"
            } finally {
                _isFetching.value = false
            }
        }
    }

    fun retryFetchLyrics(song: Song) {
        ensureLyrics(song, force = true)
    }

    fun enhanceSongMetadataAndLyrics(song: Song) {
        requestMetadataEnhancement(song, force = true)
    }

    private fun songNeedsMetadataEnhancement(song: Song): Boolean {
        val artMissing = !SongPathNormalizer.hasUsableArtwork(song.artworkUri)
        val durationMissing = song.durationMs <= 0
        return artMissing || durationMissing
    }

    private fun requestMetadataEnhancement(song: Song, force: Boolean = false) {
        if (!force && !songNeedsMetadataEnhancement(song)) return
        scope.launch {
            repository.enhanceSongMetadataAndLyrics(song)
        }
    }

    fun updateSongLyrics(songId: Long, lyrics: String?) {
        scope.launch {
            repository.updateSongLyrics(songId, lyrics)
            updateCurrentSongLyrics(songId, lyrics)
        }
    }

    fun fetchSongLyrics(song: Song, onResult: (String?) -> Unit) {
        scope.launch {
            val lyrics = repository.fetchSongLyrics(song)?.trim()?.takeIf {
                it.isNotBlank() && !it.equals("null", ignoreCase = true)
            }
            onResult(lyrics)
        }
    }
}
