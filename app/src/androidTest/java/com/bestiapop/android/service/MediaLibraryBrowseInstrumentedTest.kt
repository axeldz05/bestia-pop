package com.bestiapop.android.service

import android.content.ComponentName
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaBrowser
import androidx.media3.session.SessionToken
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bestiapop.android.service.library.MediaLibraryIds
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MediaLibraryBrowseInstrumentedTest {

    private fun <T> onMain(block: () -> T): T {
        val task = FutureTask(block)
        InstrumentationRegistry.getInstrumentation().runOnMainSync(task)
        return task.get()
    }

    @Test
    fun ownAppBrowser_readsRootAndFourStableCategories() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val token = SessionToken(context, ComponentName(context, MusicService::class.java))
        val browser = onMain {
            MediaBrowser.Builder(context, token).buildAsync()
        }.get(CONNECTION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        try {
            val root = onMain {
                browser.getLibraryRoot(null)
            }.get(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            assertEquals(LibraryResult.RESULT_SUCCESS, root.resultCode)
            assertEquals(MediaLibraryIds.ROOT, root.value?.mediaId)
            assertTrue(root.value?.mediaMetadata?.isBrowsable == true)

            val categories = onMain {
                browser.getChildren(
                    MediaLibraryIds.ROOT,
                    0,
                    10,
                    null
                )
            }.get(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            assertEquals(LibraryResult.RESULT_SUCCESS, categories.resultCode)
            assertEquals(
                listOf(
                    MediaLibraryIds.SONGS,
                    MediaLibraryIds.ALBUMS,
                    MediaLibraryIds.ARTISTS,
                    MediaLibraryIds.PLAYLISTS
                ),
                categories.value?.map { it.mediaId }
            )
            assertFalse(categories.value.orEmpty().any { it.mediaMetadata.isPlayable == true })
        } finally {
            onMain {
                browser.release()
            }
        }
    }

    private companion object {
        const val CONNECTION_TIMEOUT_SECONDS = 10L
        const val REQUEST_TIMEOUT_SECONDS = 10L
    }
}
