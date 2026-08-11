package com.bestiapop.android.ui.update

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bestiapop.android.BuildConfig
import com.bestiapop.android.data.update.ApkUpdateInstaller
import com.bestiapop.android.data.update.AppRelease
import com.bestiapop.android.data.update.AppReleaseSelection
import com.bestiapop.android.data.update.AppUpdateCheckStore
import com.bestiapop.android.data.update.GitHubReleaseUrls
import com.bestiapop.android.data.update.GitHubUpdateClient
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import kotlin.coroutines.cancellation.CancellationException

/** Install flow (dialogs over any screen). Browsing release notes lives in [AppReleaseNotesState]. */
sealed class AppUpdateUiState {
    data object Idle : AppUpdateUiState()
    data class Available(val release: AppRelease) : AppUpdateUiState()
    data class Downloading(val release: AppRelease, val progress: Float?) : AppUpdateUiState()
    data class ReadyToInstall(val release: AppRelease, val apkFile: File) : AppUpdateUiState()
    data class NeedsInstallPermission(val release: AppRelease) : AppUpdateUiState()
    data class Error(val message: String) : AppUpdateUiState()
}

/** Ajustes → Actualización: notes of the installed build plus every newer release. */
data class AppReleaseNotesState(
    val loading: Boolean = false,
    val currentNotes: String? = null,
    val newer: List<AppRelease> = emptyList(),
    val checked: Boolean = false,
    val error: String? = null
)

class AppUpdateViewModel(app: Application) : AndroidViewModel(app) {

    private val repository = BuildConfig.GITHUB_REPOSITORY.trim()
    private val store = AppUpdateCheckStore(app)
    private val client = GitHubUpdateClient(repository = repository, userAgent = USER_AGENT)

    private val _state = MutableStateFlow<AppUpdateUiState>(AppUpdateUiState.Idle)
    val state: StateFlow<AppUpdateUiState> = _state.asStateFlow()

    private val _notes = MutableStateFlow(AppReleaseNotesState())
    val notes: StateFlow<AppReleaseNotesState> = _notes.asStateFlow()

    val repositoryUrl: String? = repository.ifBlank { null }?.let(GitHubReleaseUrls::repoUrl)

    private var checkJob: Job? = null
    private var downloadJob: Job? = null
    private var pendingInstall: AppRelease? = null

    fun maybeCheckOnLaunch() {
        if (BuildConfig.DEBUG) return
        if (repository.isBlank()) return
        viewModelScope.launch {
            loadCachedNotes()
            val last = store.lastCheckAtMs()
            if (System.currentTimeMillis() - last < CHECK_INTERVAL_MS) return@launch
            val selection = fetchSelection().getOrNull() ?: return@launch
            applySelection(selection)
            selection.updateTarget?.let { _state.value = AppUpdateUiState.Available(it) }
        }
    }

    /** Ajustes → Actualización: cache first, then network unless already fetched and not [force]d. */
    fun refreshReleases(force: Boolean = false) {
        if (checkJob?.isActive == true) return
        checkJob = viewModelScope.launch {
            loadCachedNotes()
            if (!force && _notes.value.checked) return@launch
            if (repository.isBlank()) {
                _notes.update { it.copy(error = MISSING_REPOSITORY) }
                return@launch
            }
            _notes.update { it.copy(loading = true, error = null) }
            fetchSelection().fold(
                onSuccess = ::applySelection,
                onFailure = { error ->
                    _notes.update {
                        it.copy(loading = false, error = error.message ?: CHECK_FAILED)
                    }
                }
            )
        }
    }

    fun startUpdate(release: AppRelease) {
        if (release.apkUrl.isNullOrBlank()) {
            _state.value = AppUpdateUiState.Error("Este release no tiene APK para instalar.")
            return
        }
        if (!ApkUpdateInstaller.canInstallPackages(getApplication())) {
            pendingInstall = release
            _state.value = AppUpdateUiState.NeedsInstallPermission(release)
            return
        }
        startDownload(release)
    }

    fun confirmUpdate() {
        val release = when (val current = _state.value) {
            is AppUpdateUiState.Available -> current.release
            is AppUpdateUiState.NeedsInstallPermission -> current.release
            else -> return
        }
        startUpdate(release)
    }

    fun onReturnedFromUnknownSources() {
        val release = pendingInstall
            ?: (_state.value as? AppUpdateUiState.NeedsInstallPermission)?.release
            ?: return
        if (!ApkUpdateInstaller.canInstallPackages(getApplication())) {
            _state.value = AppUpdateUiState.Available(release)
            return
        }
        startDownload(release)
    }

    fun markInstallLaunched() {
        pendingInstall = null
        _state.value = AppUpdateUiState.Idle
    }

    fun dismiss() {
        downloadJob?.cancel()
        pendingInstall = null
        _state.value = AppUpdateUiState.Idle
    }

    private suspend fun loadCachedNotes() {
        if (_notes.value.currentNotes != null) return
        val cached = store.cachedNotes(BuildConfig.VERSION_NAME) ?: return
        _notes.update { it.copy(currentNotes = it.currentNotes ?: cached) }
    }

    private suspend fun fetchSelection(): Result<AppReleaseSelection> =
        client.fetchReleases().map { releases ->
            store.setLastCheckAtMs(System.currentTimeMillis())
            AppReleaseSelection.from(
                releases = releases,
                currentVersionCode = BuildConfig.VERSION_CODE,
                currentVersionName = BuildConfig.VERSION_NAME
            ).also { selection ->
                selection.current?.notes?.let { store.setCachedNotes(BuildConfig.VERSION_NAME, it) }
            }
        }

    private fun applySelection(selection: AppReleaseSelection) {
        _notes.update { current ->
            current.copy(
                loading = false,
                currentNotes = selection.current?.notes ?: current.currentNotes,
                newer = selection.newer,
                checked = true,
                error = null
            )
        }
    }

    private fun startDownload(release: AppRelease) {
        val apkUrl = release.apkUrl ?: return
        downloadJob?.cancel()
        downloadJob = viewModelScope.launch {
            _state.value = AppUpdateUiState.Downloading(release, progress = null)
            val dest = ApkUpdateInstaller.updateFile(getApplication())
            try {
                val result = ApkUpdateInstaller.download(
                    url = apkUrl,
                    dest = dest,
                    userAgent = USER_AGENT,
                    onProgress = { progress ->
                        _state.value = AppUpdateUiState.Downloading(release, progress)
                    }
                )
                result.fold(
                    onSuccess = { file ->
                        _state.value = AppUpdateUiState.ReadyToInstall(release, file)
                    },
                    onFailure = { error ->
                        _state.value = AppUpdateUiState.Error(
                            error.message ?: "No se pudo descargar la actualización"
                        )
                    }
                )
            } catch (e: CancellationException) {
                throw e
            }
        }
    }

    companion object {
        const val MISSING_REPOSITORY = "Falta GITHUB_REPOSITORY en github-release.properties"
        private const val CHECK_FAILED = "No se pudo buscar actualización"
        private const val CHECK_INTERVAL_MS = 12L * 60L * 60L * 1000L
        private val USER_AGENT = "BestiaPop/${BuildConfig.VERSION_NAME}"
    }
}
