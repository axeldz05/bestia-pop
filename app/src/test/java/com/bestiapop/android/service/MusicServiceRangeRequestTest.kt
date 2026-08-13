package com.bestiapop.android.service

import androidx.media3.common.C
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MusicServiceRangeRequestTest {

    @Test
    fun openEndedGoogleVideoRequest_isBoundedByRemainingContentLength() {
        assertEquals(
            875L,
            googleVideoBoundedLength(
                host = "rr1.googlevideo.com",
                contentLengthParam = "1000",
                position = 125,
                requestedLength = C.LENGTH_UNSET.toLong()
            )
        )
    }

    @Test
    fun knownLengthGoogleVideoRequest_isUnchanged() {
        assertNull(
            googleVideoBoundedLength(
                host = "rr1.googlevideo.com",
                contentLengthParam = "1000",
                position = 125,
                requestedLength = 100
            )
        )
    }

    @Test
    fun nonGoogleVideoRequest_isUnchanged() {
        assertNull(
            googleVideoBoundedLength(
                host = "example.com",
                contentLengthParam = "1000",
                position = 0,
                requestedLength = C.LENGTH_UNSET.toLong()
            )
        )
    }

    @Test
    fun invalidOrExhaustedContentLength_isUnchanged() {
        assertNull(
            googleVideoBoundedLength(
                host = "rr1.googlevideo.com",
                contentLengthParam = "invalid",
                position = 0,
                requestedLength = C.LENGTH_UNSET.toLong()
            )
        )
        assertNull(
            googleVideoBoundedLength(
                host = "rr1.googlevideo.com",
                contentLengthParam = "1000",
                position = 1000,
                requestedLength = C.LENGTH_UNSET.toLong()
            )
        )
    }
}
