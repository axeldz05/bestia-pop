package com.bestiapop.android

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import com.bestiapop.android.service.DownloadNotificationHelper
import com.bestiapop.android.ui.MusicPlayerViewModel
import com.bestiapop.android.ui.screens.MainScreen
import com.bestiapop.android.ui.theme.BestiaPopTheme
import com.bestiapop.android.ui.theme.ThemePresets
import com.bestiapop.android.ui.update.AppUpdateViewModel

class MainActivity : ComponentActivity() {

    private val viewModel: MusicPlayerViewModel by viewModels()
    private val appUpdateViewModel: AppUpdateViewModel by viewModels()

    private val unknownSourcesLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        appUpdateViewModel.onReturnedFromUnknownSources()
    }

    private val folderPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { treeUri ->
        treeUri?.let { uri ->
            try {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: SecurityException) {
                // Some providers don't support persistable grants; import can still use the URI now.
            }
            viewModel.importFolder(uri)
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val audioPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        val audioGranted = results[audioPermission] == true
        // First-install import only (updates skip; Room migrations handle schema).
        if (audioGranted || hasAudioPermission()) {
            viewModel.ensureInitialLibraryImport(showRecoveryToast = true)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        requestRequiredPermissions()
        handleOpenTabIntent(intent)

        setContent {
            val currentTheme by viewModel.currentThemeState.collectAsState(initial = ThemePresets.MidnightDark)

            BestiaPopTheme(customTheme = currentTheme) {
                MainScreen(
                    viewModel = viewModel,
                    appUpdateViewModel = appUpdateViewModel,
                    onSelectFolderClick = {
                        folderPickerLauncher.launch(null)
                    },
                    onRequestUnknownSources = {
                        unknownSourcesLauncher.launch(appUpdateViewModel.unknownSourcesIntent())
                    }
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        viewModel.onAppForeground()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleOpenTabIntent(intent)
    }

    private fun handleOpenTabIntent(intent: Intent?) {
        val tab = intent?.getStringExtra(DownloadNotificationHelper.EXTRA_OPEN_TAB)
        if (tab == DownloadNotificationHelper.TAB_DOWNLOADS) {
            viewModel.requestOpenDownloads()
            intent?.removeExtra(DownloadNotificationHelper.EXTRA_OPEN_TAB)
        }
    }

    private fun hasAudioPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestRequiredPermissions() {
        if (hasAudioPermission()) {
            viewModel.ensureInitialLibraryImport(showRecoveryToast = false)
        }

        val permissionsToRequest = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_AUDIO) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                permissionsToRequest.add(Manifest.permission.READ_MEDIA_AUDIO)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                permissionsToRequest.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }

        if (permissionsToRequest.isNotEmpty()) {
            permissionLauncher.launch(permissionsToRequest.toTypedArray())
        }
    }
}
