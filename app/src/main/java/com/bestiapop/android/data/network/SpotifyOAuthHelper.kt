package com.bestiapop.android.data.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

data class SpotifyTokenResponse(
    val accessToken: String,
    val refreshToken: String?,
    val expiresInSeconds: Long,
)

object SpotifyOAuthHelper {
    const val REDIRECT_URI = "bestiapop://spotify-callback"
    const val DEFAULT_CLIENT_ID = "c25603848bcf4a1bb37e193231464010"
    private const val AUTH_ENDPOINT = "https://accounts.spotify.com/authorize"
    private const val TOKEN_ENDPOINT = "https://accounts.spotify.com/api/token"
    private const val SCOPES = "playlist-read-private playlist-read-collaborative user-library-read"

    fun generateCodeVerifier(): String {
        val secureRandom = SecureRandom()
        val codeVerifierBytes = ByteArray(32)
        secureRandom.nextBytes(codeVerifierBytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(codeVerifierBytes)
    }

    fun generateCodeChallenge(codeVerifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = codeVerifier.toByteArray(Charsets.US_ASCII)
        val hash = digest.digest(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(hash)
    }

    fun generateState(): String {
        val secureRandom = SecureRandom()
        val stateBytes = ByteArray(16)
        secureRandom.nextBytes(stateBytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(stateBytes)
    }

    fun buildAuthorizationUrl(
        clientId: String,
        codeChallenge: String,
        state: String,
    ): String {
        val encodedRedirect = URLEncoder.encode(REDIRECT_URI, "UTF-8")
        val encodedScope = URLEncoder.encode(SCOPES, "UTF-8")
        val encodedChallenge = URLEncoder.encode(codeChallenge, "UTF-8")
        val encodedState = URLEncoder.encode(state, "UTF-8")

        return "$AUTH_ENDPOINT?client_id=$clientId&response_type=code&redirect_uri=$encodedRedirect" +
            "&code_challenge_method=S256&code_challenge=$encodedChallenge&state=$encodedState&scope=$encodedScope"
    }

    suspend fun exchangeAuthorizationCode(
        clientId: String,
        code: String,
        codeVerifier: String,
    ): Result<SpotifyTokenResponse> =
        withContext(Dispatchers.IO) {
            runCatching {
                val formBody =
                    FormBody
                        .Builder()
                        .add("client_id", clientId)
                        .add("grant_type", "authorization_code")
                        .add("code", code)
                        .add("redirect_uri", REDIRECT_URI)
                        .add("code_verifier", codeVerifier)
                        .build()

                val request =
                    Request
                        .Builder()
                        .url(TOKEN_ENDPOINT)
                        .post(formBody)
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .build()

                val responseBody =
                    HttpClients.api.newCall(request).execute().use { response ->
                        val body = response.body?.string().orEmpty()
                        if (!response.isSuccessful) {
                            val errorDesc =
                                runCatching {
                                    val errObj = JSONObject(body)
                                    errObj.optString("error_description").ifBlank { errObj.optString("error") }
                                }.getOrNull()
                            val detail = if (!errorDesc.isNullOrBlank()) ": $errorDesc" else ""
                            throw IOException("Error al autenticar en Spotify (HTTP ${response.code})$detail")
                        }
                        body
                    }

                val json = JSONObject(responseBody)
                val accessToken = json.getString("access_token")
                val refreshToken = json.optString("refresh_token").takeIf { it.isNotBlank() }
                val expiresIn = json.optLong("expires_in", 3600L)

                SpotifyTokenResponse(
                    accessToken = accessToken,
                    refreshToken = refreshToken,
                    expiresInSeconds = expiresIn,
                )
            }
        }

    suspend fun refreshAccessToken(
        clientId: String,
        refreshToken: String,
    ): Result<SpotifyTokenResponse> =
        withContext(Dispatchers.IO) {
            runCatching {
                val formBody =
                    FormBody
                        .Builder()
                        .add("client_id", clientId)
                        .add("grant_type", "refresh_token")
                        .add("refresh_token", refreshToken)
                        .build()

                val request =
                    Request
                        .Builder()
                        .url(TOKEN_ENDPOINT)
                        .post(formBody)
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .build()

                val responseBody =
                    HttpClients.api.newCall(request).execute().use { response ->
                        val body = response.body?.string().orEmpty()
                        if (!response.isSuccessful) {
                            val errorDesc =
                                runCatching {
                                    val errObj = JSONObject(body)
                                    errObj.optString("error_description").ifBlank { errObj.optString("error") }
                                }.getOrNull()
                            val detail = if (!errorDesc.isNullOrBlank()) ": $errorDesc" else ""
                            throw IOException("Error al renovar sesión de Spotify (HTTP ${response.code})$detail")
                        }
                        body
                    }

                val json = JSONObject(responseBody)
                val accessToken = json.getString("access_token")
                val newRefreshToken = json.optString("refresh_token").takeIf { it.isNotBlank() } ?: refreshToken
                val expiresIn = json.optLong("expires_in", 3600L)

                SpotifyTokenResponse(
                    accessToken = accessToken,
                    refreshToken = newRefreshToken,
                    expiresInSeconds = expiresIn,
                )
            }
        }
}
