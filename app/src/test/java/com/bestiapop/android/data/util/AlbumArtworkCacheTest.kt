package com.bestiapop.android.data.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AlbumArtworkCacheTest {

    @Test
    fun remembersUsableArtForRealAlbum() {
        val cache = AlbumArtworkCache()
        cache.remember("Radiohead", "Pablo Honey", "file:///cover.jpg")
        assertEquals("file:///cover.jpg", cache.lookup("radiohead", "Pablo Honey"))
        cache.remember("Radiohead", "Pablo Honey", "file:///other.jpg")
        assertEquals("file:///cover.jpg", cache.lookup("Radiohead", "pablo honey"))
    }

    @Test
    fun skipsPlaceholderArtistAndGenericAlbum() {
        val cache = AlbumArtworkCache()
        cache.remember("Unknown Artist", "Pablo Honey", "file:///cover.jpg")
        assertNull(cache.lookup("Unknown Artist", "Pablo Honey"))
        cache.remember("Radiohead", "Unknown Album", "file:///cover.jpg")
        assertNull(cache.lookup("Radiohead", "Unknown Album"))
        cache.remember("Radiohead", "Pablo Honey", "content://media/external/audio/albumart/1")
        assertNull(cache.lookup("Radiohead", "Pablo Honey"))
    }
}
