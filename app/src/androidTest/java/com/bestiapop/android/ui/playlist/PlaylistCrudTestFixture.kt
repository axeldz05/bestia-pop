package com.bestiapop.android.ui.playlist

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.bestiapop.android.BestiaPopApplication
import com.bestiapop.android.MainActivity
import com.bestiapop.android.data.model.Playlist
import com.bestiapop.android.data.model.Song
import com.bestiapop.android.data.preferences.LibraryDisplaySettings
import com.bestiapop.android.data.preferences.LibraryPreferencesRepository
import com.bestiapop.android.data.preferences.NAV_PLAYLISTS
import com.bestiapop.android.data.preferences.UiNavSnapshot
import com.bestiapop.android.testutil.PcmWavFixture
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Exact persistent-state owner for the visible playlist CRUD journey.
 *
 * The production Application, repository and Room database remain in use. Fixture rows are
 * namespaced and removed individually so unrelated library data is never cleared.
 */
internal class PlaylistCrudTestFixture : AutoCloseable {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val application = context.applicationContext as BestiaPopApplication
    private val repository = application.musicRepository
    private val libraryPreferences = LibraryPreferencesRepository(context)
    private val token = UUID.randomUUID().toString().replace("-", "").take(10)
    private val fixtureDir = File(context.cacheDir, "$FIXTURE_DIR_PREFIX$token")

    val playlistName = "$PLAYLIST_PREFIX$token"
    val renamedPlaylistName = "$PLAYLIST_PREFIX${token} Renombrada"
    val playlistDescription = "Descripción E2E $token"
    val renamedDescription = "Descripción actualizada $token"
    val songTitles = listOf(
        "$SONG_PREFIX${token} A",
        "$SONG_PREFIX${token} B"
    )

    private var scenario: ActivityScenario<MainActivity>? = null
    private var previousInitialScanCompleted: Boolean? = null
    private var previousNavSnapshot: UiNavSnapshot? = null
    private var previousDisplaySettings: LibraryDisplaySettings? = null

    fun prepare() {
        grantStartupPermissions()
        runBlocking {
            withTimeout(STATE_TIMEOUT_MS) {
                previousInitialScanCompleted = libraryPreferences.isInitialScanCompleted()
                previousNavSnapshot = libraryPreferences.navSnapshotFlow.first()
                previousDisplaySettings = libraryPreferences.displaySettingsFlow.first()

                deleteFixtureArtifacts()
                createFixtureSongs()

                libraryPreferences.setInitialScanCompleted(true)
                libraryPreferences.setSortOptionName("DATE_ADDED")
                libraryPreferences.setViewModeName("FLAT")
                libraryPreferences.setNavSnapshot(UiNavSnapshot(navIndex = NAV_PLAYLISTS))
            }
        }
    }

    fun launchMainActivity() {
        scenario = ActivityScenario.launch(MainActivity::class.java).also {
            it.moveToState(Lifecycle.State.RESUMED)
        }
    }

    fun verifyRenamedPlaylist() {
        val playlist = requireFixturePlaylist()
        check(playlist.name == renamedPlaylistName) {
            "Playlist name was not persisted: ${playlist.name}"
        }
        check(playlist.description == renamedDescription) {
            "Playlist description was not persisted: ${playlist.description}"
        }
    }

    fun verifyPlaylistMembership(expectedCount: Int) {
        val playlist = requireFixturePlaylist()
        val details = runBlocking {
            withTimeout(STATE_TIMEOUT_MS) {
                repository.getPlaylistDetailsFlow(playlist.id).first { pair ->
                    pair?.second?.size == expectedCount
                }
            }
        } ?: error("Fixture playlist disappeared while checking membership")
        check(details.second.all { it.title in songTitles }) {
            "Unexpected playlist members: ${details.second.map { it.title }}"
        }
    }

    fun verifyPlaylistDeletedAndSongsKept() {
        val playlists = runBlocking { repository.playlistsFlow.first() }
        check(playlists.none { it.name == playlistName || it.name == renamedPlaylistName }) {
            "Fixture playlist still exists: ${playlists.filter { it.name.contains(token) }}"
        }
        val songs = runBlocking { fixtureSongs() }
        check(songs.map { it.title }.toSet() == songTitles.toSet()) {
            "Deleting the playlist changed its library Songs: ${songs.map { it.title }}"
        }
    }

