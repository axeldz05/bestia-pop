package com.bestiapop.android.data.util

import com.bestiapop.android.data.preferences.JapanesePhoneticMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsPhoneticProcessorTest {

    @Test
    fun hasNonLatinScript_distinguishesScripts() {
        assertFalse(LyricsPhoneticProcessor.hasNonLatinScript("Hello world!"))
        assertFalse(LyricsPhoneticProcessor.hasNonLatinScript("Canción de amor 123, ¿cómo estás?"))
        assertFalse(LyricsPhoneticProcessor.hasNonLatinScript(""))

        assertTrue(LyricsPhoneticProcessor.hasNonLatinScript("沈むように溶けてゆくように"))
        assertTrue(LyricsPhoneticProcessor.hasNonLatinScript("夜に駆ける"))
        assertTrue(LyricsPhoneticProcessor.hasNonLatinScript("かたかな"))
        assertTrue(LyricsPhoneticProcessor.hasNonLatinScript("아침 햇살에 눈을 떠"))
        assertTrue(LyricsPhoneticProcessor.hasNonLatinScript("Группа крови на рукаве"))
        assertTrue(LyricsPhoneticProcessor.hasNonLatinScript("天青色等烟雨"))
    }

    @Test
    fun isJapanese_identifiesJapaneseScripts() {
        assertTrue(LyricsPhoneticProcessor.isJapanese("沈むように溶けてゆくように"))
        assertTrue(LyricsPhoneticProcessor.isJapanese("カタカナ"))
        assertTrue(LyricsPhoneticProcessor.isJapanese("ひらがな"))

        assertFalse(LyricsPhoneticProcessor.isJapanese("Hello world"))
        assertFalse(LyricsPhoneticProcessor.isJapanese("Привет мир"))
    }

    @Test
    fun katakanaToHiragana_convertsCorrectly() {
        assertEquals("かたかな", LyricsPhoneticProcessor.katakanaToHiragana("カタカナ"))
        assertEquals("らーめん", LyricsPhoneticProcessor.katakanaToHiragana("ラーメン"))
        assertEquals("あいうえお", LyricsPhoneticProcessor.katakanaToHiragana("アイウエオ"))
    }

    @Test
    fun romajiToHiragana_convertsCorrectly() {
        assertEquals("よる に かける", LyricsPhoneticProcessor.romajiToHiragana("Yoru ni kakeru"))
        assertEquals("しずむ よう に とけて ゆく よう に", LyricsPhoneticProcessor.romajiToHiragana("Shizumu yō ni tokete yuku yō ni"))
        assertEquals("きって", LyricsPhoneticProcessor.romajiToHiragana("kitte"))
        assertEquals("ざんこく な てんし", LyricsPhoneticProcessor.romajiToHiragana("zankoku na tenshi"))
    }

    @Test
    fun formatPhoneticLine_modes() {
        // Texto latino -> no necesita guía fonética
        assertNull(
            LyricsPhoneticProcessor.formatPhoneticLine(
                original = "Hello world",
                romanizedCandidate = "Hello world",
                japaneseMode = JapanesePhoneticMode.ROMAJI
            )
        )

        // Modo Romaji
        val romajiResult = LyricsPhoneticProcessor.formatPhoneticLine(
            original = "夜に駆ける",
            romanizedCandidate = "Yoru ni kakeru",
            japaneseMode = JapanesePhoneticMode.ROMAJI
        )
        assertEquals("Yoru ni kakeru", romajiResult)

        // Modo Hiragana
        val hiraganaResult = LyricsPhoneticProcessor.formatPhoneticLine(
            original = "夜に駆ける",
            romanizedCandidate = "Yoru ni kakeru",
            japaneseMode = JapanesePhoneticMode.HIRAGANA
        )
        assertEquals("よる に かける", hiraganaResult)

        // Cirílico en modo Romaji
        val cyrillicResult = LyricsPhoneticProcessor.formatPhoneticLine(
            original = "Группа крови",
            romanizedCandidate = "Gruppa krovi",
            japaneseMode = JapanesePhoneticMode.ROMAJI
        )
        assertEquals("Gruppa krovi", cyrillicResult)
    }
}
