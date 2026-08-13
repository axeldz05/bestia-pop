package com.bestiapop.android.ui.download

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.espresso.Espresso.closeSoftKeyboard
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import com.bestiapop.android.data.model.DownloadMessages
import com.bestiapop.android.testutil.ComposeE2EProbe
import com.bestiapop.android.testutil.DeviceAwakeRule
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@LargeTest
class CatalogDownloadFunctionalTest {
    private val composeRule = createEmptyComposeRule()
    private val fixture = CatalogDownloadTestFixture()
    private val ui = ComposeE2EProbe(composeRule, UI_TIMEOUT_MS, fixture::diagnostic)

    @get:Rule
    val rules: RuleChain = RuleChain
        .outerRule(DeviceAwakeRule())
        .around(composeRule)

    @Before
    fun setUp() {
        fixture.prepare()
        fixture.launchMainActivity()
    }

    @After
    fun tearDown() {
        fixture.close()
    }

    @Test
    fun catalogResult_downloadsThroughDownloadsAndAppearsInLibrary() {
        fixture.configureGatedSuccess()
        openCatalogSearch(
            query = CatalogDownloadTestContract.SEARCH_QUERY,
            expectedTitles = listOf(CatalogDownloadTestContract.TITLE)
        )
        composeRule.onAllNodesWithContentDescription("Agregar").onFirst().performClick()
        openDownloadsFromCatalog()

        ui.await("fixture row downloading in Descargas") {
            ui.exists(hasText(CatalogDownloadTestContract.TITLE)) &&
                fixture.isDownloadingAt(75) &&
                ui.exists(hasText("75%")) &&
                ui.exists(hasContentDescription("Cancelar descarga"))
        }
        composeRule.onNodeWithText("75%").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Cancelar descarga").assertIsDisplayed()
        fixture.awaitAudioRequest()
        fixture.releaseAudioDownload()

        awaitPrimarySuccess()
        fixture.verifyPersistedSongAndFile(fixture.persistedSong())

        composeRule
            .onNodeWithContentDescription("Biblioteca", useUnmergedTree = true)
            .performClick()
        ui.await("Biblioteca search action") {
            ui.exists(hasContentDescription("Buscar"))
        }
        composeRule.onNodeWithContentDescription("Buscar").performClick()
        composeRule.onNode(hasSetTextAction()).performTextInput(
            CatalogDownloadTestContract.TITLE
        )

        val downloadedSongTitle =
            hasText(CatalogDownloadTestContract.TITLE) and hasSetTextAction().not()
        ui.await("downloaded Song row in Biblioteca") {
            ui.exists(downloadedSongTitle)
        }
        composeRule
            .onNode(downloadedSongTitle, useUnmergedTree = true)
            .assertIsDisplayed()
    }

    @Test
    fun downloadContinuesAfterActivityAndViewModelAreDestroyed() {
        fixture.configureGatedSuccess()
        fixture.startPrimaryDownloadFromViewModel()
        fixture.awaitAudioRequest()
        ui.await("process runtime owns the active transfer") {
            fixture.isDownloadingAt(75)
        }

        fixture.destroyMainActivity()
        fixture.releaseAudioDownload()

        ui.await("download completes without a ViewModel") {
            fixture.isDownloadComplete()
        }
        fixture.verifyPersistedSongAndFile(fixture.persistedSong())
    }

    @Test
    fun interruptedQueueResumesAutomaticallyWhenUiReopens() {
        fixture.configureGatedSuccess()
        fixture.destroyMainActivity()
        fixture.seedInterruptedPrimaryDownload()

        fixture.launchMainActivity()
        fixture.awaitAudioRequest()
        ui.await("interrupted row returns to downloading") {
            fixture.isDownloadingAt(75)
        }
        fixture.releaseAudioDownload()

        ui.await("automatically resumed download completes") {
            fixture.isDownloadComplete()
        }
        fixture.verifyPersistedSongAndFile(fixture.persistedSong())
    }

    @Test
    fun duplicateOverwrite_keepsRoomIdentityAndPrivateData_replacesBytes() {
        fixture.configureGatedSuccess()
        val existing = fixture.seedExistingSong()
        fixture.startPrimaryDownloadFromViewModel()

        awaitDuplicateDialog()
        check(fixture.audioRequestCount() == 0) { fixture.diagnostic() }
        composeRule.onNodeWithText("Sobrescribir").performClick()
        awaitPrimaryDownloading()
        openDownloadsFromRoot()
        fixture.awaitAudioRequest()
        fixture.releaseAudioDownload()

        awaitPrimarySuccess()
        fixture.verifyOverwrite(existing)
    }

