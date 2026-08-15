package com.bestiapop.android

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import com.bestiapop.android.data.util.PlaybackDiagnostics
import com.bestiapop.android.service.DownloadNotificationHelper
import com.bestiapop.android.service.IdentifyNotificationHelper
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

    private val batteryOptimizationLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        PlaybackDiagnostics.log(PlaybackDiagnostics.TAG_SYSTEM, "Returned from battery optimization request")
        viewModel.onAppForeground()
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
        PlaybackDiagnostics.log(PlaybackDiagnostics.TAG_LIFECYCLE, "MainActivity.onCreate(savedInstanceState=${savedInstanceState != null})")
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
        PlaybackDiagnostics.log(PlaybackDiagnostics.TAG_LIFECYCLE, "MainActivity.onStart (app moved to foreground)")
        runCatching {
            startService(Intent(this, com.bestiapop.android.service.MusicService::class.java))
        }
        viewModel.onAppForeground()
    }

    override fun onResume() {
        super.onResume()
        PlaybackDiagnostics.log(PlaybackDiagnostics.TAG_LIFECYCLE, "MainActivity.onResume (UI active/interactive)")
    }

    override fun onPause() {
        PlaybackDiagnostics.log(PlaybackDiagnostics.TAG_LIFECYCLE, "MainActivity.onPause (UI losing focus / switching apps / locking)")
        super.onPause()
    }

    override fun onStop() {
        PlaybackDiagnostics.log(PlaybackDiagnostics.TAG_LIFECYCLE, "MainActivity.onStop (UI no longer visible / in background)")
        viewModel.onUiDetached()
        super.onStop()
    }

    override fun onDestroy() {
        PlaybackDiagnostics.log(PlaybackDiagnostics.TAG_LIFECYCLE, "MainActivity.onDestroy (Activity destroyed, isFinishing=$isFinishing)")
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleOpenTabIntent(intent)
    }

    private fun handleOpenTabIntent(intent: Intent?) {
        val tab = intent?.getStringExtra(DownloadNotificationHelper.EXTRA_OPEN_TAB)
            ?: intent?.getStringExtra(IdentifyNotificationHelper.EXTRA_OPEN_TAB)
        when (tab) {
            DownloadNotificationHelper.TAB_DOWNLOADS -> {
                viewModel.requestOpenDownloads()
                intent?.removeExtra(DownloadNotificationHelper.EXTRA_OPEN_TAB)
            }
            IdentifyNotificationHelper.TAB_IDENTIFY_REVIEW -> {
                viewModel.requestOpenIdentifyReview()
                intent?.removeExtra(IdentifyNotificationHelper.EXTRA_OPEN_TAB)
            }
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
