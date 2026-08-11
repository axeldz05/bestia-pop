package com.bestiapop.android.data.update

import com.bestiapop.android.data.network.HttpClients
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlin.coroutines.cancellation.CancellationException

class GitHubUpdateClient(
    private val repository: String,
    private val userAgent: String,
    private val http: OkHttpClient = defaultClient
) {
    suspend fun fetchReleases(): Result<List<AppRelease>> = withContext(Dispatchers.IO) {
        if (repository.isBlank()) {
            return@withContext Result.failure(IllegalStateException("GITHUB_REPOSITORY vacío"))
        }
        try {
            Result.success(GitHubReleaseParser.parseReleases(get(GitHubReleaseUrls.apiReleasesUrl(repository))))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun get(url: String): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent)
            .header("Accept", "application/vnd.github+json")
            .get()
            .build()
        http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                error("GitHub HTTP ${response.code}")
            }
            return body
        }
    }

    companion object {
        private val defaultClient: OkHttpClient = HttpClients.api
    }
}
