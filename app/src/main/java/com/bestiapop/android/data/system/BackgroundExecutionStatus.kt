package com.bestiapop.android.data.system

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.PowerManager

data class BackgroundExecutionStatus(
    val backgroundRestricted: Boolean = false,
    val ignoringBatteryOptimizations: Boolean = false
)

object BackgroundExecutionProbe {
    fun current(context: Context): BackgroundExecutionStatus {
        val appContext = context.applicationContext
        val activityManager = appContext.getSystemService(ActivityManager::class.java)
        val powerManager = appContext.getSystemService(PowerManager::class.java)
        val backgroundRestricted =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
                activityManager?.isBackgroundRestricted == true
        return resolveBackgroundExecutionStatus(
            sdkInt = Build.VERSION.SDK_INT,
            backgroundRestricted = { backgroundRestricted },
            ignoringBatteryOptimizations = {
                powerManager?.isIgnoringBatteryOptimizations(appContext.packageName) == true
            }
        )
    }
}

internal fun resolveBackgroundExecutionStatus(
    sdkInt: Int,
    backgroundRestricted: () -> Boolean,
    ignoringBatteryOptimizations: () -> Boolean
): BackgroundExecutionStatus = BackgroundExecutionStatus(
    backgroundRestricted =
        sdkInt >= Build.VERSION_CODES.P && backgroundRestricted(),
    ignoringBatteryOptimizations = ignoringBatteryOptimizations()
)
