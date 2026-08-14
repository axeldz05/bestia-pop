package com.bestiapop.android.ui.state

sealed interface LoadPhase {
    data object Idle : LoadPhase
    data object Loading : LoadPhase
    data object Loaded : LoadPhase
    data class Error(val message: String) : LoadPhase
}

data class LoadableUiState<T>(
    val data: T,
    val phase: LoadPhase = LoadPhase.Idle
) {
    val isLoading: Boolean
        get() = phase is LoadPhase.Loading

    val isLoaded: Boolean
        get() = phase is LoadPhase.Loaded

    val errorMessage: String?
        get() = (phase as? LoadPhase.Error)?.message

    fun loading(data: T = this.data): LoadableUiState<T> =
        copy(data = data, phase = LoadPhase.Loading)

    fun success(data: T = this.data): LoadableUiState<T> =
        copy(data = data, phase = LoadPhase.Loaded)

    fun failure(message: String, data: T = this.data): LoadableUiState<T> =
        copy(data = data, phase = LoadPhase.Error(message))

    fun idle(data: T = this.data): LoadableUiState<T> =
        copy(data = data, phase = LoadPhase.Idle)
}
