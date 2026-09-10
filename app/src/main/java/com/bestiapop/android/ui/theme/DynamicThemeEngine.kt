package com.bestiapop.android.ui.theme

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.util.LruCache
import androidx.core.graphics.drawable.toBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import coil.imageLoader
import coil.request.ImageRequest
import coil.size.Precision
import com.bestiapop.android.data.model.ColorSchemeData
import com.bestiapop.android.data.model.CustomTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.scan

/**
 * Engine that extracts harmonious, high-contrast, visually comfortable color palettes
 * from song album artwork.
 *
 * Implements WCAG 2.1 contrast guardrails and provides an intelligent congruent fallback
 * when covers are monochromatic or muddy.
 */
object DynamicThemeEngine {

    private const val THUMBNAIL_SIZE_PX = 64
    private const val CACHE_MAX_SIZE = 50

    // Safe congruent base colors when album art lacks distinct chroma
    private val SAFE_CONGRUENT_PURPLE = Color(0xFF9D4EDD)
    private val SAFE_CONGRUENT_CYAN = Color(0xFF00F5D4)
    private val SAFE_CONGRUENT_AMBER = Color(0xFFFF9E00)
    private val SAFE_CONGRUENT_CORAL = Color(0xFFFF6B6B)

    private val themeCache = LruCache<String, CustomTheme>(CACHE_MAX_SIZE)

    /**
     * Level 2 data bundle: captures dynamic theme and the artwork URI it was derived from.
     */
    data class DynamicThemeState(
        val theme: CustomTheme,
        val artworkUri: String? = null
    )

    /**
     * Level 1 primitive: Extracts a dynamic [CustomTheme] from the given [artworkUri].
     * Returns a cached theme if available, or derives a new one in [Dispatchers.IO].
     * If [artworkUri] is null/blank or fails to load, gracefully returns [fallback] or [fallbackTheme].
     */
    suspend fun extractDynamicTheme(
        context: Context,
        artworkUri: String?,
        isDark: Boolean = true,
        fallback: CustomTheme? = null
    ): CustomTheme {
        if (artworkUri.isNullOrBlank()) {
            return fallback ?: fallbackTheme(isDark)
        }

        val cacheKey = "${artworkUri}_${if (isDark) "dark" else "light"}"
        themeCache.get(cacheKey)?.let { return it }

        val theme = withContext(Dispatchers.IO) {
            val bitmap = loadThumbnailBitmap(context, artworkUri)
            if (bitmap != null) {
                deriveThemeFromBitmap(bitmap, artworkUri, isDark)
            } else {
                fallback ?: fallbackTheme(isDark)
            }
        }

        themeCache.put(cacheKey, theme)
        return theme
    }

    /**
     * Level 2 compressed wrapper: Resolves transition to next dynamic theme state.
     * If [artworkUri] is non-blank and differs from [currentState], derives a new theme.
     * If [artworkUri] is null/blank or fails to load, gracefully retains [currentState.theme].
     */
    suspend fun resolveNextTheme(
        context: Context,
        artworkUri: String?,
        currentState: DynamicThemeState,
        isDark: Boolean = true
    ): DynamicThemeState {
        val trimmedUri = artworkUri?.takeIf { it.isNotBlank() }
        if (trimmedUri == null || trimmedUri == currentState.artworkUri) {
            return currentState
        }
        val newTheme = extractDynamicTheme(
            context = context,
            artworkUri = trimmedUri,
            isDark = isDark,
            fallback = currentState.theme
        )
        return DynamicThemeState(theme = newTheme, artworkUri = trimmedUri)
    }

    /**
     * Level 3 high-level utility: Emits a reactive flow of [CustomTheme] that tracks
     * artwork changes, retains the last dynamic theme across empty queue / null items,
     * and triggers [onThemeChanged] whenever a new dynamic theme is derived for persistence.
     */
    fun dynamicThemeFlow(
        context: Context,
        artworkUriFlow: Flow<String?>,
        initialState: DynamicThemeState,
        isDark: Boolean = true,
        onThemeChanged: (suspend (DynamicThemeState) -> Unit)? = null
    ): Flow<CustomTheme> = artworkUriFlow
        .scan(initialState) { state, currentUri ->
            resolveNextTheme(
                context = context,
                artworkUri = currentUri,
                currentState = state,
                isDark = isDark
            )
        }
        .distinctUntilChangedBy { it.theme.colors }
        .onEach { state ->
            onThemeChanged?.invoke(state)
        }
        .map { it.theme }

