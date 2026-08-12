package com.bestiapop.android.testutil

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.bestiapop.android.data.db.AppDatabase
import org.junit.rules.ExternalResource

/**
 * Creates a fresh in-memory [AppDatabase] per test and always closes it afterwards.
 *
 * JVM callers use [org.robolectric.RobolectricTestRunner]; device callers use AndroidJUnit4.
 * Feature-specific setup stays in the test class instead of growing this into a repository fixture.
 */
class RoomTestDatabaseRule : ExternalResource() {
    lateinit var database: AppDatabase
        private set

    val musicDao
        get() = database.musicDao()

    val pendingListenDao
        get() = database.pendingListenDao()

    override fun before() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    override fun after() {
        if (::database.isInitialized) {
            database.close()
        }
    }
}
