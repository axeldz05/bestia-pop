package com.bestiapop.android.ui.state

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class UiStateFlowsTest {

    @Test
    fun mapToUiState_startsOnSubscription_andAllowsEagerOverride() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val source = MutableStateFlow(1)
        val subscribed = source.mapToUiState(backgroundScope, 0) { it * 2 }
        val eager = source.mapToUiState(
            scope = backgroundScope,
            initial = 0,
            started = SharingStarted.Eagerly
        ) { it * 3 }

        source.value = 2
        runCurrent()
        assertEquals(0, subscribed.value)
        assertEquals(6, eager.value)

        val collector = backgroundScope.launch(dispatcher) { subscribed.collect {} }
        runCurrent()
        assertEquals(4, subscribed.value)

        collector.cancel()
    }
}