    @Test
    fun duplicateSaveAs_createsSecondSongFileAndTitle() {
        fixture.configureGatedSuccess()
        val existing = fixture.seedExistingSong()
        fixture.startPrimaryDownloadFromViewModel()

        awaitDuplicateDialog()
        check(fixture.audioRequestCount() == 0) { fixture.diagnostic() }
        composeRule.onNodeWithText("Crear nueva").performClick()
        composeRule.onNodeWithText("Nuevo título").assertIsDisplayed()
        composeRule.onNodeWithText(CatalogDownloadTestContract.SAVE_AS_TITLE).assertIsDisplayed()
        composeRule.onNodeWithText("Guardar").performClick()
        awaitPrimaryDownloading()
        openDownloadsFromRoot()
        fixture.awaitAudioRequest()
        fixture.releaseAudioDownload()

        awaitPrimarySuccess(displayTitle = CatalogDownloadTestContract.SAVE_AS_TITLE)
        fixture.verifySaveAs(existing)
    }

    @Test
    fun duplicateCancel_makesNoAudioRequestAndLeavesOriginalIntact() {
        fixture.configureGatedSuccess()
        val existing = fixture.seedExistingSong()
        fixture.startPrimaryDownloadFromViewModel()

        awaitDuplicateDialog()
        composeRule.onNodeWithText("Cancelar").performClick()

        ui.await("duplicate conflict dismissed without tracked download") {
            !ui.exists(hasText("Canción ya en la biblioteca")) &&
                fixture.isDownloadAbsent()
        }
        check(fixture.audioRequestCount() == 0) {
            "Duplicate Cancel reached audio HTTP. ${fixture.diagnostic()}"
        }
        fixture.verifyOriginalUnchanged(existing)
    }

    @Test
    fun downloadCancellationAfterPartialBytes_removesJobRowRoomFileAndPartial() {
        fixture.configurePartialCancellation()
        openPrimaryCatalogAndStartDownload()
        openDownloadsFromCatalog()
        fixture.awaitPartialBytesWritten()

        ui.await("partial download remains cancellable in Descargas") {
            fixture.isDownloadingAt(75) &&
                ui.exists(hasText(CatalogDownloadTestContract.TITLE)) &&
                ui.exists(hasContentDescription("Cancelar descarga"))
        }
        composeRule.onNodeWithContentDescription("Cancelar descarga").performClick()
        fixture.awaitCancellationFinished()

        ui.await("cancelled row is dismissed from Descargas") {
            fixture.isDownloadAbsent()
        }
        fixture.verifyNoPublicationOrPartial()
    }

    @Test
    fun forbiddenExhaustsRetries_shows403_thenRetrySucceeds() {
        fixture.configureForbiddenThenRecovery()
        openPrimaryCatalogAndStartDownload()
        openDownloadsFromCatalog()
        fixture.awaitForbiddenAttemptsExhausted()

        ui.await("403 ERROR with retry action") {
            fixture.isError(CatalogDownloadTestContract.TITLE) &&
                ui.exists(hasText("403", substring = true)) &&
                ui.exists(hasContentDescription("Reintentar"))
        }
        composeRule.onNodeWithText("403", substring = true).assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Reintentar").assertIsDisplayed()

        fixture.recoverForbiddenAudio()
        composeRule.onNodeWithContentDescription("Reintentar").performClick()

        awaitPrimarySuccess()
        check(fixture.audioRequestCount() == 6) {
            "Expected five automatic 403 attempts plus one successful retry. ${fixture.diagnostic()}"
        }
        fixture.verifyPersistedSongAndFile(fixture.persistedSong())
    }

