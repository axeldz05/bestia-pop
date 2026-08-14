package com.bestiapop.android.ui.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LoadableUiStateTest {

    @Test
    fun transitions_preserveContentUnlessReplacementIsExplicit() {
        val loaded = LoadableUiState(emptyList<String>()).success(listOf("old"))
        val loading = loaded.loading()
        val failed = loading.failure("offline")

        assertEquals(listOf("old"), loading.data)
        assertTrue(loading.isLoading)
        assertEquals(listOf("old"), failed.data)
        assertEquals("offline", failed.errorMessage)

        val reset = failed.idle(emptyList())
        assertTrue(reset.phase is LoadPhase.Idle)
        assertFalse(reset.isLoaded)
        assertNull(reset.errorMessage)
        assertTrue(reset.data.isEmpty())
    }
}
