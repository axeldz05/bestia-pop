package com.bestiapop.android.data.model

/**
 * Centralized user-facing messages and labels for offline mode.
 */
object OfflineMessages {
    val connectionDisabled get() = "Conexión a internet desactivada"
    val blockedByUser get() = "Conexión a internet desactivada por el usuario"
    val telemetryPausedBanner
        get() = "Conexión a internet desactivada. La telemetría técnica y el reporte de cierres están pausados por completo."
    val telemetryPausedSubtitle
        get() = "Pausado por modo sin internet — ningún reporte se enviará al servidor"
    val listenBrainzPausedBanner
        get() = "Conexión a internet desactivada. El scrobbling se acumulará localmente y las recomendaciones en línea están pausadas."
    val settingsTitle get() = "Desactivar conexión a internet"
    val settingsSubtitle
        get() = "Desactiva la pestaña Descubrir, telemetría, scrobbling y búsquedas en internet (incluyendo identificación)"
}
