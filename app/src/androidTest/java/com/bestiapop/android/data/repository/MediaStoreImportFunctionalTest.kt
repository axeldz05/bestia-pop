package com.bestiapop.android.data.repository

import android.content.Context
import android.media.MediaMetadataRetriever
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.bestiapop.android.data.model.Song
import com.bestiapop.android.data.util.MusicFileStore
import com.bestiapop.android.data.util.SongPathNormalizer
import com.bestiapop.android.data.util.StorageUtils
import com.bestiapop.android.testutil.PcmWavFixture
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Functional coverage for the real local-import repository against one private MediaStore fixture.
 *
 * The repository sees only that exact URI through the test harness. Room is in-memory, and cleanup
 * never lists or removes any other media on the device.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
@SdkSuppress(minSdkVersion = 29)
class MediaStoreImportFunctionalTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun firstMediaStoreScan_importsPlayableMetadata_andSecondScanDoesNotDuplicate() =
        runBlocking {
            val fixture = MediaStoreAudioFixture.create(
                context = context,
                relativePath = "Music/SofoInstrumentedImports"
            )
            try {
                val harness = MediaStoreRepositoryHarness(context, fixture)
                try {
                    harness.repository.scanMediaStore()

                    val firstImport = harness.repository.allSongsFlow.first()
                    val imported = firstImport.single(fixture::owns)
                    assertEquals(fixture.expectedImportedUri, imported.uriString)
                    assertEquals(fixture.row.absolutePath, imported.folderPath)
                    assertEquals(fixture.row.title, imported.title)
                    assertEquals(fixture.row.artist, imported.artist)
                    assertEquals(fixture.row.album, imported.album)
                    assertEquals(fixture.row.durationMs, imported.durationMs)
                    assertEquals(fixture.row.year, imported.year)
                    assertEquals(fixture.row.trackNumber, imported.trackNumber)
                    assertPlayable(context, imported)

                    harness.repository.scanMediaStore()

                    val afterSecondScan = harness.repository.allSongsFlow.first()
                    assertEquals(1, afterSecondScan.count(fixture::owns))
                } finally {
                    try {
                        harness.removeFixtureRows(fixture)
                    } finally {
                        harness.close()
                    }
                }
            } finally {
                fixture.close()
            }
        }

    @Test
    fun appManagedMediaStoreRow_isSkippedByScan_thenResyncImportsItOnce() = runBlocking {
        val fixture = MediaStoreAudioFixture.create(
            context = context,
            relativePath = StorageUtils.RELATIVE_MUSIC_DIR
        )
        try {
            assertEquals(fixture.displayName, fixture.exactFile.name)
            assertTrue(
                "Fixture must resolve inside the exact app-managed directory before resync",
                SongPathNormalizer.isUnderBestiaPop(fixture.exactFile.absolutePath)
            )

            val harness = MediaStoreRepositoryHarness(
                context = context,
                fixture = fixture,
                managedFixtureFile = fixture.exactFile
            )
            try {
                harness.repository.scanMediaStore()
                assertTrue(harness.repository.allSongsFlow.first().none(fixture::owns))

                assertEquals(1, harness.repository.resyncAppManagedMusic())

                val imported = harness.repository.allSongsFlow.first().single(fixture::owns)
                assertEquals(fixture.exactFile.absolutePath, imported.uriString)
                assertEquals(fixture.exactFile.parent.orEmpty(), imported.folderPath)
                assertEquals(fixture.exactFile.nameWithoutExtension, imported.title)
                assertEquals("Unknown Artist", imported.artist)
                assertEquals("Unknown Album", imported.album)
                assertTrue(imported.durationMs >= 30_000L)
                assertPlayable(context, imported)

                assertEquals(0, harness.repository.resyncAppManagedMusic())
                assertEquals(1, harness.repository.allSongsFlow.first().count(fixture::owns))
            } finally {
                try {
                    harness.removeFixtureRows(fixture)
                } finally {
                    harness.close()
                }
            }
        } finally {
            fixture.close()
        }
    }

    private fun assertPlayable(context: Context, song: Song) {
        val store = MusicFileStore(context)
        val ref = store.canonicalize(song.uriString, song.folderPath)
        val byteCount = checkNotNull(store.openRead(ref)).use { it.statSize }
        assertTrue(
            "Imported URI must open the complete synthetic WAV",
            byteCount == -1L || byteCount > PcmWavFixture.HEADER_SIZE_BYTES
        )

        val retriever = MediaMetadataRetriever()
        try {
            store.applyDataSource(retriever, ref)
            val duration = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
                ?: 0L
            assertTrue("Imported URI must be decodable", duration > 0L)
        } finally {
            retriever.release()
        }
    }
}
