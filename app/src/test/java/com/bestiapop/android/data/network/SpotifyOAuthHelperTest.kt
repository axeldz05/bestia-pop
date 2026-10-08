package com.bestiapop.android.data.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpotifyOAuthHelperTest {
    @Test
    fun generateCodeVerifier_generatesValidPkceVerifier() {
        val verifier1 = SpotifyOAuthHelper.generateCodeVerifier()
        val verifier2 = SpotifyOAuthHelper.generateCodeVerifier()

        assertTrue(verifier1.length in 43..128)
        assertTrue(verifier1.all { it.isLetterOrDigit() || it in "-._~" })
        assertNotEquals(verifier1, verifier2)
    }

    @Test
    fun generateCodeChallenge_matchesRfc7636TestVector() {
        val rfcVerifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"
        val expectedChallenge = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM"

        val challenge = SpotifyOAuthHelper.generateCodeChallenge(rfcVerifier)
        assertEquals(expectedChallenge, challenge)
    }

    @Test
    fun generateState_generatesCryptographicRandomState() {
        val state1 = SpotifyOAuthHelper.generateState()
        val state2 = SpotifyOAuthHelper.generateState()

        assertTrue(state1.length >= 16)
        assertNotEquals(state1, state2)
    }

    @Test
    fun buildAuthorizationUrl_containsExpectedParameters() {
        val clientId = "test-client-123"
        val challenge = "E9Melhoa2OwvFrGMTJguCH5rtG6Ft30QYrSTdgM50Ts"
        val state = "sec-state-abc"

        val url = SpotifyOAuthHelper.buildAuthorizationUrl(clientId, challenge, state)

        assertTrue(url.startsWith("https://accounts.spotify.com/authorize?"))
        assertTrue(url.contains("client_id=$clientId"))
        assertTrue(url.contains("response_type=code"))
        assertTrue(url.contains("redirect_uri=bestiapop%3A%2F%2Fspotify-callback"))
        assertTrue(url.contains("code_challenge_method=S256"))
        assertTrue(url.contains("code_challenge=$challenge"))
        assertTrue(url.contains("state=sec-state-abc"))
        assertTrue(url.contains("playlist-read-private"))
        assertTrue(url.contains("user-library-read"))
    }
}
