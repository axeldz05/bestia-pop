package com.bestiapop.android.data.playback

import com.bestiapop.android.data.model.PlayableItem
import com.bestiapop.android.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class PlaybackFallbackPlannerTest {

    @Test
    fun circularPlan_wrapsFromSelectedIndexInQueueOrder() {
        val items = listOf(
            remote("A"),
            local("B"),
            remote("C"),
            local("D")
        )

        val plan = PlaybackFallbackPlanner.circularPlan(items, startIndex = 2)

        assertEquals(listOf(2, 3, 0, 1), plan.map { it.queueIndex })
        assertEquals(listOf("C", "D", "A", "B"), plan.map { it.item.title })
    }

    @Test
    fun failedRemote_fallsThroughToNextLocalWithoutTryingLaterRemote() {
        val firstRemote = remote("A")
        val nextLocal = local("B")
        val laterRemote = remote("C")
        val attemptedRemotes = mutableListOf<String>()
        var selected: PlayableItem? = null

        for (step in PlaybackFallbackPlanner.circularPlan(
            listOf(firstRemote, nextLocal, laterRemote),
            startIndex = 0
        )) {
            when (step) {
                is PlaybackFallbackStep.ResolveRemote -> {
                    attemptedRemotes += step.item.title
                    // Fake resolve failure: continue through the circular plan.
                }
                is PlaybackFallbackStep.ReadyLocal -> {
                    selected = step.item
                    break
                }
            }
        }

        assertEquals(listOf("A"), attemptedRemotes)
        assertSame(nextLocal, selected)
    }

    @Test
    fun failedRemoteAtTail_wrapsToLocalAtHead() {
        val headLocal = local("Head")
        val tailRemote = remote("Tail")
        var selected: PlayableItem? = null

        for (step in PlaybackFallbackPlanner.circularPlan(
            listOf(headLocal, remote("Middle"), tailRemote),
            startIndex = 2
        )) {
            when (step) {
                is PlaybackFallbackStep.ResolveRemote -> Unit
                is PlaybackFallbackStep.ReadyLocal -> {
                    selected = step.item
                    break
                }
            }
        }

        assertSame(headLocal, selected)
    }

    private fun remote(title: String): PlayableItem.Remote =
        PlayableItem.remoteFrom(artist = "Artist", title = title)

    private fun local(title: String): PlayableItem.Local =
        PlayableItem.Local(
            Song(
                uriString = "file:///$title.mp3",
                title = title,
                artist = "Artist"
            )
        )
}
