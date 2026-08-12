package com.bestiapop.android.ui.update

import android.app.Application
import android.content.Context
import android.content.Intent
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
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import kotlin.coroutines.cancellation.CancellationException

internal fun interface AppUpdateGateway {
    suspend fun fetchReleases(): Result<List<AppRelease>>
}

internal interface AppUpdateStore {
    suspend fun lastCheckAtMs(): Long
    suspend fun setLastCheckAtMs(epochMs: Long)
    suspend fun cachedNotes(versionName: String): String?
    suspend fun setCachedNotes(versionName: String, notes: String)
}

internal fun interface AppUpdateClock {
    fun nowMs(): Long
}

internal interface AppUpdateInstallerBoundary {
    fun canInstallPackages(context: Context): Boolean
    fun updateFile(context: Context): File

    suspend fun download(
        context: Context,
        url: String,
        dest: File,
        userAgent: String,
        onProgress: (Float?) -> Unit
    ): Result<File>

    fun unknownSourcesIntent(context: Context): Intent
    fun launchInstaller(context: Context, apk: File): Result<Unit>
}

internal data class AppUpdateDependencies(
    val repository: String,
    val gateway: AppUpdateGateway,
    val store: AppUpdateStore,
    val clock: AppUpdateClock,
    val isDebugBuild: Boolean,
    val installer: AppUpdateInstallerBoundary,
    val currentVersionCode: Int,
    val currentVersionName: String,
    val userAgent: String
) {
    companion object {
        fun production(app: Application): AppUpdateDependencies {
            val repository = BuildConfig.GITHUB_REPOSITORY.trim()
            val store = AppUpdateCheckStore(app)
            val userAgent = "BestiaPop/${BuildConfig.VERSION_NAME}"
            return AppUpdateDependencies(
                repository = repository,
                gateway = AppUpdateGateway {
                    GitHubUpdateClient(repository, userAgent).fetchReleases()
                },
                store = object : AppUpdateStore {
                    override suspend fun lastCheckAtMs(): Long = store.lastCheckAtMs()

                    override suspend fun setLastCheckAtMs(epochMs: Long) {
                        store.setLastCheckAtMs(epochMs)
                    }

                    override suspend fun cachedNotes(versionName: String): String? =
                        store.cachedNotes(versionName)

                    override suspend fun setCachedNotes(versionName: String, notes: String) {
                        store.setCachedNotes(versionName, notes)
                    }
                },
                clock = AppUpdateClock(System::currentTimeMillis),
                isDebugBuild = BuildConfig.DEBUG,
                installer = ProductionAppUpdateInstaller,
                currentVersionCode = BuildConfig.VERSION_CODE,
                currentVersionName = BuildConfig.VERSION_NAME,
                userAgent = userAgent
            )
        }
    }
}

private object ProductionAppUpdateInstaller : AppUpdateInstallerBoundary {
    override fun canInstallPackages(context: Context): Boolean =
        ApkUpdateInstaller.canInstallPackages(context)

    override fun updateFile(context: Context): File = ApkUpdateInstaller.updateFile(context)

    override suspend fun download(
        context: Context,
        url: String,
        dest: File,
        userAgent: String,
        onProgress: (Float?) -> Unit
    ): Result<File> = ApkUpdateInstaller.download(context, url, dest, userAgent, onProgress)

    override fun unknownSourcesIntent(context: Context): Intent =
        ApkUpdateInstaller.unknownSourcesIntent(context)

    override fun launchInstaller(context: Context, apk: File): Result<Unit> = runCatching {
        context.startActivity(ApkUpdateInstaller.installIntent(context, apk))
    }
}

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

class AppUpdateViewModel internal constructor(
    app: Application,
    private val dependencies: AppUpdateDependencies
) : AndroidViewModel(app) {

    constructor(app: Application) : this(app, AppUpdateDependencies.production(app))

    private val _state = MutableStateFlow<AppUpdateUiState>(AppUpdateUiState.Idle)
    val state: StateFlow<AppUpdateUiState> = _state.asStateFlow()

    private val _notes = MutableStateFlow(AppReleaseNotesState())
    val notes: StateFlow<AppReleaseNotesState> = _notes.asStateFlow()

    val repositoryUrl: String? =
        dependencies.repository.ifBlank { null }?.let(GitHubReleaseUrls::repoUrl)

    private var checkJob: Job? = null
    private var downloadJob: Job? = null
    private var pendingInstall: AppRelease? = null

    fun maybeCheckOnLaunch() {
        if (dependencies.isDebugBuild) return
        if (dependencies.repository.isBlank()) return
        viewModelScope.launch {
            loadCachedNotes()
            val last = dependencies.store.lastCheckAtMs()
            if (dependencies.clock.nowMs() - last < CHECK_INTERVAL_MS) return@launch
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
            if (dependencies.repository.isBlank()) {
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
        if (!dependencies.installer.canInstallPackages(getApplication())) {
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
        if (!dependencies.installer.canInstallPackages(getApplication())) {
            _state.value = AppUpdateUiState.Available(release)
            return
        }
        startDownload(release)
    }

    fun unknownSourcesIntent(): Intent =
        dependencies.installer.unknownSourcesIntent(getApplication())

    fun launchInstaller(apk: File) {
        dependencies.installer.launchInstaller(getApplication(), apk).fold(
            onSuccess = { markInstallLaunched() },
            onFailure = { error ->
                _state.value = AppUpdateUiState.Error(
                    error.message ?: "No se pudo abrir el instalador"
                )
            }
        )
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
        val cached = dependencies.store.cachedNotes(dependencies.currentVersionName) ?: return
        _notes.update { it.copy(currentNotes = it.currentNotes ?: cached) }
    }

    private suspend fun fetchSelection(): Result<AppReleaseSelection> =
        dependencies.gateway.fetchReleases().map { releases ->
            dependencies.store.setLastCheckAtMs(dependencies.clock.nowMs())
            AppReleaseSelection.from(
                releases = releases,
                currentVersionCode = dependencies.currentVersionCode,
                currentVersionName = dependencies.currentVersionName
            ).also { selection ->
                selection.current?.notes?.let {
                    dependencies.store.setCachedNotes(dependencies.currentVersionName, it)
                }
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
            val context = getApplication<Application>()
            val dest = dependencies.installer.updateFile(context)
            try {
                val result = dependencies.installer.download(
                    context = context,
                    url = apkUrl,
                    dest = dest,
                    userAgent = dependencies.userAgent,
                    onProgress = { progress ->
                        _state.value = AppUpdateUiState.Downloading(release, progress)
                    }
                )
                ensureActive()
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
        internal const val CHECK_INTERVAL_MS = 12L * 60L * 60L * 1000L
    }
}
