package com.bestiapop.android.ui.update

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bestiapop.android.data.update.PackageManagerApkValidator
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PackageManagerApkValidatorInstrumentedTest {

    @Test
    fun installedTargetApk_isAcceptedAsSamePackage() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val installedApk = File(context.applicationInfo.sourceDir)

        val result = PackageManagerApkValidator(context).validate(installedApk)

        assertTrue(result.exceptionOrNull()?.message, result.isSuccess)
    }

    @Test
    fun arbitraryBytes_areRejected() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val invalid = File(context.cacheDir, "invalid-update-${System.nanoTime()}.apk")
        try {
            invalid.writeText("not an apk")

            val result = PackageManagerApkValidator(context).validate(invalid)

            assertTrue(result.isFailure)
        } finally {
            invalid.delete()
        }
    }
}
