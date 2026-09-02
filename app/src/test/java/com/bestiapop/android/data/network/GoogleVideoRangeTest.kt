package com.bestiapop.android.data.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GoogleVideoRangeTest {

    @Test
    fun downloadChunk_neverCoversTheWholeFile() {
        assertEquals(
            "bytes=0-262143",
            GoogleVideoRange.nextChunkRange(
                host = "rr1---sn-uxaxjxougv-x1xl7.googlevideo.com",
                contentLengthParam = "4086041",
                startByte = 0L
            )
        )
        assertEquals(
            "bytes=0-6",
            GoogleVideoRange.nextChunkRange(
                host = "rr1.googlevideo.com",
                contentLengthParam = "8",
                startByte = 0L
            )
        )
        assertEquals(
            "bytes=7-7",
            GoogleVideoRange.nextChunkRange(
                host = "rr1.googlevideo.com",
                contentLengthParam = "8",
                startByte = 7L
            )
        )
    }

    @Test
    fun firstRequest_usesClosedRangeThroughClen() {
        assertEquals(
            "bytes=0-4086040",
            GoogleVideoRange.httpRangeHeader(
                host = "rr1---sn-uxaxjxougv-x1xl7.googlevideo.com",
                contentLengthParam = "4086041",
                startByte = 0L
            )
        )
    }

    @Test
    fun resume_usesClosedRangeFromOffset() {
        assertEquals(
            "bytes=125-999",
            GoogleVideoRange.httpRangeHeader(
                host = "rr1.googlevideo.com",
                contentLengthParam = "1000",
                startByte = 125L
            )
        )
        assertEquals(
            875L,
            GoogleVideoRange.remainingLength(
                host = "rr1.googlevideo.com",
                contentLengthParam = "1000",
                position = 125L
            )
        )
    }

    @Test
    fun nonGoogleVideoOrInvalidClen_hasNoRange() {
        assertNull(
            GoogleVideoRange.httpRangeHeader(
                host = "127.0.0.1",
                contentLengthParam = "8",
                startByte = 0L
            )
        )
        assertNull(
            GoogleVideoRange.httpRangeHeader(
                host = "rr1.googlevideo.com",
                contentLengthParam = "invalid",
                startByte = 0L
            )
        )
        assertNull(
            GoogleVideoRange.httpRangeHeader(
                host = "rr1.googlevideo.com",
                contentLengthParam = "1000",
                startByte = 1000L
            )
        )
    }
}
