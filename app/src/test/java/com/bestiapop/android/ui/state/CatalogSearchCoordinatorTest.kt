package com.bestiapop.android.ui.state

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CatalogSearchCoordinatorTest {
    @Test
    fun recordDraftToRecent_savesNonBlankDraft() {
        val testScope = TestScope()
        val savedSearches = mutableListOf<String>()

        val coordinator =
            CatalogSearchCoordinator(
                scope = testScope,
                isOnline = { true },
                onNotifyToast = {},
                onSaveRecentSearch = { savedSearches.add(it) },
            )

        coordinator.setDraft("  Queen Live At Wembley  ")
        coordinator.recordDraftToRecent()

        assertEquals(1, savedSearches.size)
        assertEquals("Queen Live At Wembley", savedSearches.first())
    }

    @Test
    fun recordDraftToRecent_ignoresBlankDraft() {
        val testScope = TestScope()
        val savedSearches = mutableListOf<String>()

        val coordinator =
            CatalogSearchCoordinator(
                scope = testScope,
                isOnline = { true },
                onNotifyToast = {},
                onSaveRecentSearch = { savedSearches.add(it) },
            )

        coordinator.setDraft("    ")
        coordinator.recordDraftToRecent()

        assertTrue(savedSearches.isEmpty())
    }
}
