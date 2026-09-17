package com.bestiapop.android.service

import android.app.Application
import androidx.media3.common.util.UnstableApi
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class RefreshingMediaNotificationProviderTest {

    @Test
    fun lastMediaNotification_startsNull() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val provider = RefreshingMediaNotificationProvider(
            context = context,
            notificationId = 100,
            channelId = "test_channel",
            channelNameResourceId = android.R.string.ok,
            requestNotificationRefresh = {}
        )

        assertNull(provider.lastMediaNotification)
    }
}