    fun diagnostic(): String {
        val playlists = runCatching {
            runBlocking {
                repository.playlistsFlow.first()
                    .filter { it.name == playlistName || it.name == renamedPlaylistName }
                    .joinToString { "${it.id}:${it.name}" }
            }
        }.getOrElse { "Room playlist diagnostic failed: ${it.message}" }
        val songs = runCatching {
            runBlocking { fixtureSongs() }.joinToString { "${it.id}:${it.title}" }
        }.getOrElse { "Room Song diagnostic failed: ${it.message}" }
        return "playlists=[$playlists], songs=[$songs], fixtureDir=${fixtureDir.exists()}"
    }

    override fun close() {
        var firstFailure: Throwable? = null
        fun cleanup(block: () -> Unit) {
            runCatching(block).exceptionOrNull()?.let { failure ->
                if (firstFailure == null) firstFailure = failure
                else firstFailure?.addSuppressed(failure)
            }
        }

        cleanup { scenario?.close() }
        cleanup {
            runBlocking {
                withTimeout(STATE_TIMEOUT_MS) {
                    deleteFixtureArtifacts()
                }
            }
        }
        cleanup {
            runBlocking {
                previousInitialScanCompleted?.let {
                    libraryPreferences.setInitialScanCompleted(it)
                }
                previousDisplaySettings?.let { previous ->
                    libraryPreferences.setSortOptionName(previous.sortOptionName)
                    libraryPreferences.setSortDirectionName(
                        previous.sortDirectionName,
                        previous.sortOptionName
                    )
                    libraryPreferences.setViewModeName(previous.viewModeName)
                }
                previousNavSnapshot?.let { libraryPreferences.setNavSnapshot(it) }
            }
        }
        firstFailure?.let { throw it }
    }

    private suspend fun createFixtureSongs() {
        check(fixtureDir.mkdirs() || fixtureDir.isDirectory) {
            "Could not create ${fixtureDir.absolutePath}"
        }
        songTitles.forEachIndexed { index, title ->
            val file = File(fixtureDir, "song-$index.wav")
            PcmWavFixture.write(
                file = file,
                durationMs = WAV_DURATION_MS,
                toneHz = 330.0 + (index * 110.0)
            )
            val song = Song(
                uriString = file.absolutePath,
                title = title,
                artist = "$ARTIST_PREFIX$token",
                album = "$ALBUM_PREFIX$token",
                genre = "Fixture",
                durationMs = WAV_DURATION_MS.toLong(),
                folderPath = fixtureDir.absolutePath,
                dateAdded = System.currentTimeMillis() + index
            )
            val id = repository.saveUploadedSong(song)
            check(id > 0L) { "Could not persist fixture Song $title (id=$id)" }
        }
    }

    private fun requireFixturePlaylist(): Playlist = runBlocking {
        withTimeout(STATE_TIMEOUT_MS) {
            repository.playlistsFlow.first { playlists ->
                playlists.any { it.name == playlistName || it.name == renamedPlaylistName }
            }.first { it.name == playlistName || it.name == renamedPlaylistName }
        }
    }

    private suspend fun fixtureSongs(): List<Song> =
        repository.getAllSongsSync().filter {
            it.artist == "$ARTIST_PREFIX$token" && it.title in songTitles
        }

    private suspend fun deleteFixtureArtifacts() {
        repository.playlistsFlow.first()
            .filter { it.name == playlistName || it.name == renamedPlaylistName }
            .forEach { repository.deletePlaylist(it.id) }

        val songs = repository.getAllSongsSync().filter {
            it.artist == "$ARTIST_PREFIX$token" && it.title in songTitles
        }
        if (songs.isNotEmpty()) repository.deleteSongsFromDevice(songs)

        check(!fixtureDir.exists() || fixtureDir.deleteRecursively()) {
            "Could not delete exact playlist fixture directory ${fixtureDir.absolutePath}"
        }
    }

    private fun grantStartupPermissions() {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            listOf(Manifest.permission.READ_MEDIA_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
        } else {
            listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        permissions.forEach { permission ->
            if (context.checkSelfPermission(permission) == PackageManager.PERMISSION_DENIED) {
                instrumentation.uiAutomation.grantRuntimePermission(
                    context.packageName,
                    permission
                )
            }
        }
    }

    private companion object {
        const val PLAYLIST_PREFIX = "BestiaPop E2E Playlist "
        const val SONG_PREFIX = "BestiaPop E2E Song "
        const val ARTIST_PREFIX = "BestiaPop E2E Artist "
        const val ALBUM_PREFIX = "BestiaPop E2E Album "
        const val FIXTURE_DIR_PREFIX = "playlist-crud-e2e-"
        const val WAV_DURATION_MS = 1_000
        const val STATE_TIMEOUT_MS = 10_000L
    }
}
