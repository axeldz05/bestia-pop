package com.bestiapop.android.testutil

import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToString

internal class ComposeE2EProbe(
    private val rule: ComposeTestRule,
    private val timeoutMs: Long,
    private val diagnostics: () -> String
) {
    fun exists(matcher: SemanticsMatcher): Boolean =
        rule.onAllNodes(matcher, useUnmergedTree = true)
            .fetchSemanticsNodes(atLeastOneRootRequired = false)
            .isNotEmpty()

    fun await(description: String, condition: () -> Boolean) {
        try {
            rule.waitUntil(timeoutMillis = timeoutMs, condition = condition)
        } catch (failure: Throwable) {
            val tree = runCatching {
                rule.onRoot(useUnmergedTree = true).printToString()
            }.getOrElse { "Semantics unavailable: ${it.message}" }
            throw AssertionError(
                "Timed out waiting for $description. ${diagnostics()}\n$tree",
                failure
            )
        }
    }
}
