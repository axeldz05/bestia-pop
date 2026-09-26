package com.bestiapop.android.domain.util

import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneticMatchHelperTest {
    @Test
    fun matchesPhonetically_gansAnRous_matchesGunsNRoses() {
        val matches =
            PhoneticMatchHelper.matchesPhonetically(
                query = "gans an rous",
                targetText = "Guns N' Roses",
            )
        assertTrue(matches)
    }

    @Test
    fun matchesPhonetically_kueen_matchesQueen() {
        val matches =
            PhoneticMatchHelper.matchesPhonetically(
                query = "kueen",
                targetText = "Queen",
            )
        assertTrue(matches)
    }

    @Test
    fun matchesPhonetically_eisiDisi_matchesAcDc() {
        val matches =
            PhoneticMatchHelper.matchesPhonetically(
                query = "eisi disi",
                targetText = "AC/DC",
            )
        assertTrue(matches)
    }

    @Test
    fun matchesPhonetically_maicolYacson_matchesMichaelJackson() {
        val matches =
            PhoneticMatchHelper.matchesPhonetically(
                query = "maicol yacson",
                targetText = "Michael Jackson",
            )
        assertTrue(matches)
    }
}
