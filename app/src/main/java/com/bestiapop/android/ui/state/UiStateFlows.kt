package com.bestiapop.android.ui.state

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

const val UI_STATE_STOP_TIMEOUT_MS = 5_000L

fun <T> Flow<T>.stateInUi(
    scope: CoroutineScope,
    initial: T,
    started: SharingStarted = SharingStarted.WhileSubscribed(UI_STATE_STOP_TIMEOUT_MS)
): StateFlow<T> = stateIn(scope, started, initial)

fun <T, R> Flow<T>.mapToUiState(
    scope: CoroutineScope,
    initial: R,
    started: SharingStarted = SharingStarted.WhileSubscribed(UI_STATE_STOP_TIMEOUT_MS),
    transform: (T) -> R
): StateFlow<R> = map(transform).stateInUi(scope, initial, started)
