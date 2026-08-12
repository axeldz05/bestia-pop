package com.bestiapop.android.ui.components

import com.bestiapop.android.data.model.Song
import com.bestiapop.android.data.model.toPlayable
import com.bestiapop.android.data.model.withFreshQueueEntryIds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class QueueUiIdentityTest {

    @Test
    fun duplicateTracks_keepSlotKeysAndExactFocusAcrossReorder() {
        val repeated = Song(
            id = 1L,
            uriString = "content://song/repeated",
            title = "Repeated"
        ).toPlayable()
        val original = listOf(repeated, repeated).withFreshQueueEntryIds()
        val focused = original[1]

        assertEquals(original[0].mediaId, original[1].mediaId)
        assertNotEquals(queueRowKey(original[0]), queueRowKey(original[1]))
        assertEquals(1, focusedQueueIndex(original, focused.queueEntryId))

        val reordered = original.reversed()

        assertEquals(
            listOf(original[1].queueEntryId, original[0].queueEntryId),
            reordered.map(::queueRowKey)
        )
        assertEquals(0, focusedQueueIndex(reordered, focused.queueEntryId))
    }
}
