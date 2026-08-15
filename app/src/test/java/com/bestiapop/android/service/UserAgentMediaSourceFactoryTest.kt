package com.bestiapop.android.service

import android.app.Application
import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.test.core.app.ApplicationProvider
import com.bestiapop.android.data.model.PlayableItem
import com.bestiapop.android.data.model.TrackIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
@UnstableApi
class UserAgentMediaSourceFactoryTest {

    @Test
    fun unresolvedRemotePlaceholder_createMediaSourceDoesNotThrow() {
        val remote = PlayableItem.remoteFrom(
            identity = TrackIdentity(title = "Preview", artist = "Artist"),
            youtubeQueryOrId = "Artist Preview"
        )
        val mediaItem = PlaybackMediaItemCodec.encode(remote) { error("local unused") }
        assertEquals(Uri.EMPTY, mediaItem.localConfiguration?.uri)
        val source = UserAgentMediaSourceFactory(ApplicationProvider.getApplicationContext())
            .createMediaSource(mediaItem)
        assertNotNull(source)
    }
}
