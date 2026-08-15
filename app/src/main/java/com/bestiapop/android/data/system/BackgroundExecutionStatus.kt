package com.bestiapop.android.data.system

import android.app.ActivityManager
import android.app.AppOpsManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.provider.Settings

data class BackgroundExecutionStatus(
    val backgroundRestricted: Boolean = false,
    val ignoringBatteryOptimizations: Boolean = false,
    val runAnyInBackgroundIgnored: Boolean = false
) {
    val blocksBackgroundPlayback: Boolean
        get() = backgroundRestricted || runAnyInBackgroundIgnored
}

data class BackgroundRestrictionGuidance(
    val title: String,
    val body: String
)

internal fun backgroundRestrictionGuidance(
    manufacturer: String
): BackgroundRestrictionGuidance {
    val brand = manufacturer.lowercase()
    val body = when {
        "motorola" in brand || "lenovo" in brand ->
            "Este teléfono puede cortar la reproducción al ir al inicio. Fijá BestiaPop en Recientes para que no la limpie."
        "samsung" in brand ->
            "Samsung puede poner la app en reposo y cortar la reproducción. En Ajustes de batería, agregá BestiaPop a las apps que nunca duermen."
        "xiaomi" in brand || "redmi" in brand || "poco" in brand || "blackshark" in brand ->
            "Este teléfono puede cortar la reproducción en segundo plano. Activá el inicio automático y desactivá las restricciones de batería para BestiaPop."
        else ->
            "Android restringió la actividad en segundo plano. En la ficha de BestiaPop, poné el uso de batería en Sin restricciones."
    }
    return BackgroundRestrictionGuidance(
        title = "Segundo plano restringido",
        body = body
    )
}

internal const val OPSTR_RUN_ANY_IN_BACKGROUND = "android:run_any_in_background"

internal const val OEM_POWER_BACKGROUND_CLEAN_ACTION = "unisoc.intent.action.POWER_BACKGROUND_CLEAN"
internal const val OEM_LOCK_SCREEN_BATTERY_SAVE_KEY = "lock_screen_battery_save"
internal const val SETTINGS_FRAGMENT_ARG_KEY = ":settings:fragment_args_key"

/** Unisoc/Motorola battery page that hosts “cerrar al apagar/bloquear pantalla”. Implicit only. */
internal fun oemScreenOffCleanupSettingsIntent(): Intent =
    Intent(OEM_POWER_BACKGROUND_CLEAN_ACTION).apply {
        addCategory(Intent.CATEGORY_DEFAULT)
        putExtra(SETTINGS_FRAGMENT_ARG_KEY, OEM_LOCK_SCREEN_BATTERY_SAVE_KEY)
    }

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
            },
            runAnyInBackgroundIgnored = { runAnyInBackgroundIgnored(appContext) }
        )
    }

    fun applicationDetailsIntent(context: Context, newTask: Boolean = false): Intent =
        applicationDetailsSettingsIntent(context.packageName, newTask)

    fun restrictionGuidance(manufacturer: String = Build.MANUFACTURER): BackgroundRestrictionGuidance =
        backgroundRestrictionGuidance(manufacturer)

    fun openApplicationDetails(context: Context) {
        try {
            context.startActivity(applicationDetailsIntent(context))
        } catch (_: ActivityNotFoundException) {
        } catch (_: SecurityException) {
        }
    }

    fun oemScreenOffCleanupIntent(context: Context): Intent? {
        val intent = oemScreenOffCleanupSettingsIntent()
        return intent.takeIf { it.resolveActivity(context.packageManager) != null }
    }

    fun openOemScreenOffCleanupSettings(context: Context): Boolean {
        val intent = oemScreenOffCleanupIntent(context) ?: return false
        if (context !is android.app.Activity) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }

    private fun runAnyInBackgroundIgnored(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false
        val appOps = context.getSystemService(AppOpsManager::class.java) ?: return false
        @Suppress("DEPRECATION")
        val mode = appOps.checkOpNoThrow(
            OPSTR_RUN_ANY_IN_BACKGROUND,
            Process.myUid(),
            context.packageName
        )
        return mode == AppOpsManager.MODE_IGNORED || mode == AppOpsManager.MODE_ERRORED
    }
}

internal fun applicationDetailsSettingsIntent(packageName: String, newTask: Boolean = false): Intent =
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
        data = Uri.fromParts("package", packageName, null)
        if (newTask) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

internal fun resolveBackgroundExecutionStatus(
    sdkInt: Int,
    backgroundRestricted: () -> Boolean,
    ignoringBatteryOptimizations: () -> Boolean,
    runAnyInBackgroundIgnored: () -> Boolean = { false }
): BackgroundExecutionStatus = BackgroundExecutionStatus(
    backgroundRestricted =
        sdkInt >= Build.VERSION_CODES.P && backgroundRestricted(),
    ignoringBatteryOptimizations = ignoringBatteryOptimizations(),
    runAnyInBackgroundIgnored =
        sdkInt >= Build.VERSION_CODES.P && runAnyInBackgroundIgnored()
)
