package com.bestiapop.android.data.util

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class QrCodeGeneratorTest {

    @Test
    fun generateBitmap_validContent_createsExpectedDimensions() {
        val bitmap = QrCodeGenerator.generateBitmap(
            content = "https://github.com/axeldz05/bestia-pop/releases/latest",
            sizePx = 256
        )

        assertNotNull(bitmap)
        val nonNullBitmap: Bitmap = requireNotNull(bitmap)
        assertEquals(256, nonNullBitmap.width)
        assertEquals(256, nonNullBitmap.height)
    }

    @Test
    fun generateBitmap_blankContent_returnsNull() {
        assertNull(QrCodeGenerator.generateBitmap("", sizePx = 256))
        assertNull(QrCodeGenerator.generateBitmap("   ", sizePx = 256))
    }

    @Test
    fun generateBitmap_invalidSize_returnsNull() {
        assertNull(QrCodeGenerator.generateBitmap("https://example.com", sizePx = 0))
        assertNull(QrCodeGenerator.generateBitmap("https://example.com", sizePx = -10))
    }

    @Test
    fun generateBitmap_hasCustomForegroundAndBackgroundColors() {
        val fg = Color.RED
        val bg = Color.BLUE
        val bitmap = QrCodeGenerator.generateBitmap(
            content = "test-qr",
            sizePx = 100,
            foregroundColor = fg,
            backgroundColor = bg
        )

        assertNotNull(bitmap)
        val nonNullBitmap: Bitmap = requireNotNull(bitmap)
        var hasFg = false
        var hasBg = false
        for (y in 0 until nonNullBitmap.height) {
            for (x in 0 until nonNullBitmap.width) {
                val pixel = nonNullBitmap.getPixel(x, y)
                if (pixel == fg) hasFg = true
                if (pixel == bg) hasBg = true
            }
        }
        assertTrue("Bitmap should contain foreground pixels", hasFg)
        assertTrue("Bitmap should contain background pixels", hasBg)
    }
}
