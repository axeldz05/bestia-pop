package com.bestiapop.android.ui.download

import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.printToString
import androidx.test.espresso.Espresso.closeSoftKeyboard
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
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
        awaitUi("Biblioteca root with Agregar action") {
            nodeExists(hasText("Agregar"))
        }
        composeRule.onNodeWithText("Agregar").performClick()
        composeRule.onNodeWithText("Agregar Música").assertIsDisplayed()

        composeRule.onNodeWithText("Catálogo").performClick()
        composeRule.onNode(hasSetTextAction()).performTextInput(
            CatalogDownloadTestContract.SEARCH_QUERY
        )
        composeRule.onNodeWithTag("catalog-search-submit").performClick()
        closeSoftKeyboard()

        awaitUi("catalog fixture result") {
            nodeExists(hasText(CatalogDownloadTestContract.TITLE))
        }
        composeRule.onNodeWithText(CatalogDownloadTestContract.TITLE).assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Agregar").performClick()

        composeRule.onNodeWithContentDescription("Cerrar").performClick()
        composeRule
            .onNodeWithContentDescription("Descargas", useUnmergedTree = true)
            .performClick()

        awaitUi("fixture row downloading in Descargas") {
            nodeExists(hasText(CatalogDownloadTestContract.TITLE)) &&
                fixture.isDownloadingAt(75) &&
                nodeExists(hasText("75%")) &&
                nodeExists(hasContentDescription("Cancelar descarga"))
        }
        composeRule.onNodeWithText(CatalogDownloadTestContract.TITLE).assertIsDisplayed()
        composeRule.onNodeWithText("75%").assertIsDisplayed()
        composeRule
            .onNodeWithContentDescription("Cancelar descarga")
            .assertIsDisplayed()

        fixture.releaseAudioDownload()

        awaitUi("fixture row completed in Descargas") {
            fixture.isDownloadComplete() &&
                nodeExists(hasText(CatalogDownloadTestContract.TITLE)) &&
                nodeExists(hasText("Descargada", substring = true)) &&
                nodeExists(hasContentDescription("Reproducir"))
        }
        composeRule.onNodeWithText("Descargada", substring = true).assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Reproducir").assertIsDisplayed()

        fixture.verifyPersistedSongAndFile(fixture.persistedSong())

        composeRule
            .onNodeWithContentDescription("Biblioteca", useUnmergedTree = true)
            .performClick()
        awaitUi("Biblioteca search action") {
            nodeExists(hasContentDescription("Buscar"))
        }
        composeRule.onNodeWithContentDescription("Buscar").performClick()
        composeRule.onNode(hasSetTextAction()).performTextInput(
            CatalogDownloadTestContract.TITLE
        )

        val downloadedSongTitle =
            hasText(CatalogDownloadTestContract.TITLE) and hasSetTextAction().not()
        awaitUi("downloaded Song row in Biblioteca") {
            nodeExists(downloadedSongTitle)
        }
        composeRule
            .onNode(downloadedSongTitle, useUnmergedTree = true)
            .assertIsDisplayed()
    }

    private fun nodeExists(matcher: SemanticsMatcher): Boolean =
        composeRule.onAllNodes(matcher, useUnmergedTree = true)
            .fetchSemanticsNodes(atLeastOneRootRequired = false)
            .isNotEmpty()

    private fun awaitUi(description: String, condition: () -> Boolean) {
        try {
            composeRule.waitUntil(timeoutMillis = UI_TIMEOUT_MS, condition = condition)
        } catch (failure: Throwable) {
            val tree = runCatching {
                composeRule.onRoot(useUnmergedTree = true).printToString()
            }.getOrElse { "Semantics unavailable: ${it.message}" }
            throw AssertionError(
                "Timed out waiting for $description. ${fixture.diagnostic()}\n$tree",
                failure
            )
        }
    }

    private companion object {
        const val UI_TIMEOUT_MS = 15_000L
    }
}
