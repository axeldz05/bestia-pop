package com.bestiapop.android.domain.util

/**
 * Utility for sanitizing HTML markup and decoding common entities into plain text.
 */
object HtmlSanitizer {
    private val TAG_REGEX = Regex("<[^>]*>")
    private val BR_REGEX = Regex("<br\\s*/?>", RegexOption.IGNORE_CASE)
    private val P_CLOSE_OPEN_REGEX = Regex("</p>\\s*<p[^>]*>", RegexOption.IGNORE_CASE)
    private val DECIMAL_ENTITY_REGEX = Regex("&#(\\d+);")
    private val HEX_ENTITY_REGEX = Regex("&#x([0-9a-fA-F]+);")

    /**
     * Strips HTML tags, transforms line breaks and paragraphs into clean newlines,
     * decodes named and numeric HTML entities, and normalizes excessive whitespace.
     */
    fun stripHtml(html: String?): String? {
        if (html.isNullOrBlank()) return null
        val converted =
            html
                .replace(BR_REGEX, "\n")
                .replace(P_CLOSE_OPEN_REGEX, "\n\n")
                .replace(TAG_REGEX, "")
                .replace("&amp;", "&")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&apos;", "'")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&nbsp;", " ")

        val decodedDecimals =
            DECIMAL_ENTITY_REGEX.replace(converted) { matchResult ->
                val code = matchResult.groupValues[1].toIntOrNull()
                if (code != null && Character.isValidCodePoint(code)) {
                    String(Character.toChars(code))
                } else {
                    matchResult.value
                }
            }

        val decodedHex =
            HEX_ENTITY_REGEX.replace(decodedDecimals) { matchResult ->
                val code = matchResult.groupValues[1].toIntOrNull(16)
                if (code != null && Character.isValidCodePoint(code)) {
                    String(Character.toChars(code))
                } else {
                    matchResult.value
                }
            }

        return decodedHex
            .lines()
            .map { it.trim() }
            .joinToString("\n")
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
            .takeIf { it.isNotBlank() }
    }
}
