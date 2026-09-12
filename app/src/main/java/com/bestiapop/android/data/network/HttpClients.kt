package com.bestiapop.android.data.network

import android.content.Context
import okhttp3.Cache
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * One connection pool, dispatcher and DNS cache for the whole app. Every module derives from these
 * with `newBuilder()` instead of building its own client — there were six independent ones (seven at
 * runtime, since the WiFi server builds its own [com.bestiapop.android.data.repository.MusicRepository]).
 *
 * `callTimeout` is the reason this exists beyond pooling: none of the old clients set it, so a
 * response that trickles bytes slower than the read timeout could hang a request indefinitely.
 */
object HttpClients {

    private const val HTTP_CACHE_SIZE_BYTES = 25L * 1024 * 1024 // 25 MB

    private val baseBuilder: OkHttpClient.Builder = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .connectionPool(ConnectionPool(5, 30, TimeUnit.SECONDS))

    /** Catalog / API calls: short, bounded end to end, with HTTP disk caching when initialized. */
    var api: OkHttpClient = baseBuilder.build()
        private set

    /**
     * Byte transfers (audio download, APK update). No overall call cap — a large file legitimately
     * takes minutes — but a stalled socket still trips the read timeout.
     */
    var transfer: OkHttpClient = api.newBuilder()
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .readTimeout(5, TimeUnit.MINUTES)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()
        private set

    /**
     * Installs an on-disk HTTP cache (RFC 7234) for API responses.
     * Safe to call from [com.bestiapop.android.BestiaPopApplication.onCreate].
     */
    fun initialize(context: Context) {
        try {
            val cacheDir = File(context.cacheDir, "http_api_cache")
            val cache = Cache(cacheDir, HTTP_CACHE_SIZE_BYTES)
            api = baseBuilder.cache(cache).build()
            transfer = api.newBuilder()
                .callTimeout(0, TimeUnit.MILLISECONDS)
                .readTimeout(5, TimeUnit.MINUTES)
                .followRedirects(true)
                .followSslRedirects(true)
                .build()
        } catch (_: Exception) {
        }
    }
}
