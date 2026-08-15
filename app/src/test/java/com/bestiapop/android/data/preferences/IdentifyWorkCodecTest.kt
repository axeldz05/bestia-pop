package com.bestiapop.android.data.preferences

import com.bestiapop.android.data.model.IdentifyApplyFields
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IdentifyWorkCodecTest {

    @Test
    fun roundTrip_keepsRemainingIdsAndFields() {
        val original = IdentifyWorkSnapshot(
            remainingSongIds = listOf(3L, 8L, 21L),
            force = true,
            showReview = false,
            applyFields = IdentifyApplyFields(
                artwork = true,
                title = false,
                artist = true,
                album = false,
                year = true,
                trackNumber = false
            ),
            processedCount = 2,
            totalCount = 5,
            updated = 1,
            skipped = 1,
            medium = 2,
            low = 0,
            none = 1,
            lbHits = 3,
            alreadyQueued = 4,
            reviewCount = 3,
            interrupted = true
        )
        val restored = IdentifyWorkCodec.decode(IdentifyWorkCodec.encode(original))
        assertEquals(original, restored)
    }

    @Test
    fun decode_emptyOrInvalid_returnsNull() {
        assertNull(IdentifyWorkCodec.decode(""))
        assertNull(IdentifyWorkCodec.decode("not-json"))
    }

    @Test
    fun decode_legacyWithoutFields_defaultsToAll() {
        val json = """{"remainingSongIds":[9],"force":true,"showReview":true}"""
        val decoded = IdentifyWorkCodec.decode(json)
        checkNotNull(decoded)
        assertEquals(listOf(9L), decoded.remainingSongIds)
        assertTrue(decoded.force)
        assertEquals(IdentifyApplyFields.ALL, decoded.applyFields)
    }
}
