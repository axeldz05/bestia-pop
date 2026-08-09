package com.bestiapop.android.data.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException

class GitHubUpdateClient(
    private val repository: String,
    private val userAgent: String,
    private val http: OkHttpClient = defaultClient
) {
    suspend fun fetchLatest(): Result<AppUpdateInfo> = withContext(Dispatchers.IO) {
        if (repository.isBlank()) {
            return@withContext Result.failure(IllegalStateException("GITHUB_REPOSITORY vacío"))
        }
        try {
            val releaseBody = get(GitHubReleaseUrls.apiLatestUrl(repository), githubApi = true)
            val assets = GitHubReleaseParser.parseReleaseApi(releaseBody)
                ?: return@withContext Result.failure(
                    IllegalStateException("El release no tiene APK y latest.json")
                )
            val version = GitHubReleaseParser.parseLatestJson(get(assets.latestJsonUrl))
                ?: return@withContext Result.failure(IllegalStateException("latest.json inválido"))
            Result.success(
                AppUpdateInfo(
                    versionCode = version.versionCode,
                    versionName = version.versionName,
                    apkUrl = assets.apkUrl,
                    changelog = assets.changelog
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun get(url: String, githubApi: Boolean = false): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent)
            .apply {
                if (githubApi) header("Accept", "application/vnd.github+json")
            }
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
        private val defaultClient: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build()
    }
}
