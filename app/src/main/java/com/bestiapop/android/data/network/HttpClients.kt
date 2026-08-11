package com.bestiapop.android.data.network

import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
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

    /** Catalog / API calls: short, bounded end to end. */
    val api: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .connectionPool(ConnectionPool(10, 5, TimeUnit.MINUTES))
        .build()

    /**
     * Byte transfers (audio download, APK update). No overall call cap — a large file legitimately
     * takes minutes — but a stalled socket still trips the read timeout.
     */
    val transfer: OkHttpClient = api.newBuilder()
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .readTimeout(5, TimeUnit.MINUTES)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()
}
