package com.bestiapop.android.testutil

import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry

/**
 * Mirrors install.sh's sideload allowances for device tests that require sustained playback.
 */
internal object SideloadPlaybackAppOps {
    fun acquire(): AutoCloseable {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val packageName = instrumentation.targetContext.packageName
        val userId = checkNotNull(execute("am get-current-user").trim().toIntOrNull()) {
            "Could not determine the instrumentation Android user"
        }
        val previousRestriction = execute(
            "cmd activity get-bg-restriction-level --user $userId $packageName"
        ).trim()
        check(previousRestriction in RESTRICTION_LEVELS) {
            "Could not snapshot sideload background restriction: $previousRestriction"
        }
        val previousAppOpMode = appOpRestoreMode(
            execute("cmd appops get --user $userId $packageName RUN_ANY_IN_BACKGROUND")
        )

        try {
            execute(
                "cmd activity set-bg-restriction-level --user $userId " +
                    "$packageName adaptive_bucket"
            )
            execute(
                "cmd appops set --user $userId $packageName " +
                    "RUN_ANY_IN_BACKGROUND allow"
            )
            execute("cmd appops write-settings")

            val restriction = execute(
                "cmd activity get-bg-restriction-level --user $userId $packageName"
            ).trim()
            check(restriction == "adaptive_bucket") {
                "Sideload background restriction was not applied: $restriction"
            }
            val appOp = execute(
                "cmd appops get --user $userId $packageName RUN_ANY_IN_BACKGROUND"
            )
            check(appOpMode(appOp) == "allow") {
                "RUN_ANY_IN_BACKGROUND was not enabled for sideload playback: $appOp"
            }
        } catch (failure: Throwable) {
            runCatching {
                restore(
                    packageName = packageName,
                    userId = userId,
                    restriction = previousRestriction,
                    appOpMode = previousAppOpMode
                )
            }.exceptionOrNull()?.let(failure::addSuppressed)
            throw failure
        }

        return Restoration {
            restore(
                packageName = packageName,
                userId = userId,
                restriction = previousRestriction,
                appOpMode = previousAppOpMode
            )
        }
    }

    private fun restore(
        packageName: String,
        userId: Int,
        restriction: String,
        appOpMode: String
    ) {
        var firstFailure: Throwable? = null
        fun restoreStep(block: () -> Unit) {
            runCatching(block).exceptionOrNull()?.let { failure ->
                if (firstFailure == null) firstFailure = failure
                else firstFailure?.addSuppressed(failure)
            }
        }

        restoreStep {
            execute(
                "cmd activity set-bg-restriction-level --user $userId " +
                    "$packageName $restriction"
            )
        }
        restoreStep {
            execute(
                "cmd appops set --user $userId $packageName " +
                    "RUN_ANY_IN_BACKGROUND $appOpMode"
            )
        }
        restoreStep { execute("cmd appops write-settings") }
        firstFailure?.let { throw it }
    }

    private fun appOpRestoreMode(output: String): String =
        if (output.lineSequence().any { it.trim() == "No operations." }) {
            "default"
        } else {
            checkNotNull(appOpMode(output)) {
                "Could not snapshot RUN_ANY_IN_BACKGROUND: $output"
            }
        }

    private fun appOpMode(output: String): String? =
        APP_OP_MODE.find(output)?.groupValues?.get(1)

    private class Restoration(private val restore: () -> Unit) : AutoCloseable {
        private var closed = false

        override fun close() {
            synchronized(this) {
                if (closed) return
                closed = true
            }
            restore()
        }
    }

    private fun execute(command: String): String {
        val uiAutomation = InstrumentationRegistry.getInstrumentation().uiAutomation
        return ParcelFileDescriptor.AutoCloseInputStream(
            uiAutomation.executeShellCommand(command)
        ).bufferedReader().use { it.readText() }
    }

    private val APP_OP_MODE = Regex("""RUN_ANY_IN_BACKGROUND:\s*([a-z_]+)""")
    private val RESTRICTION_LEVELS = setOf(
        "unrestricted",
        "exempted",
        "adaptive_bucket",
        "restricted_bucket",
        "background_restricted",
        "hibernation"
    )
}
