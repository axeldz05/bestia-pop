package com.bestiapop.android.data.util

import org.junit.Assert.assertEquals
import org.junit.Test

class AlbumTrackNumbersTest {

    @Test
    fun encode_singleDiscIsPlainTrack() {
        assertEquals(5, encodeAlbumTrack(5, disc = 1))
        assertEquals(5, encodeAlbumTrack(5, disc = 0))
        assertEquals(0, encodeAlbumTrack(0, disc = 2))
    }

    @Test
    fun encode_multiDiscUsesThousands() {
        assertEquals(2004, encodeAlbumTrack(4, disc = 2))
    }

    @Test
    fun displayAndDisc_roundTripMediaStoreEncoding() {
        assertEquals(5, albumTrackDisplayNumber(5))
        assertEquals(5, albumTrackDisplayNumber(1005))
        assertEquals(4, albumTrackDisplayNumber(2004))
        assertEquals(0, albumDiscNumber(5))
        assertEquals(1, albumDiscNumber(1005))
        assertEquals(2, albumDiscNumber(2004))
        assertEquals(0, albumTrackDisplayNumber(0))
    }

    @Test
    fun sortKey_collatesPlainAndEncodedDisc1() {
        assertEquals(albumTrackSortKey(5), albumTrackSortKey(1005))
        assertEquals(Int.MAX_VALUE, albumTrackSortKey(0))
        assertEquals(true, albumTrackSortKey(5) < albumTrackSortKey(2001))
    }

    @Test
    fun parseCdTrackNumber_slashAndPlain() {
        assertEquals(3, parseCdTrackNumber("3/12", null))
        assertEquals(2003, parseCdTrackNumber("3/12", "2"))
        assertEquals(7, parseCdTrackNumber("7", "1"))
        assertEquals(0, parseCdTrackNumber(null, "2"))
    }
}
