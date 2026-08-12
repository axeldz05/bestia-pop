package com.bestiapop.android.service

import com.bestiapop.android.BuildConfig
import java.util.concurrent.atomic.AtomicReference

internal data class MusicServiceAppliedSettings(
    val leftGain: Float,
    val rightGain: Float,
    val targetGainMb: Int
)

/**
 * Debug-only observation seam for instrumented tests. Production and normal debug runs do no work
 * until a test installs an observer; it never changes playback state or captures audio.
 */
internal object MusicServiceSettingsProbe {
    private val observer =
        AtomicReference<((MusicServiceAppliedSettings) -> Unit)?>(null)

    fun observe(onApplied: (MusicServiceAppliedSettings) -> Unit): AutoCloseable {
        if (!BuildConfig.DEBUG) return AutoCloseable {}
        observer.set(onApplied)
        return AutoCloseable { observer.compareAndSet(onApplied, null) }
    }

    fun publish(settings: MusicServiceAppliedSettings) {
        if (BuildConfig.DEBUG) observer.get()?.invoke(settings)
    }
}
