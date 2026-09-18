package com.bestiapop.android.domain.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HtmlSanitizerTest {
    @Test
    fun `null or blank input returns null`() {
        assertNull(HtmlSanitizer.stripHtml(null))
        assertNull(HtmlSanitizer.stripHtml(""))
        assertNull(HtmlSanitizer.stripHtml("   \n\t  "))
        assertNull(HtmlSanitizer.stripHtml("<p></p>"))
    }

    @Test
    fun `strips basic paragraph and line break tags`() {
        val input = "<p>First paragraph.</p><p>Second paragraph.</p>"
        val expected = "First paragraph.\n\nSecond paragraph."
        assertEquals(expected, HtmlSanitizer.stripHtml(input))

        val brInput = "Line 1<br>Line 2<br/>Line 3<br />Line 4"
        val brExpected = "Line 1\nLine 2\nLine 3\nLine 4"
        assertEquals(brExpected, HtmlSanitizer.stripHtml(brInput))
    }

    @Test
    fun `decodes named and numeric html entities`() {
        val input = "<p>Rock &amp; Roll with &quot;Quotes&quot; and &#39;Apostrophes&#39; &lt;3</p>"
        val expected = "Rock & Roll with \"Quotes\" and 'Apostrophes' <3"
        assertEquals(expected, HtmlSanitizer.stripHtml(input))

        val numericInput = "Enjoy&#33; Hex&#x21;"
        val numericExpected = "Enjoy! Hex!"
        assertEquals(numericExpected, HtmlSanitizer.stripHtml(numericInput))
    }

    @Test
    fun `strips nested html tags and normalizes excessive newlines`() {
        val input =
            "<div><p>Title</p><br><br><br>" +
                "<p><strong>Description</strong> with <em>emphasis</em> and <a href=\"#\">link</a>.</p></div>"
        val expected = "Title\n\nDescription with emphasis and link."
        assertEquals(expected, HtmlSanitizer.stripHtml(input))
    }
}
