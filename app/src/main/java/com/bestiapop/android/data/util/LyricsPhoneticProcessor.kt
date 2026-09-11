package com.bestiapop.android.data.util

import android.os.Build
import com.bestiapop.android.data.preferences.JapanesePhoneticMode
import java.util.Locale

object LyricsPhoneticProcessor {

    /** Retorna true si la línea contiene caracteres de alfabetos no latinos (japonés, cirílico, coreano, etc.). */
    fun hasNonLatinScript(text: String): Boolean {
        if (text.isBlank()) return false
        for (i in 0 until text.length) {
            val ch = text[i]
            if (ch in '\u3040'..'\u30FF' ||
                ch in '\u4E00'..'\u9FFF' ||
                ch in '\u3400'..'\u4DBF' ||
                ch in '\uF900'..'\uFAFF' ||
                ch in '\uAC00'..'\uD7AF' ||
                ch in '\u1100'..'\u11FF' ||
                ch in '\u3130'..'\u318F' ||
                ch in '\u0400'..'\u04FF' ||
                ch in '\u0370'..'\u03FF' ||
                ch in '\u0600'..'\u06FF' ||
                ch in '\u0590'..'\u05FF' ||
                ch in '\u0E00'..'\u0E7F'
            ) {
                return true
            }
        }
        return false
    }

    /** Retorna true si la línea contiene caracteres japoneses (kana o kanji con contexto). */
    fun isJapanese(text: String): Boolean {
        if (text.isBlank()) return false
        for (i in 0 until text.length) {
            val ch = text[i]
            if (ch in '\u3040'..'\u309F' || ch in '\u30A0'..'\u30FF' || ch in '\u4E00'..'\u9FFF' || ch in '\u3400'..'\u4DBF') {
                return true
            }
        }
        return false
    }

    /**
     * Transforma una línea al formato fonético deseado según el modo.
     * Si se provee [romanizedCandidate] (de red o transliterador), se usa como base.
     */
    fun formatPhoneticLine(
        original: String,
        romanizedCandidate: String?,
        japaneseMode: JapanesePhoneticMode
    ): String? {
        if (!hasNonLatinScript(original)) return null

        val isJap = isJapanese(original)
        if (isJap && japaneseMode == JapanesePhoneticMode.HIRAGANA) {
            if (!romanizedCandidate.isNullOrBlank()) {
                val hira = romajiToHiragana(romanizedCandidate)
                if (hira.isNotBlank() && hira != original) return hira
            }
            // Fallback offline: convertir katakana a hiragana
            val directHira = katakanaToHiragana(original)
            if (directHira != original) return directHira
        }

        if (!romanizedCandidate.isNullOrBlank()) {
            return romanizedCandidate.trim()
        }

        // Fallback offline con ICU en Android 10+
        val icuFallback = transliterateOffline(original)
        if (!icuFallback.isNullOrBlank() && icuFallback != original) {
            return if (isJap && japaneseMode == JapanesePhoneticMode.HIRAGANA) {
                romajiToHiragana(icuFallback)
            } else {
                icuFallback
            }
        }

        return null
    }

    /**
     * Convierte texto Katakana a Hiragana (desplazamiento de codepoints Unicode).
     */
    fun katakanaToHiragana(text: String): String {
        val sb = StringBuilder(text.length)
        for (ch in text) {
            if (ch in '\u30A1'..'\u30F6') {
                sb.append((ch.code - 0x60).toChar())
            } else {
                sb.append(ch)
            }
        }
        return sb.toString()
    }

