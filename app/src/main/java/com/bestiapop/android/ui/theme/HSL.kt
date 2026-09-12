package com.bestiapop.android.ui.theme

import androidx.compose.ui.graphics.Color
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Encapsulates HSL color values for cleaner parameter passing.
 */
data class HSL(
    val hue: Float,
    val saturation: Float,
    val lightness: Float,
    val alpha: Float = 1f
) {
    init {
        require(hue >= 0f && hue <= 360f) { "Hue must be between 0 and 360" }
        require(saturation in 0f..1f) { "Saturation must be between 0 and 1" }
        require(lightness in 0f..1f) { "Lightness must be between 0 and 1" }
        require(alpha in 0f..1f) { "Alpha must be between 0 and 1" }
    }

    /**
     * Converts this HSL color to a Compose Color.
     */
    fun toColor(): Color {
        val h = (hue % 360f + 360f) % 360f
        val s = saturation.coerceIn(0f, 1f)
        val l = lightness.coerceIn(0f, 1f)

        if (s == 0f) {
            return Color(l, l, l, alpha)
        }

        val q = if (l < 0.5f) l * (1f + s) else l + s - l * s
        val p = 2f * l - q

        fun hueToRgb(t: Float): Float {
            var tc = t
            if (tc < 0f) tc += 1f
            if (tc > 1f) tc -= 1f
            return when {
                tc < 1f / 6f -> p + (q - p) * 6f * tc
                tc < 1f / 2f -> q
                tc < 2f / 3f -> p + (q - p) * (2f / 3f - tc) * 6f
                else -> p
            }
        }

        val hk = h / 360f
        val r = hueToRgb(hk + 1f / 3f)
        val g = hueToRgb(hk)
        val b = hueToRgb(hk - 1f / 3f)

        return Color(r.coerceIn(0f, 1f), g.coerceIn(0f, 1f), b.coerceIn(0f, 1f), alpha)
    }

    companion object {
        /**
         * Creates an HSL from a Color.
         */
        fun fromColor(color: Color): HSL {
            val r = color.red
            val g = color.green
            val b = color.blue

            val maxC = max(r, max(g, b))
            val minC = min(r, min(g, b))
            val delta = maxC - minC

            val l = (maxC + minC) / 2f

            if (delta == 0f) {
                return HSL(0f, 0f, l, color.alpha)
            }

            val s = if (l > 0.5f) delta / (2f - maxC - minC) else delta / (maxC + minC)

            var h = when (maxC) {
                r -> ((g - b) / delta) + (if (g < b) 6f else 0f)
                g -> ((b - r) / delta) + 2f
                else -> ((r - g) / delta) + 4f
            } * 60f

            if (h < 0f) h += 360f

            return HSL(h.coerceIn(0f, 360f), s.coerceIn(0f, 1f), l.coerceIn(0f, 1f), color.alpha)
        }
    }
}
