package com.bestiapop.android.domain.util

/**
 * Normalize album titles for comparison / collision detection.
 *
 * Handles:
 * - trim + collapse whitespace
 * - Unicode ellipsis `…` (U+2026) → `...`
 * - common UTF-8 mojibake of that ellipsis (seen in real library rows)
 */
fun normalizeAlbumName(name: String): String {
    var s = name.trim()
    // U+2026 HORIZONTAL ELLIPSIS
    s = s.replace("\u2026", "...")
    // Device DB mojibake of UTF-8 ellipsis: â + U+0080 + ¦
    s = s.replace("\u00E2\u0080\u00A6", "...")
    // Classic Windows-1252 mojibake of ellipsis: â€¦
    s = s.replace("\u00E2\u20AC\u00A6", "...")
    s = s.replace(Regex("\\s+"), " ")
    return s
}

fun albumNamesMatch(a: String, b: String): Boolean =
    normalizeAlbumName(a).equals(normalizeAlbumName(b), ignoreCase = true)