    private suspend fun loadThumbnailBitmap(context: Context, uri: String): Bitmap? {
        return try {
            val request = ImageRequest.Builder(context)
                .data(uri)
                .size(THUMBNAIL_SIZE_PX)
                .precision(Precision.INEXACT)
                .allowHardware(false) // Software bitmap for pixel access
                .build()

            val result = context.imageLoader.execute(request)
            val drawable = result.drawable ?: return null

            drawable.toBitmap(
                width = drawable.intrinsicWidth.coerceAtLeast(1),
                height = drawable.intrinsicHeight.coerceAtLeast(1),
                config = Bitmap.Config.ARGB_8888
            )
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Analyzes pixels, groups them into hue buckets, scores them for vibrance and balance,
     * applies WCAG contrast and comfortable ambient tinting.
     */
    internal fun deriveThemeFromBitmap(
        bitmap: Bitmap,
        artworkUri: String,
        isDark: Boolean
    ): CustomTheme {
        val width = bitmap.width
        val height = bitmap.height
        val totalPixels = width * height

        // Sample every Nth pixel for sub-millisecond execution
        val step = max(1, (totalPixels / 800))
        val pixels = IntArray(totalPixels)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        val bucketCount = 12
        val bucketHueStep = 360f / bucketCount
        val bucketPopulations = IntArray(bucketCount)
        val bucketSatSums = FloatArray(bucketCount)
        val bucketLumSums = FloatArray(bucketCount)

        var saturatedPixelCount = 0
        var sampledCount = 0
        var avgLuminanceSum = 0f

        for (i in 0 until totalPixels step step) {
            val pixel = pixels[i]
            val a = (pixel ushr 24) and 0xFF
            if (a < 128) continue

            val r = (pixel ushr 16) and 0xFF
            val g = (pixel ushr 8) and 0xFF
            val b = pixel and 0xFF

            val color = Color(r / 255f, g / 255f, b / 255f)
            val hsl = ThemeHarmonizer.rgbToHsl(color)
            val h = hsl[0]
            val s = hsl[1]
            val l = hsl[2]

            sampledCount++
            avgLuminanceSum += l

            // Filter out near-blacks and near-whites from dominant hue calculations
            if (l < 0.08f || l > 0.95f) continue

            if (s >= 0.15f) {
                val bucketIndex = ((h / bucketHueStep).toInt() % bucketCount).coerceIn(0, bucketCount - 1)
                bucketPopulations[bucketIndex]++
                bucketSatSums[bucketIndex] += s
                bucketLumSums[bucketIndex] += l
                saturatedPixelCount++
            }
        }

        val avgLuminance = if (sampledCount > 0) avgLuminanceSum / sampledCount else 0.5f

        // Check if the album art is monochrome, grayscale, or muddy (e.g. B&W album covers)
        val isMonochromeOrMuddy = saturatedPixelCount < max(12, (sampledCount * 0.06f).roundToInt())

        val primaryColor: Color
        val secondaryColor: Color
        val accentColor: Color
        val baseHue: Float

        if (isMonochromeOrMuddy) {
            // SAFE CONGRUENT FALLBACK: Select a harmonious, comfortable accent color
            // based on the luminance temperature of the monochromatic cover
            val safeColor = when {
                avgLuminance > 0.65f -> SAFE_CONGRUENT_CYAN
                avgLuminance > 0.40f -> SAFE_CONGRUENT_PURPLE
                avgLuminance > 0.25f -> SAFE_CONGRUENT_AMBER
                else -> SAFE_CONGRUENT_CORAL
            }
            val safeHsl = ThemeHarmonizer.rgbToHsl(safeColor)
            baseHue = safeHsl[0]
            primaryColor = safeColor
            secondaryColor = ThemeHarmonizer.hslToColor((baseHue + 35f) % 360f, 0.70f, 0.60f)
            accentColor = ThemeHarmonizer.hslToColor((baseHue + 180f) % 360f, 0.75f, 0.65f)
        } else {
            // Find best vibrant hue bucket using population * saturation * luminance score
            var bestScore = -1f
            var bestBucket = 0
            var secondScore = -1f
            var secondBucket = 1

            for (i in 0 until bucketCount) {
                val pop = bucketPopulations[i]
                if (pop == 0) continue
                val avgS = bucketSatSums[i] / pop
                val avgL = bucketLumSums[i] / pop

                // Target ideal luminance between 0.45 and 0.70 for primary
                val lumWeight = 1f - (abs(avgL - 0.58f) * 1.5f).coerceIn(0f, 0.8f)
                val score = pop * (avgS * avgS) * lumWeight

                if (score > bestScore) {
                    secondScore = bestScore
                    secondBucket = bestBucket
                    bestScore = score
                    bestBucket = i
                } else if (score > secondScore) {
                    secondScore = score
                    secondBucket = i
                }
            }

            val primaryHue = (bestBucket * bucketHueStep + bucketHueStep / 2f) % 360f
            val bestPop = bucketPopulations[bestBucket].coerceAtLeast(1)
            val primarySat = (bucketSatSums[bestBucket] / bestPop).coerceIn(0.55f, 0.95f)
            val primaryLum = if (isDark) 0.65f else 0.42f

            baseHue = primaryHue
            primaryColor = ThemeHarmonizer.hslToColor(primaryHue, primarySat, primaryLum)

            val secondaryHue = if (secondScore > 0f) {
                (secondBucket * bucketHueStep + bucketHueStep / 2f) % 360f
            } else {
                (primaryHue + 35f) % 360f
            }
            secondaryColor = ThemeHarmonizer.hslToColor(secondaryHue, 0.65f, if (isDark) 0.60f else 0.48f)
            accentColor = ThemeHarmonizer.hslToColor((primaryHue + 180f) % 360f, 0.75f, if (isDark) 0.70f else 0.45f)
        }

        // Ambient dark background and surfaces tinted with base hue for comfortable immersion
        val background: Color
        val surface: Color
        val surfaceVariant: Color

        if (isDark) {
            background = ThemeHarmonizer.hslToColor(baseHue, 0.08f, 0.055f)
            surface = ThemeHarmonizer.hslToColor(baseHue, 0.11f, 0.10f)
            surfaceVariant = ThemeHarmonizer.hslToColor(baseHue, 0.14f, 0.15f)
        } else {
            background = ThemeHarmonizer.hslToColor(baseHue, 0.06f, 0.97f)
            surface = Color.White
            surfaceVariant = ThemeHarmonizer.hslToColor(baseHue, 0.09f, 0.92f)
        }

        // Apply strict WCAG contrast guardrail
        val adjustedPrimary = ThemeHarmonizer.ensureContrast(
            color = primaryColor,
            background = background,
            minRatio = 4.5f,
            isDarkTheme = isDark
        )

        val adjustedSecondary = ThemeHarmonizer.ensureContrast(
            color = secondaryColor,
            background = background,
            minRatio = 3.5f,
            isDarkTheme = isDark
        )

        val adjustedAccent = ThemeHarmonizer.ensureContrast(
            color = accentColor,
            background = background,
            minRatio = 3.5f,
            isDarkTheme = isDark
        )

        val colorData = ColorSchemeData(
            primary = adjustedPrimary.toArgb().toLong(),
            onPrimary = ThemeHarmonizer.bestOnColor(adjustedPrimary).toArgb().toLong(),
            secondary = adjustedSecondary.toArgb().toLong(),
            background = background.toArgb().toLong(),
            surface = surface.toArgb().toLong(),
            surfaceVariant = surfaceVariant.toArgb().toLong(),
            accent = adjustedAccent.toArgb().toLong()
        )

        return CustomTheme(
            id = ThemePresets.DYNAMIC_THEME_ID,
            name = "Dinámico por Canción",
            colors = colorData,
            isDark = isDark
        )
    }

    private fun fallbackTheme(isDark: Boolean): CustomTheme {
        return if (isDark) {
            ThemePresets.MidnightDark.copy(
                id = ThemePresets.DYNAMIC_THEME_ID,
                name = "Dinámico por Canción"
            )
        } else {
            ThemePresets.CleanLight.copy(
                id = ThemePresets.DYNAMIC_THEME_ID,
                name = "Dinámico por Canción"
            )
        }
    }
}
