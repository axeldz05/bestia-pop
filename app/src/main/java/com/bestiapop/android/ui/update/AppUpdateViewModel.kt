package com.bestiapop.android.ui.update

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bestiapop.android.BuildConfig
import com.bestiapop.android.data.update.ApkUpdateInstaller
import com.bestiapop.android.data.update.AppUpdateCheckStore
import com.bestiapop.android.data.update.AppUpdateInfo
import com.bestiapop.android.data.update.GitHubUpdateClient
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import kotlin.coroutines.cancellation.CancellationException

sealed class AppUpdateUiState {
    data object Idle : AppUpdateUiState()
    data object Checking : AppUpdateUiState()
    data object UpToDate : AppUpdateUiState()
    data class Available(val info: AppUpdateInfo) : AppUpdateUiState()
    data class Downloading(val info: AppUpdateInfo, val progress: Float?) : AppUpdateUiState()
    data class ReadyToInstall(val info: AppUpdateInfo, val apkFile: File) : AppUpdateUiState()
    data class NeedsInstallPermission(val info: AppUpdateInfo) : AppUpdateUiState()
    data class Error(val message: String) : AppUpdateUiState()
}

class AppUpdateViewModel(app: Application) : AndroidViewModel(app) {

    private val store = AppUpdateCheckStore(app)
    private val client = GitHubUpdateClient(
        repository = BuildConfig.GITHUB_REPOSITORY.trim(),
        userAgent = USER_AGENT
    )

    private val _state = MutableStateFlow<AppUpdateUiState>(AppUpdateUiState.Idle)
    val state: StateFlow<AppUpdateUiState> = _state.asStateFlow()

    private var checkJob: Job? = null
    private var downloadJob: Job? = null
    private var pendingInstall: AppUpdateInfo? = null

    fun maybeCheckOnLaunch() {
        if (BuildConfig.DEBUG) return
        if (BuildConfig.GITHUB_REPOSITORY.isBlank()) return
        viewModelScope.launch {
            val last = store.lastCheckAtMs()
            if (System.currentTimeMillis() - last < CHECK_INTERVAL_MS) return@launch
            runCheck(silent = true)
        }
    }

    fun checkNow() {
        if (BuildConfig.GITHUB_REPOSITORY.isBlank()) {
            _state.value = AppUpdateUiState.Error(
                "Falta GITHUB_REPOSITORY en github-release.properties"
            )
            return
        }
        runCheck(silent = false)
    }

    fun confirmUpdate() {
        val info = when (val current = _state.value) {
            is AppUpdateUiState.Available -> current.info
            is AppUpdateUiState.NeedsInstallPermission -> current.info
            else -> return
        }
        val ctx = getApplication<Application>()
        if (!ApkUpdateInstaller.canInstallPackages(ctx)) {
            pendingInstall = info
            _state.value = AppUpdateUiState.NeedsInstallPermission(info)
            return
        }
        startDownload(info)
    }

    fun onReturnedFromUnknownSources() {
        val info = pendingInstall
            ?: (_state.value as? AppUpdateUiState.NeedsInstallPermission)?.info
            ?: return
        val ctx = getApplication<Application>()
        if (!ApkUpdateInstaller.canInstallPackages(ctx)) {
            _state.value = AppUpdateUiState.Available(info)
            return
        }
        startDownload(info)
    }

    fun markInstallLaunched() {
        pendingInstall = null
        _state.value = AppUpdateUiState.Idle
    }

    fun dismiss() {
        downloadJob?.cancel()
        checkJob?.cancel()
        pendingInstall = null
        _state.value = AppUpdateUiState.Idle
    }

    private fun runCheck(silent: Boolean) {
        checkJob?.cancel()
        checkJob = viewModelScope.launch {
            if (!silent) _state.value = AppUpdateUiState.Checking
            val result = client.fetchLatest()
            result.fold(
                onSuccess = { info ->
                    store.setLastCheckAtMs(System.currentTimeMillis())
                    _state.value = when {
                        info.versionCode > BuildConfig.VERSION_CODE -> AppUpdateUiState.Available(info)
                        silent -> AppUpdateUiState.Idle
                        else -> AppUpdateUiState.UpToDate
                    }
                },
                onFailure = { error ->
                    _state.value = if (silent) {
                        AppUpdateUiState.Idle
                    } else {
                        AppUpdateUiState.Error(error.message ?: "No se pudo buscar actualización")
                    }
                }
            )
        }
    }

    private fun startDownload(info: AppUpdateInfo) {
        downloadJob?.cancel()
        downloadJob = viewModelScope.launch {
            _state.value = AppUpdateUiState.Downloading(info, progress = null)
            val dest = ApkUpdateInstaller.updateFile(getApplication())
            try {
                val result = ApkUpdateInstaller.download(
                    url = info.apkUrl,
                    dest = dest,
                    userAgent = USER_AGENT,
                    onProgress = { progress ->
                        _state.value = AppUpdateUiState.Downloading(info, progress)
                    }
                )
                result.fold(
                    onSuccess = { file ->
                        _state.value = AppUpdateUiState.ReadyToInstall(info, file)
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
        private const val CHECK_INTERVAL_MS = 12L * 60L * 60L * 1000L
        private val USER_AGENT = "BestiaPop/${BuildConfig.VERSION_NAME}"
    }
}
