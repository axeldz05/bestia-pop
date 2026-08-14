package com.bestiapop.android.data.util

import android.util.Log
import com.google.firebase.crashlytics.FirebaseCrashlytics

/**
 * Thin wrapper around Firebase Crashlytics for non-fatal reporting with context keys.
 * Uncaught crashes are collected automatically once Crashlytics is enabled in [com.bestiapop.android.BestiaPopApplication].
 */
object CrashReporter {

    private const val TAG = "BestiaPop"

    fun setKey(key: String, value: String) {
        runCatching {
            FirebaseCrashlytics.getInstance().setCustomKey(key, value.take(MAX_VALUE_LEN))
        }
    }

    fun log(message: String) {
        runCatching { Log.d(TAG, message) }
        runCatching {
            FirebaseCrashlytics.getInstance().log(message.take(MAX_LOG_LEN))
        }
    }

    fun recordNonFatal(throwable: Throwable, keys: Map<String, String> = emptyMap()) {
        val details = if (keys.isNotEmpty()) " keys=$keys" else ""
        runCatching { Log.w(TAG, "Non-fatal exception recorded: ${throwable.message}$details", throwable) }
        runCatching {
            val crashlytics = FirebaseCrashlytics.getInstance()
            keys.forEach { (k, v) ->
                crashlytics.setCustomKey(k, v.take(MAX_VALUE_LEN))
            }
            crashlytics.recordException(throwable)
        }
    }

    private const val MAX_VALUE_LEN = 1024
    private const val MAX_LOG_LEN = 1024
}
