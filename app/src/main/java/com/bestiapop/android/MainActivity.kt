package com.bestiapop.android

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.bestiapop.android.service.DownloadNotificationHelper
import com.bestiapop.android.ui.MusicPlayerViewModel
import com.bestiapop.android.ui.screens.MainScreen
import com.bestiapop.android.ui.theme.BestiaPopTheme
import com.bestiapop.android.ui.theme.ThemePresets

class MainActivity : ComponentActivity() {

    private val viewModel: MusicPlayerViewModel by viewModels()

    private val folderPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { treeUri ->
        treeUri?.let { uri ->
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            viewModel.importFolder(uri)
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* Permissions result handled */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestRequiredPermissions()
        handleOpenTabIntent(intent)

        setContent {
            val currentTheme by viewModel.currentThemeState.collectAsState(initial = ThemePresets.MidnightDark)

            BestiaPopTheme(customTheme = currentTheme) {
                MainScreen(
                    viewModel = viewModel,
                    onSelectFolderClick = {
                        folderPickerLauncher.launch(null)
                    }
                )
            }
        }
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

    private fun requestRequiredPermissions() {
        val permissionsToRequest = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionsToRequest.add(Manifest.permission.READ_MEDIA_AUDIO)
            permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            permissionsToRequest.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

        if (permissionsToRequest.isNotEmpty()) {
            permissionLauncher.launch(permissionsToRequest.toTypedArray())
        }
    }
}
