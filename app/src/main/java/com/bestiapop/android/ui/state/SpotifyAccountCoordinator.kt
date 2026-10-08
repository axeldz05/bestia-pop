package com.bestiapop.android.ui.state

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.bestiapop.android.data.model.OnlineCatalogTrack
import com.bestiapop.android.data.model.PlaylistImportSummary
import com.bestiapop.android.data.network.SpotifyApiClient
import com.bestiapop.android.data.network.SpotifyOAuthHelper
import com.bestiapop.android.data.network.SpotifyPlaylistSummary
import com.bestiapop.android.data.network.SpotifyUserProfile
import com.bestiapop.android.data.preferences.SpotifyAuthData
import com.bestiapop.android.data.preferences.SpotifyPreferencesRepository
import com.bestiapop.android.domain.repository.IMusicRepository
import com.bestiapop.android.domain.usecase.ImportPlaylistFromLinkUseCase
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class SpotifyImportProgress(
    val playlistName: String,
    val message: String,
)

sealed interface SpotifyAccountUiState {
    data class Disconnected(
        val customClientId: String? = null,
        val error: String? = null,
    ) : SpotifyAccountUiState

    data object Connecting : SpotifyAccountUiState

    data class Connected(
        val profile: SpotifyUserProfile,
        val playlists: List<SpotifyPlaylistSummary>,
        val isLoadingPlaylists: Boolean = false,
        val importProgress: SpotifyImportProgress? = null,
        val lastImportSummary: PlaylistImportSummary? = null,
        val error: String? = null,
    ) : SpotifyAccountUiState
}

