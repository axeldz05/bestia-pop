package com.bestiapop.android.ui.download

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.espresso.Espresso.closeSoftKeyboard
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
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
        ui.await("Biblioteca root with Agregar action") {
            ui.exists(hasText("Agregar"))
        }
        composeRule.onNodeWithText("Agregar").performClick()
        composeRule.onNodeWithText("Agregar Música").assertIsDisplayed()

        composeRule.onNodeWithText("Catálogo").performClick()
        composeRule.onNode(hasSetTextAction()).performTextInput(
            CatalogDownloadTestContract.SEARCH_QUERY
        )
        composeRule.onNodeWithTag("catalog-search-submit").performClick()
        closeSoftKeyboard()

        ui.await("catalog fixture result") {
            ui.exists(hasText(CatalogDownloadTestContract.TITLE))
        }
        composeRule.onNodeWithText(CatalogDownloadTestContract.TITLE).assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Agregar").performClick()

        composeRule.onNodeWithContentDescription("Cerrar").performClick()
        composeRule
            .onNodeWithContentDescription("Descargas", useUnmergedTree = true)
            .performClick()

        ui.await("fixture row downloading in Descargas") {
            ui.exists(hasText(CatalogDownloadTestContract.TITLE)) &&
                fixture.isDownloadingAt(75) &&
                ui.exists(hasText("75%")) &&
                ui.exists(hasContentDescription("Cancelar descarga"))
        }
        composeRule.onNodeWithText(CatalogDownloadTestContract.TITLE).assertIsDisplayed()
        composeRule.onNodeWithText("75%").assertIsDisplayed()
        composeRule
            .onNodeWithContentDescription("Cancelar descarga")
            .assertIsDisplayed()

        fixture.releaseAudioDownload()

        ui.await("fixture row completed in Descargas") {
            fixture.isDownloadComplete() &&
                ui.exists(hasText(CatalogDownloadTestContract.TITLE)) &&
                ui.exists(hasText("Descargada", substring = true)) &&
                ui.exists(hasContentDescription("Reproducir"))
        }
        composeRule.onNodeWithText("Descargada", substring = true).assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Reproducir").assertIsDisplayed()

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

    private companion object {
        const val UI_TIMEOUT_MS = 15_000L
    }
}
