package com.bestiapop.android.data.stream

import org.junit.Assert.assertEquals
import org.junit.Test

class BestiaPopMediaCacheTest {

    @Test
    fun cacheKey_formatsDeterministicKey() {
        assertEquals("yt:abc123xyz", BestiaPopMediaCache.cacheKey("abc123xyz"))
        assertEquals("yt:some-video-id", BestiaPopMediaCache.cacheKey("some-video-id"))
    }
}
