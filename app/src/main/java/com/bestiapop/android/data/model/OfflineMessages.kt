package com.bestiapop.android.data.model

/**
 * Centralized user-facing messages and labels for offline mode.
 */
object OfflineMessages {
    const val connectionDisabled = "Conexión a internet desactivada"
    const val blockedByUser = "Conexión a internet desactivada por el usuario"
    const val telemetryPausedBanner =
        "Conexión a internet desactivada. La telemetría técnica y el reporte de cierres están pausados por completo."
    const val telemetryPausedSubtitle =
        "Pausado por modo sin internet — ningún reporte se enviará al servidor"
    const val listenBrainzPausedBanner =
        "Conexión a internet desactivada. El scrobbling se acumulará localmente y las recomendaciones en línea están pausadas."
    const val settingsTitle = "Desactivar conexión a internet"
    const val settingsSubtitle =
        "Desactiva la pestaña Descubrir, telemetría, scrobbling y búsquedas en internet (incluyendo identificación)"
}
