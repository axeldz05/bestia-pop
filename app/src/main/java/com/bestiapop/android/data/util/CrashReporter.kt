package com.bestiapop.android.data.util

import com.google.firebase.crashlytics.FirebaseCrashlytics

/**
 * Thin wrapper around Firebase Crashlytics for non-fatal reporting with context keys.
 * Uncaught crashes are collected automatically once Crashlytics is enabled in [com.bestiapop.android.BestiaPopApplication].
 */
object CrashReporter {

    fun setKey(key: String, value: String) {
        runCatching {
            FirebaseCrashlytics.getInstance().setCustomKey(key, value.take(MAX_VALUE_LEN))
        }
    }

    fun log(message: String) {
        runCatching {
            FirebaseCrashlytics.getInstance().log(message.take(MAX_LOG_LEN))
        }
    }

    fun recordNonFatal(throwable: Throwable, keys: Map<String, String> = emptyMap()) {
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
