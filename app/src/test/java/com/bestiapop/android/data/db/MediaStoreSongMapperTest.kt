package com.bestiapop.android.data.db

import android.app.Application
import android.database.MatrixCursor
import android.provider.MediaStore
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class MediaStoreSongMapperTest {

    private val projection = arrayOf(
        MediaStore.Audio.Media._ID,
        MediaStore.Audio.Media.TITLE,
        MediaStore.Audio.Media.ARTIST,
        MediaStore.Audio.Media.ALBUM,
        MediaStore.Audio.Media.DURATION,
        MediaStore.Audio.Media.YEAR,
        MediaStore.Audio.Media.TRACK,
        MediaStore.Audio.Media.DATA,
        MediaStore.Audio.Media.ALBUM_ID,
        MediaStore.Audio.Media.DATE_ADDED,
        MediaStore.Audio.Media.DATE_MODIFIED
    )

    @Test
    fun toSong_usesDateAddedWhenPresent() {
        val cursor = MatrixCursor(projection).apply {
            addRow(
                arrayOf<Any?>(
                    1L,
                    "Song Title",
                    "Artist Name",
                    "Album Name",
                    180000L,
                    2021,
                    1,
                    "/storage/emulated/0/Music/song.mp3",
                    10L,
                    1609459200L, // 2021-01-01 00:00:00 UTC in seconds
                    1609545600L
                )
            )
        }

        cursor.moveToFirst()
        val song = cursor.toSong()

        assertEquals(1609459200000L, song.dateAdded)
        assertEquals("Song Title", song.title)
        assertEquals("Artist Name", song.artist)
    }

    @Test
    fun toSong_fallsBackToDateModified_whenDateAddedIsZero() {
        val cursor = MatrixCursor(projection).apply {
            addRow(
                arrayOf<Any?>(
                    2L,
                    "Song Two",
                    "Artist Two",
                    "Album Two",
                    200000L,
                    2022,
                    2,
                    "/storage/emulated/0/Music/song2.mp3",
                    -1L,
                    0L,
                    1640995200L // 2022-01-01 00:00:00 UTC in seconds
                )
            )
        }

        cursor.moveToFirst()
        val song = cursor.toSong()

        assertEquals(1640995200000L, song.dateAdded)
    }
}