class SpotifyAccountCoordinator(
    private val scope: CoroutineScope,
    private val repository: IMusicRepository,
    private val spotifyPrefs: SpotifyPreferencesRepository,
    private val enqueuePendingDownloads: suspend (Long, List<OnlineCatalogTrack>, Boolean) -> Unit,
    private val toast: (String) -> Unit,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val _uiState = MutableStateFlow<SpotifyAccountUiState>(SpotifyAccountUiState.Disconnected())
    val uiState: StateFlow<SpotifyAccountUiState> = _uiState.asStateFlow()

    private val importPlaylistFromLinkUseCase = ImportPlaylistFromLinkUseCase(repository)
    private val tokenRefreshMutex = Mutex()
    private var loadJob: Job? = null
    private var importJob: Job? = null

    init {
        scope.launch(ioDispatcher) {
            val auth = spotifyPrefs.getAuthData()
            if (auth.isConnected) {
                restoreSession(auth)
            } else {
                _uiState.value = SpotifyAccountUiState.Disconnected(customClientId = auth.customClientId)
            }
        }
    }

    private suspend fun restoreSession(auth: SpotifyAuthData) {
        val validToken = ensureValidToken(auth)
        if (validToken == null) {
            _uiState.value = SpotifyAccountUiState.Disconnected(customClientId = auth.customClientId)
            return
        }

        val profileResult = SpotifyApiClient.fetchUserProfile(validToken)
        profileResult.fold(
            onSuccess = { profile ->
                spotifyPrefs.saveUserProfile(profile.displayName, profile.id)
                _uiState.value =
                    SpotifyAccountUiState.Connected(
                        profile = profile,
                        playlists = emptyList(),
                        isLoadingPlaylists = true,
                    )
                loadPlaylists(validToken, profile)
            },
            onFailure = {
                _uiState.value =
                    SpotifyAccountUiState.Disconnected(
                        customClientId = auth.customClientId,
                        error = "Sesión expirada o no válida",
                    )
            },
        )
    }

    private fun loadPlaylists(
        token: String,
        profile: SpotifyUserProfile,
    ) {
        loadJob?.cancel()
        loadJob =
            scope.launch(ioDispatcher) {
                val playlistsResult = SpotifyApiClient.fetchUserPlaylists(token)
                playlistsResult.fold(
                    onSuccess = { list ->
                        val current = _uiState.value as? SpotifyAccountUiState.Connected
                        if (current != null) {
                            _uiState.value =
                                current.copy(
                                    playlists = list,
                                    isLoadingPlaylists = false,
                                    error = null,
                                )
                        } else {
                            _uiState.value =
                                SpotifyAccountUiState.Connected(
                                    profile = profile,
                                    playlists = list,
                                    isLoadingPlaylists = false,
                                )
                        }
                    },
                    onFailure = { error ->
                        val current = _uiState.value as? SpotifyAccountUiState.Connected
                        if (current != null) {
                            _uiState.value =
                                current.copy(
                                    isLoadingPlaylists = false,
                                    error = error.message ?: "No se pudieron cargar las playlists",
                                )
                        }
                    },
                )
            }
    }

    fun startConnect(context: Context) {
        scope.launch(ioDispatcher) {
            val auth = spotifyPrefs.getAuthData()
            val clientId = auth.customClientId?.ifBlank { null } ?: SpotifyOAuthHelper.DEFAULT_CLIENT_ID
            val verifier = SpotifyOAuthHelper.generateCodeVerifier()
            val challenge = SpotifyOAuthHelper.generateCodeChallenge(verifier)
            val state = SpotifyOAuthHelper.generateState()
            spotifyPrefs.savePendingAuthSession(verifier = verifier, state = state)

            _uiState.value = SpotifyAccountUiState.Connecting

            val authUrl = SpotifyOAuthHelper.buildAuthorizationUrl(clientId, challenge, state)
            val intent =
                Intent(Intent.ACTION_VIEW, Uri.parse(authUrl)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            try {
                context.startActivity(intent)
            } catch (e: Exception) {
                spotifyPrefs.clearPendingAuthSession()
                _uiState.value =
                    SpotifyAccountUiState.Disconnected(
                        customClientId = auth.customClientId,
                        error = "No se pudo abrir el navegador para iniciar sesión: ${e.message}",
                    )
            }
        }
    }

    fun handleAuthCallback(
        code: String?,
        state: String?,
        error: String?,
    ): Job {
        if (!error.isNullOrBlank()) {
            return scope.launch(ioDispatcher) {
                spotifyPrefs.clearPendingAuthSession()
                val auth = spotifyPrefs.getAuthData()
                _uiState.value =
                    SpotifyAccountUiState.Disconnected(
                        customClientId = auth.customClientId,
                        error = "Autorización cancelada o denegada: $error",
                    )
            }
        }

        if (code.isNullOrBlank()) {
            return scope.launch(ioDispatcher) {
                spotifyPrefs.clearPendingAuthSession()
            }
        }

        return scope.launch(ioDispatcher) {
            _uiState.value = SpotifyAccountUiState.Connecting
            val auth = spotifyPrefs.getAuthData()
            val clientId = auth.customClientId?.ifBlank { null } ?: SpotifyOAuthHelper.DEFAULT_CLIENT_ID
            val verifier = auth.pendingCodeVerifier
            val pendingState = auth.pendingState

            if (pendingState.isNullOrBlank() || state.isNullOrBlank() || pendingState != state) {
                spotifyPrefs.clearPendingAuthSession()
                _uiState.value =
                    SpotifyAccountUiState.Disconnected(
                        customClientId = auth.customClientId,
                        error = "Error de validación de seguridad (CSRF). Por favor reintentá iniciar sesión.",
                    )
                return@launch
            }

            if (verifier.isNullOrBlank()) {
                spotifyPrefs.clearPendingAuthSession()
                _uiState.value =
                    SpotifyAccountUiState.Disconnected(
                        customClientId = auth.customClientId,
                        error = "Error interno de validación PKCE",
                    )
                return@launch
            }

            val exchangeResult = SpotifyOAuthHelper.exchangeAuthorizationCode(clientId, code, verifier)
            spotifyPrefs.clearPendingAuthSession()
            exchangeResult.fold(
                onSuccess = { tokens ->
                    spotifyPrefs.saveTokens(
                        accessToken = tokens.accessToken,
                        refreshToken = tokens.refreshToken,
                        expiresInSeconds = tokens.expiresInSeconds,
                    )
                    val updatedAuth = spotifyPrefs.getAuthData()
                    restoreSession(updatedAuth)
                    toast("Cuenta de Spotify conectada")
                },
                onFailure = { e ->
                    _uiState.value =
                        SpotifyAccountUiState.Disconnected(
                            customClientId = auth.customClientId,
                            error = e.message ?: "Error al canjear el código de autorización",
                        )
                },
            )
        }
    }

    private suspend fun ensureValidToken(auth: SpotifyAuthData): String? {
        if (!auth.isTokenExpired && !auth.accessToken.isNullOrBlank()) {
            return auth.accessToken
        }

        return tokenRefreshMutex.withLock {
            val freshAuth = spotifyPrefs.getAuthData()
            if (!freshAuth.isTokenExpired && !freshAuth.accessToken.isNullOrBlank()) {
                return@withLock freshAuth.accessToken
            }

            val refreshToken = freshAuth.refreshToken ?: return@withLock null
            val clientId = freshAuth.customClientId?.ifBlank { null } ?: SpotifyOAuthHelper.DEFAULT_CLIENT_ID
            val refreshResult = SpotifyOAuthHelper.refreshAccessToken(clientId, refreshToken)

            refreshResult.fold(
                onSuccess = { newTokens ->
                    spotifyPrefs.saveTokens(
                        accessToken = newTokens.accessToken,
                        refreshToken = newTokens.refreshToken ?: refreshToken,
                        expiresInSeconds = newTokens.expiresInSeconds,
                    )
                    newTokens.accessToken
                },
                onFailure = { error ->
                    val msg = error.message.orEmpty()
                    if (msg.contains("invalid_grant", ignoreCase = true) ||
                        msg.contains("HTTP 400", ignoreCase = true) ||
                        msg.contains("HTTP 401", ignoreCase = true)
                    ) {
                        spotifyPrefs.clearAuth()
                        _uiState.value =
                            SpotifyAccountUiState.Disconnected(
                                customClientId = freshAuth.customClientId,
                                error = "Sesión expirada o revocada. Por favor iniciá sesión nuevamente.",
                            )
                    }
                    null
                },
            )
        }
    }

    fun refreshPlaylists() {
        scope.launch(ioDispatcher) {
            val auth = spotifyPrefs.getAuthData()
            val token = ensureValidToken(auth)
            val current = _uiState.value as? SpotifyAccountUiState.Connected
            if (token != null && current != null) {
                _uiState.value = current.copy(isLoadingPlaylists = true, error = null)
                loadPlaylists(token, current.profile)
            }
        }
    }

    fun importPlaylist(
        playlist: SpotifyPlaylistSummary,
        downloadAfterImport: Boolean,
    ) {
        importJob?.cancel()
        importJob =
            scope.launch(ioDispatcher) {
                val current = _uiState.value as? SpotifyAccountUiState.Connected ?: return@launch
                val auth = spotifyPrefs.getAuthData()
                val token = ensureValidToken(auth)
                if (token == null) {
                    _uiState.value = current.copy(error = "Sesión expirada. Volvé a conectar tu cuenta.")
                    return@launch
                }

                _uiState.value =
                    current.copy(
                        importProgress =
                            SpotifyImportProgress(
                                playlistName = playlist.name,
                                message = "Obteniendo pistas de «${playlist.name}» (${playlist.trackCount} temas)…",
                            ),
                        lastImportSummary = null,
                        error = null,
                    )

                val tracksResult =
                    SpotifyApiClient.fetchPlaylistAllTracks(
                        accessToken = token,
                        playlistId = playlist.id,
                        playlistName = playlist.name,
                        coverUrl = playlist.coverUrl,
                        onProgress = { loaded ->
                            val currentProg = _uiState.value as? SpotifyAccountUiState.Connected
                            if (currentProg != null) {
                                _uiState.value =
                                    currentProg.copy(
                                        importProgress =
                                            SpotifyImportProgress(
                                                playlistName = playlist.name,
                                                message = "Cargando canciones: $loaded de ${playlist.trackCount}…",
                                            ),
                                    )
                            }
                        },
                    )

                tracksResult.fold(
                    onSuccess = { data ->
                        _uiState.value =
                            current.copy(
                                importProgress =
                                    SpotifyImportProgress(
                                        playlistName = playlist.name,
                                        message = "Creando playlist local y vinculando canciones...",
                                    ),
                            )

                        try {
                            val summary = importPlaylistFromLinkUseCase.execute(data, isDownloading = downloadAfterImport)

                            if (downloadAfterImport && summary.pendingStreamCount > 0) {
                                _uiState.value =
                                    current.copy(
                                        importProgress =
                                            SpotifyImportProgress(
                                                playlistName = playlist.name,
                                                message = "Encolando ${summary.pendingStreamCount} descargas…",
                                            ),
                                    )
                                val pending = repository.getPlaylistPendingTracks(summary.playlistId)
                                if (pending.isNotEmpty()) {
                                    enqueuePendingDownloads(
                                        summary.playlistId,
                                        pending.map { it.toOnlineCatalogTrack() },
                                        true,
                                    )
                                }
                            }

                            _uiState.value =
                                current.copy(
                                    importProgress = null,
                                    lastImportSummary = summary,
                                )
                            toast("Playlist «${summary.playlistTitle}» importada")
                        } catch (e: Exception) {
                            _uiState.value =
                                current.copy(
                                    importProgress = null,
                                    error = "Error al crear la playlist local: ${e.message}",
                                )
                        }
                    },
                    onFailure = { e ->
                        _uiState.value =
                            current.copy(
                                importProgress = null,
                                error = "Error al obtener canciones de Spotify: ${e.message}",
                            )
                    },
                )
            }
    }

    fun importLikedSongs(downloadAfterImport: Boolean) {
        importJob?.cancel()
        importJob =
            scope.launch(ioDispatcher) {
                val current = _uiState.value as? SpotifyAccountUiState.Connected ?: return@launch
                val auth = spotifyPrefs.getAuthData()
                val token = ensureValidToken(auth)
                if (token == null) {
                    _uiState.value = current.copy(error = "Sesión expirada. Volvé a conectar tu cuenta.")
                    return@launch
                }

                _uiState.value =
                    current.copy(
                        importProgress =
                            SpotifyImportProgress(
                                playlistName = "Tus me gusta",
                                message = "Obteniendo tus canciones favoritas de Spotify…",
                            ),
                        lastImportSummary = null,
                        error = null,
                    )

                val tracksResult =
                    SpotifyApiClient.fetchLikedSongsAsPlaylist(
                        accessToken = token,
                        onProgress = { loaded ->
                            val currentProg = _uiState.value as? SpotifyAccountUiState.Connected
                            if (currentProg != null) {
                                _uiState.value =
                                    currentProg.copy(
                                        importProgress =
                                            SpotifyImportProgress(
                                                playlistName = "Tus me gusta",
                                                message = "Cargando canciones favoritas: $loaded pistas…",
                                            ),
                                    )
                            }
                        },
                    )

                tracksResult.fold(
                    onSuccess = { data ->
                        _uiState.value =
                            current.copy(
                                importProgress =
                                    SpotifyImportProgress(
                                        playlistName = "Tus me gusta",
                                        message = "Organizando canciones en tu biblioteca...",
                                    ),
                            )

                        try {
                            val summary = importPlaylistFromLinkUseCase.execute(data, isDownloading = downloadAfterImport)

                            if (downloadAfterImport && summary.pendingStreamCount > 0) {
                                val pending = repository.getPlaylistPendingTracks(summary.playlistId)
                                if (pending.isNotEmpty()) {
                                    enqueuePendingDownloads(
                                        summary.playlistId,
                                        pending.map { it.toOnlineCatalogTrack() },
                                        true,
                                    )
                                }
                            }

                            _uiState.value =
                                current.copy(
                                    importProgress = null,
                                    lastImportSummary = summary,
                                )
                            toast("«Tus me gusta» importados")
                        } catch (e: Exception) {
                            _uiState.value =
                                current.copy(
                                    importProgress = null,
                                    error = "Error al crear la playlist local: ${e.message}",
                                )
                        }
                    },
                    onFailure = { e ->
                        _uiState.value =
                            current.copy(
                                importProgress = null,
                                error = "Error al obtener canciones favoritas: ${e.message}",
                            )
                    },
                )
            }
    }

    fun saveCustomClientId(clientId: String?) {
        scope.launch(ioDispatcher) {
            spotifyPrefs.saveCustomClientId(clientId)
            val auth = spotifyPrefs.getAuthData()
            if (_uiState.value is SpotifyAccountUiState.Disconnected) {
                _uiState.value = SpotifyAccountUiState.Disconnected(customClientId = auth.customClientId)
            }
        }
    }

    fun dismissLastSummary() {
        val current = _uiState.value as? SpotifyAccountUiState.Connected ?: return
        _uiState.value = current.copy(lastImportSummary = null)
    }

    fun disconnect() {
        scope.launch(ioDispatcher) {
            spotifyPrefs.clearAuth()
            val auth = spotifyPrefs.getAuthData()
            _uiState.value = SpotifyAccountUiState.Disconnected(customClientId = auth.customClientId)
            toast("Sesión de Spotify cerrada")
        }
    }
}
