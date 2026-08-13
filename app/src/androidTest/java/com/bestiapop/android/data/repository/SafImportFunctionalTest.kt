package com.bestiapop.android.data.repository

import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import com.bestiapop.android.data.util.MusicFileStore
import com.bestiapop.android.testutil.DeviceAwakeRule
import com.bestiapop.android.testutil.RoomTestDatabaseRule
import com.bestiapop.android.testutil.TestAudioDocumentsProvider
import java.io.File
import java.io.FileNotFoundException
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@LargeTest
class SafImportFunctionalTest {
    private val database = RoomTestDatabaseRule()

    @get:Rule
    val rules: RuleChain = RuleChain
        .outerRule(DeviceAwakeRule())
        .around(database)

    private val instrumentation
        get() = InstrumentationRegistry.getInstrumentation()
    private val targetContext: Context
        get() = instrumentation.targetContext
    private val providerContext: Context
        get() = instrumentation.context

    private lateinit var namespace: UUID

    @Before
    fun activateProviderTree() {
        namespace = UUID.randomUUID()
        TestAudioDocumentsProvider.activate(providerContext, namespace)
        providerContext.grantUriPermission(
            targetContext.packageName,
            TestAudioDocumentsProvider.treeUri(namespace),
            TREE_GRANT_FLAGS
        )
    }

    @After
    fun releaseProviderTree() {
        targetContext.contentResolver.persistedUriPermissions
            .filter { it.uri.authority == TestAudioDocumentsProvider.AUTHORITY }
            .forEach { permission ->
                runCatching {
                    targetContext.contentResolver.releasePersistableUriPermission(
                        permission.uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                }
            }
        runCatching {
            providerContext.revokeUriPermission(
                targetContext.packageName,
                TestAudioDocumentsProvider.treeUri(namespace),
                TREE_GRANT_FLAGS
            )
        }
        TestAudioDocumentsProvider.delete(providerContext, namespace)
    }

    @Test
    fun scanFolderUri_importsOnce_andStoredContentUriReallyPlays() = runBlocking {
        val progress = mutableListOf<Triple<Int, Int, String>>()
        val repository = MusicRepository(targetContext, database.database)

        val firstCount = repository.scanFolderUri(TestAudioDocumentsProvider.treeUri(namespace)) {
                done,
                total,
                label ->
            progress += Triple(done, total, label)
        }
        val secondCount = repository.scanFolderUri(TestAudioDocumentsProvider.treeUri(namespace))

        val song = database.musicDao.getAllSongs().single()
        assertEquals(1, firstCount)
        assertEquals(0, secondCount)
        assertEquals(TestAudioDocumentsProvider.audioUri(namespace).toString(), song.uriString)
        assertTrue(song.durationMs >= 31_000L)
        assertEquals(
            listOf(Triple(1, 1, "BestiaPop SAF fixture.wav")),
            progress
        )
        assertEquals(
            "Each import must enumerate the SAF tree once",
            2,
            TestAudioDocumentsProvider.childQueryCount(namespace)
        )
        assertEquals(
            "Tag and artwork extraction must share one audio descriptor",
            1,
            TestAudioDocumentsProvider.audioOpenCount(namespace)
        )

        val player = MediaPlayer()
        try {
            player.setVolume(0f, 0f)
            val store = MusicFileStore(targetContext)
            store.applyDataSource(player, store.canonicalize(song.uriString, song.folderPath))
            player.prepare()
            assertTrue(player.duration >= 31_000)
            player.start()
            assertTrue(player.isPlaying)
        } finally {
            runCatching { player.stop() }
            player.release()
        }
    }

    @Test
    fun userCover_isCopiedToFilesDir_andSurvivesProviderInvalidation() {
        val repository = MusicRepository(targetContext, database.database)
        var copied: File? = null

        try {
            val storedUri = repository.saveAlbumCoverImage(
                TestAudioDocumentsProvider.imageUri(namespace).toString()
            )
            val persistedFile = checkNotNull(storedUri).let { File(java.net.URI(it)) }
            copied = persistedFile
            assertTrue(persistedFile.isFile)
            assertTrue(persistedFile.startsWith(targetContext.filesDir))

            TestAudioDocumentsProvider.invalidate(providerContext, namespace)

            assertTrue(
                runCatching {
                    targetContext.contentResolver
                        .openInputStream(TestAudioDocumentsProvider.imageUri(namespace))
                }.exceptionOrNull() is FileNotFoundException
            )
            val decoded = checkNotNull(
                android.graphics.BitmapFactory.decodeFile(persistedFile.absolutePath)
            )
            assertEquals(2, decoded.width)
            assertEquals(1, decoded.height)
            decoded.recycle()
        } finally {
            copied?.delete()
            copied?.parentFile?.takeIf { it.list().isNullOrEmpty() }?.delete()
        }
    }

    private companion object {
        const val TREE_GRANT_FLAGS =
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
    }
}
