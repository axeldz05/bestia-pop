package com.bestiapop.android

import android.app.Application
import com.bestiapop.android.data.util.CrashReporter
import com.google.firebase.crashlytics.FirebaseCrashlytics

class BestiaPopApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Collect crashes/non-fatals on release/beta builds only (not local debug noise).
        FirebaseCrashlytics.getInstance().setCrashlyticsCollectionEnabled(!BuildConfig.DEBUG)
        CrashReporter.log("BestiaPopApplication.onCreate version=${BuildConfig.VERSION_NAME}")
    }
}
