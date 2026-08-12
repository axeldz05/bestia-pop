package com.bestiapop.android.ui

import android.Manifest
import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.Intents.intended
import androidx.test.espresso.intent.Intents.intending
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.bestiapop.android.MainActivity
import com.bestiapop.android.data.db.AppDatabase
import com.bestiapop.android.data.preferences.LibraryPreferencesRepository
import com.bestiapop.android.data.preferences.UiNavSnapshot
import com.bestiapop.android.testutil.ComposeE2EProbe
import com.bestiapop.android.testutil.DeviceAwakeRule
import com.bestiapop.android.testutil.TestAudioDocumentsProvider
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@LargeTest
@SdkSuppress(minSdkVersion = Build.VERSION_CODES.TIRAMISU)
class SafFolderPickerFunctionalTest {
    private val namespace = UUID.randomUUID()
    private val activityRule = createAndroidComposeRule<MainActivity>()
    private val stateRule = SafPickerStateRule(namespace)

    @get:Rule
    val rules: RuleChain = RuleChain
        .outerRule(DeviceAwakeRule())
        .around(stateRule)
        .around(activityRule)

    private val probe by lazy {
        ComposeE2EProbe(
            rule = activityRule,
            timeoutMs = 10_000L,
            diagnostics = {
                val songs = runBlocking {
                    stateRule.database.musicDao().getAllSongs()
                        .joinToString { "${it.id}:${it.title}:${it.uriString}" }
                }
                "songs=[$songs]"
            }
        )
    }

    @Before
    fun initializeIntentInterception() {
        Intents.init()
    }

    @After
    fun releaseIntentInterception() {
        Intents.release()
    }

    @Test
    fun selectFolder_usesOpenDocumentTree_andImportsReturnedTree() {
        val treeUri = TestAudioDocumentsProvider.treeUri(namespace)
        val resultIntent = Intent()
            .setData(treeUri)
            .addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_PREFIX_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
            )
        intending(hasAction(Intent.ACTION_OPEN_DOCUMENT_TREE)).respondWith(
            Instrumentation.ActivityResult(Activity.RESULT_OK, resultIntent)
        )

        activityRule.onNodeWithText("Agregar", useUnmergedTree = true).performClick()
        activityRule.onNodeWithText("Seleccionar Carpeta", useUnmergedTree = true).performClick()

        intended(hasAction(Intent.ACTION_OPEN_DOCUMENT_TREE))
        probe.await("SAF picker result imported into production Room") {
            runBlocking { stateRule.database.musicDao().getAllSongs().size == 1 }
        }
        val persisted = runBlocking { stateRule.database.musicDao().getAllSongs().single() }
        assertEquals(TestAudioDocumentsProvider.audioUri(namespace).toString(), persisted.uriString)
    }
}

private class SafPickerStateRule(
    private val namespace: UUID
) : ExternalResource() {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val providerContext = instrumentation.context
    private val preferences = LibraryPreferencesRepository(context)

    val database: AppDatabase
        get() = AppDatabase.getDatabase(context)

    private var originalInitialScanCompleted = true
    private var originalNav = UiNavSnapshot()

    override fun before() {
        grantStartupPermissions()
        TestAudioDocumentsProvider.activate(providerContext, namespace)
        providerContext.grantUriPermission(
            context.packageName,
            TestAudioDocumentsProvider.treeUri(namespace),
            TREE_GRANT_FLAGS
        )
        runBlocking {
            withContext(Dispatchers.IO) {
                originalInitialScanCompleted = preferences.isInitialScanCompleted()
                originalNav = preferences.navSnapshotFlow.first()
                database.clearAllTables()
                preferences.setInitialScanCompleted(true)
                preferences.setNavSnapshot(UiNavSnapshot())
            }
        }
    }

    override fun after() {
        runBlocking {
            withContext(Dispatchers.IO) {
                database.clearAllTables()
                preferences.setInitialScanCompleted(originalInitialScanCompleted)
                preferences.setNavSnapshot(originalNav)
            }
        }
        context.contentResolver.persistedUriPermissions
            .filter { it.uri.authority == TestAudioDocumentsProvider.AUTHORITY }
            .forEach { permission ->
                runCatching {
                    context.contentResolver.releasePersistableUriPermission(
                        permission.uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                }
            }
        runCatching {
            providerContext.revokeUriPermission(
                context.packageName,
                TestAudioDocumentsProvider.treeUri(namespace),
                TREE_GRANT_FLAGS
            )
        }
        TestAudioDocumentsProvider.delete(providerContext, namespace)
    }

    private fun grantStartupPermissions() {
        listOf(
            Manifest.permission.READ_MEDIA_AUDIO,
            Manifest.permission.POST_NOTIFICATIONS
        ).forEach { permission ->
            if (context.checkSelfPermission(permission) == PackageManager.PERMISSION_DENIED) {
                instrumentation.uiAutomation.grantRuntimePermission(context.packageName, permission)
            }
        }
    }

    private companion object {
        const val TREE_GRANT_FLAGS =
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
    }
}
