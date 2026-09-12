package com.bestiapop.android.ui.state

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.content.ContextCompat
import com.bestiapop.android.data.db.AppDatabase
import com.bestiapop.android.data.model.LibraryJobKind
import com.bestiapop.android.data.model.LibraryJobProgress
import com.bestiapop.android.data.model.Song
import com.bestiapop.android.data.preferences.LibraryPreferencesRepository
import com.bestiapop.android.data.util.CrashReporter
import com.bestiapop.android.domain.repository.IMusicRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Coordinates library scanning from disk, folder SAF imports, MediaStore sync,
 * initial import serialization, tags syncing to files, and scan progress tracking.
 */
class LibraryScanCoordinator(
    private val context: Context,
    private val scope: CoroutineScope,
    private val repository: IMusicRepository,
    private val libraryPreferences: LibraryPreferencesRepository,
    private val identifyProgress: StateFlow<LibraryJobProgress?>,
    private val identifyImportedGaps: (List<Song>) -> Unit,
    private val toast: (String) -> Unit
) {
    private val _localLibraryJobProgress = MutableStateFlow<LibraryJobProgress?>(null)
    val localLibraryJobProgress: StateFlow<LibraryJobProgress?> = _localLibraryJobProgress.asStateFlow()

    val libraryJobProgress: StateFlow<LibraryJobProgress?> =
        combine(_localLibraryJobProgress, identifyProgress) { local, identify ->
            identify ?: local
        }.stateInUi(scope, null)

    /** Serializes the first-launch disk import: two callers race the completed-flag check. */
    private val initialImportMutex = Mutex()

    fun reportLibraryProgress(
        kind: LibraryJobKind,
        done: Int,
        total: Int,
        label: String
    ) {
        _localLibraryJobProgress.value = LibraryJobProgress(kind, done, total, label)
    }

    fun clearLibraryProgress() {
        _localLibraryJobProgress.value = null
    }

    private fun importScanProgress(): (Int, Int, String) -> Unit = { done, total, fileName ->
        reportLibraryProgress(LibraryJobKind.IMPORT, done, total, fileName)
    }

    // SAF Import
    fun importFolder(treeUri: Uri) {
        scope.launch {
            // Show the banner before the SAF tree walk; listing a big folder can take
            // minutes with no files indexed yet, which looked like a dead tap.
            reportLibraryProgress(LibraryJobKind.IMPORT, 0, 0, "Buscando archivos…")
            // finally: a stuck banner blocks every later library job (see the guard in
            // syncLibraryTagsToFiles), so it must clear even if the scan throws.
            val inserted = try {
                withContext(Dispatchers.IO) {
                    repository.scanFolderUri(treeUri, importScanProgress())
                }
            } catch (e: Exception) {
                CrashReporter.recordNonFatal(e, mapOf("scan_phase" to "folder_import"))
                toast("No se pudo leer esa carpeta")
                return@launch
            } finally {
                clearLibraryProgress()
            }
            val count = inserted.size
            toast(
                when {
                    count <= 0 -> "No se encontraron canciones nuevas en esa carpeta"
                    count == 1 -> "1 canción agregada a la biblioteca"
                    else -> "$count canciones agregadas a la biblioteca"
                }
            )
            identifyImportedGaps(inserted)
        }
    }

    suspend fun warnIfDatabaseWasDowngraded() {
        val seen = libraryPreferences.highestDbVersionSeen()
        if (seen > AppDatabase.VERSION) {
            toast(
                "Instalaste una versión más vieja de BestiaPop: se reinició la base " +
                        "(playlists y datos de álbumes). Tus archivos de música siguen en Music/BestiaPop."
            )
        }
        if (seen < AppDatabase.VERSION) {
            libraryPreferences.setHighestDbVersionSeen(AppDatabase.VERSION)
        }
    }

    /**
     * First-install (or post-uninstall) disk import: BestiaPop folder + MediaStore.
     * Skipped on later cold starts / updates once [LibraryPreferencesRepository] marks completed.
     * Room migrations still run independently via [AppDatabase].
     */
    fun ensureInitialLibraryImport(showRecoveryToast: Boolean = false) {
        scope.launch {
            initialImportMutex.withLock {
                if (libraryPreferences.isInitialScanCompleted()) return@launch
                if (!hasAudioPermission()) return@launch
                try {
                    runLibraryDiskImport(showRecoveryToast = showRecoveryToast)
                } catch (e: Exception) {
                    e.printStackTrace()
                    CrashReporter.recordNonFatal(
                        e,
                        mapOf("phase" to "ensureInitialLibraryImport")
                    )
                } finally {
                    libraryPreferences.setInitialScanCompleted(true)
                }
            }
        }
    }

    private suspend fun runLibraryDiskImport(showRecoveryToast: Boolean) {
        val (recovered, inserted) = try {
            withContext(Dispatchers.IO) {
                val fromManaged = repository.resyncAppManagedMusic(importScanProgress())
                val fromMedia = repository.scanMediaStore(importScanProgress())
                fromManaged to (fromManaged + fromMedia)
            }
        } finally {
            clearLibraryProgress()
        }
        if (showRecoveryToast && recovered.isNotEmpty()) {
            toast(
                if (recovered.size == 1) "Se recuperó 1 canción de Music/BestiaPop"
                else "Se recuperaron ${recovered.size} canciones de Music/BestiaPop"
            )
        }
        identifyImportedGaps(inserted)
    }

    fun hasAudioPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) ==
                    PackageManager.PERMISSION_GRANTED
        }
    }

    fun syncLibraryTagsToFiles() {
        if (_localLibraryJobProgress.value != null || identifyProgress.value != null) {
            toast("Ya hay una tarea de biblioteca en curso")
            return
        }
        scope.launch {
            val summary = withContext(Dispatchers.IO) {
                repository.syncTagsToFiles { done, total, fileName ->
                    reportLibraryProgress(LibraryJobKind.TAG_WRITE, done, total, fileName)
                }
            }
            clearLibraryProgress()
            toast(
                buildString {
                    append(
                        if (summary.updated == 1) "1 archivo actualizado"
                        else "${summary.updated} archivos actualizados"
                    )
                    if (summary.skipped > 0) {
                        append(
                            if (summary.skipped == 1) ", 1 omitido"
                            else ", ${summary.skipped} omitidos"
                        )
                    }
                    if (summary.errors > 0) {
                        append(
                            if (summary.errors == 1) ", 1 error"
                            else ", ${summary.errors} errores"
                        )
                    }
                }
            )
        }
    }
}