    /**
     * Transliteración offline usando Android ICU en API 29+ para alfabetos no latinos.
     */
    private fun transliterateOffline(text: String): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return try {
            val transliterator = android.icu.text.Transliterator.getInstance("Any-Latin; Latin-ASCII")
            transliterator.transliterate(text)
        } catch (_: Throwable) {
            null
        }
    }

    private val ROMAJI_TABLE: List<Pair<String, String>> = listOf(
        "kya" to "きゃ", "kyu" to "きゅ", "kyo" to "きょ",
        "sha" to "しゃ", "shu" to "しゅ", "sho" to "しょ",
        "cha" to "ちゃ", "chu" to "ちゅ", "cho" to "ちょ",
        "nya" to "にゃ", "nyu" to "にゅ", "nyo" to "にょ",
        "hya" to "ひゃ", "hyu" to "ひゅ", "hyo" to "ひょ",
        "mya" to "みゃ", "myu" to "みゅ", "myo" to "みょ",
        "rya" to "りゃ", "ryu" to "りゅ", "ryo" to "りょ",
        "gya" to "ぎゃ", "gyu" to "ぎゅ", "gyo" to "ぎょ",
        "ja" to "じゃ", "ju" to "じゅ", "jo" to "じょ",
        "bya" to "びゃ", "byu" to "びゅ", "byo" to "びょ",
        "pya" to "ぴゃ", "pyu" to "ぴゅ", "pyo" to "ぴょ",
        "shi" to "し", "chi" to "ち", "tsu" to "つ",
        "dji" to "ぢ", "dzu" to "づ", "fu" to "ふ",
        "ka" to "か", "ki" to "き", "ku" to "く", "ke" to "け", "ko" to "こ",
        "sa" to "さ", "si" to "し", "su" to "す", "se" to "せ", "so" to "そ",
        "ta" to "た", "ti" to "ち", "tu" to "つ", "te" to "て", "to" to "と",
        "na" to "な", "ni" to "に", "nu" to "ぬ", "ne" to "ね", "no" to "の",
        "ha" to "は", "hi" to "ひ", "hu" to "ふ", "he" to "へ", "ho" to "ほ",
        "ma" to "ま", "mi" to "み", "mu" to "む", "me" to "め", "mo" to "も",
        "ya" to "や", "yu" to "ゆ", "yo" to "よ",
        "ra" to "ら", "ri" to "り", "ru" to "る", "re" to "れ", "ro" to "ろ",
        "wa" to "わ", "wo" to "を",
        "ga" to "が", "gi" to "ぎ", "gu" to "ぐ", "ge" to "げ", "go" to "ご",
        "za" to "ざ", "ji" to "じ", "zu" to "ず", "ze" to "ぜ", "zo" to "ぞ",
        "da" to "だ", "de" to "で", "do" to "ど",
        "ba" to "ば", "bi" to "び", "bu" to "ぶ", "be" to "べ", "bo" to "ぼ",
        "pa" to "ぱ", "pi" to "ぴ", "pu" to "ぷ", "pe" to "ぺ", "po" to "ぽ",
        "a" to "あ", "i" to "い", "u" to "う", "e" to "え", "o" to "お",
        "nn" to "ん", "n" to "ん"
    )

    /**
     * Convierte Rōmaji (Hepburn) a Hiragana preservando espacios y signos de puntuación.
     */
    fun romajiToHiragana(romaji: String): String {
        if (romaji.isBlank()) return ""
        val sbNorm = java.lang.StringBuilder(romaji.length + 4)
        for (idx in 0 until romaji.length) {
            when (val c = romaji[idx].lowercaseChar()) {
                'ā' -> sbNorm.append("aa")
                'ī' -> sbNorm.append("ii")
                'ū' -> sbNorm.append("uu")
                'ē' -> sbNorm.append("ee")
                'ō' -> sbNorm.append("ou")
                else -> sbNorm.append(c)
            }
        }
        val normalized = sbNorm.toString()

        val sb = StringBuilder(normalized.length)
        var i = 0
        val n = normalized.length

        while (i < n) {
            val c = normalized[i]

            // Sokuon (pequeño 'っ' ante consonante doble como 'tt', 'kk', 'pp', 'ss', etc.)
            if (i + 1 < n && c == normalized[i + 1] && c in "bcdfghjklmpqrstvwxyz" && c != 'n') {
                sb.append('っ')
                i++
                continue
            }

            var matched = false
            for ((key, value) in ROMAJI_TABLE) {
                if (normalized.startsWith(key, i)) {
                    // Especial para 'n': si le sigue una vocal o 'y', no es 'ん'
                    if (key == "n" && i + 1 < n) {
                        val next = normalized[i + 1]
                        if (next in "aiueoy") {
                            continue
                        }
                    }
                    sb.append(value)
                    i += key.length
                    matched = true
                    break
                }
            }

            if (!matched) {
                sb.append(c)
                i++
            }
        }

        return sb.toString()
    }
}
