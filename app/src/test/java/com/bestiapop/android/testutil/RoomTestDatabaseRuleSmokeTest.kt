package com.bestiapop.android.testutil

import android.app.Application
import com.bestiapop.android.data.model.Song
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@Category(MediumTest::class)
class RoomTestDatabaseRuleSmokeTest {
    @get:Rule
    val database = RoomTestDatabaseRule()

    @Test
    fun freshDatabase_canWriteAndReadSong() = runTest {
        database.musicDao.insertSong(
            Song(
                uriString = "file:///wave-zero-smoke.mp3",
                title = "Wave zero smoke"
            )
        )

        assertEquals(
            "Wave zero smoke",
            database.musicDao.getAllSongs().single().title
        )
    }
}