    @Test
    fun queuedDownload_rechecksMeteredAfterPermit_withoutAudioRequest() {
        fixture.configureMeteredAfterPermit()
        fixture.startMeteredDownloadsFromViewModel()
        fixture.awaitThreeTransfersHoldingPermits()
        openDownloadsFromRoot()

        val firstThree = CatalogDownloadTestContract.METERED_TRACKS.take(3)
        val blocked = CatalogDownloadTestContract.METERED_TRACKS.last()
        ui.await("three active transfers and fourth queued") {
            firstThree.all { fixture.isDownloadingAt(75, it.title) } &&
                fixture.isQueued(blocked.title) &&
                ui.exists(hasText(blocked.title)) &&
                ui.exists(hasText(DownloadMessages.queued))
        }
        composeRule.onNodeWithText(blocked.title).performScrollTo().assertIsDisplayed()

        fixture.flipToMetered()
        fixture.releaseOneMeteredPermit()

        ui.await("queued transfer blocked after acquiring permit on metered network") {
            fixture.isError(blocked.title, DownloadMessages.blockedOnMetered) &&
                ui.exists(hasText(DownloadMessages.blockedOnMetered)) &&
                ui.exists(hasContentDescription("Reintentar"))
        }
        composeRule
            .onNodeWithText(DownloadMessages.blockedOnMetered)
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Reintentar").assertIsDisplayed()
        fixture.verifyMeteredTrackNeverRequestedOrPublished()
    }

    private fun openPrimaryCatalogAndStartDownload() {
        openCatalogSearch(
            query = CatalogDownloadTestContract.SEARCH_QUERY,
            expectedTitles = listOf(CatalogDownloadTestContract.TITLE)
        )
        composeRule.onAllNodesWithContentDescription("Agregar").onFirst().performClick()
    }

    private fun openCatalogSearch(query: String, expectedTitles: List<String>) {
        ui.await("Biblioteca root with Agregar action") {
            ui.exists(hasText("Agregar"))
        }
        composeRule.onNodeWithText("Agregar").performClick()
        composeRule.onNodeWithText("Agregar Música").assertIsDisplayed()
        composeRule.onNodeWithText("Catálogo").performClick()
        composeRule.onNode(hasSetTextAction()).performTextInput(query)
        composeRule.onNodeWithTag("catalog-search-submit").performClick()
        closeSoftKeyboard()

        ui.await("catalog fixture results ${expectedTitles.joinToString()}") {
            expectedTitles.all { title ->
                composeRule.onAllNodesWithText(title, useUnmergedTree = true)
                    .fetchSemanticsNodes(atLeastOneRootRequired = false)
                    .isNotEmpty()
            } &&
                composeRule.onAllNodesWithContentDescription("Agregar", useUnmergedTree = true)
                    .fetchSemanticsNodes(atLeastOneRootRequired = false)
                    .size >= expectedTitles.size
        }
        expectedTitles.forEach { title ->
            composeRule.onAllNodesWithText(title, useUnmergedTree = true)
                .onFirst()
                .assertIsDisplayed()
        }
    }

    private fun openDownloadsFromCatalog() {
        ui.await("catalog dialog ready to close") {
            ui.exists(hasContentDescription("Cerrar"))
        }
        composeRule.onNodeWithContentDescription("Cerrar").performClick()
        composeRule
            .onNodeWithContentDescription("Descargas", useUnmergedTree = true)
            .performClick()
    }

    private fun openDownloadsFromRoot() {
        composeRule
            .onNodeWithContentDescription("Descargas", useUnmergedTree = true)
            .performClick()
    }

    private fun awaitDuplicateDialog() {
        ui.await("duplicate conflict dialog") {
            ui.exists(hasText("Canción ya en la biblioteca")) &&
                ui.exists(hasText("Sobrescribir")) &&
                ui.exists(hasText("Crear nueva")) &&
                ui.exists(hasText("Cancelar"))
        }
        composeRule.onNodeWithText("Canción ya en la biblioteca").assertIsDisplayed()
    }

    private fun awaitPrimaryDownloading() {
        ui.await("resolved conflict begins the real audio transfer") {
            fixture.isDownloadingAt(75) &&
                !ui.exists(hasText("Canción ya en la biblioteca"))
        }
    }

    private fun awaitPrimarySuccess(displayTitle: String = CatalogDownloadTestContract.TITLE) {
        ui.await("fixture row completed in Descargas") {
            fixture.isDownloadComplete() &&
                ui.exists(hasText(displayTitle)) &&
                ui.exists(hasText("Descargada", substring = true)) &&
                ui.exists(hasContentDescription("Reproducir"))
        }
        composeRule.onAllNodesWithText("Descargada", substring = true)
            .onFirst()
            .assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Reproducir").assertIsDisplayed()
    }

    private companion object {
        const val UI_TIMEOUT_MS = 30_000L
    }
}
