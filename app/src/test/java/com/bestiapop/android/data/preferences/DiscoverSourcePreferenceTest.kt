package com.bestiapop.android.data.preferences

import org.junit.Assert.assertEquals
import org.junit.Test

class DiscoverSourcePreferenceTest {

    @Test
    fun parseDiscoverSourcePreference_validValues() {
        assertEquals(DiscoverSourcePreference.BOTH, parseDiscoverSourcePreference("BOTH"))
        assertEquals(DiscoverSourcePreference.DEEZER, parseDiscoverSourcePreference("DEEZER"))
        assertEquals(DiscoverSourcePreference.LISTENBRAINZ, parseDiscoverSourcePreference("LISTENBRAINZ"))
    }

    @Test
    fun parseDiscoverSourcePreference_nullOrInvalidFallbackToBoth() {
        assertEquals(DiscoverSourcePreference.BOTH, parseDiscoverSourcePreference(null))
        assertEquals(DiscoverSourcePreference.BOTH, parseDiscoverSourcePreference(""))
        assertEquals(DiscoverSourcePreference.BOTH, parseDiscoverSourcePreference("INVALID_SOURCE"))
    }
}
