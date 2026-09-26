package com.bestiapop.android.data.preferences

import com.bestiapop.android.data.model.TrackMeta
import com.bestiapop.android.domain.util.TrackMatchKeys
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.UUID
import kotlin.math.log10
import kotlin.math.round

const val MIN_EQUALIZER_BANDS = 5
const val MAX_EQUALIZER_BANDS = 12
const val DEFAULT_EQUALIZER_BAND_COUNT = 5
const val MIN_EQUALIZER_GAIN_DB = -12f
const val MAX_EQUALIZER_GAIN_DB = 12f

const val EQUALIZER_PRESET_FLAT = "Plano"
const val EQUALIZER_PRESET_CUSTOM = "Personalizado"

val DEFAULT_5_BAND_GAINS: List<Float> = listOf(0f, 0f, 0f, 0f, 0f)

data class EqualizerBand(
    val index: Int,
    val centerFrequencyHz: Int,
    val gainDb: Float,
)

data class EqualizerPreset(
    val name: String,
    val targetCurve: List<Pair<Int, Float>>,
)

enum class EqualizerTargetType(
    val label: String,
) {
    SONG("Canción"),
    ALBUM("Álbum"),
    ARTIST("Artista"),
    CUSTOM_PRESET("Preset"),
}

data class DynamicEqualizerRule(
    val id: String = UUID.randomUUID().toString(),
    val targetType: EqualizerTargetType,
    val targetKey: String,
    val targetName: String,
    val targetArtist: String = "",
    val bandCount: Int = DEFAULT_EQUALIZER_BAND_COUNT,
    val bandGainsDb: List<Float> = DEFAULT_5_BAND_GAINS,
    val presetName: String = EQUALIZER_PRESET_CUSTOM,
    val createdAt: Long = System.currentTimeMillis(),
) {
    val bands: List<EqualizerBand>
        get() {
            val freqs = centerFrequenciesForBandCount(bandCount)
            return freqs.mapIndexed { index, freq ->
                EqualizerBand(
                    index = index,
                    centerFrequencyHz = freq,
                    gainDb = bandGainsDb.getOrElse(index) { 0f },
                )
            }
        }
}

data class EqualizerSettings(
    val enabled: Boolean = false,
    val dynamicEnabled: Boolean = true,
    val bandCount: Int = DEFAULT_EQUALIZER_BAND_COUNT,
    val bandGainsDb: List<Float> = DEFAULT_5_BAND_GAINS,
    val presetName: String = EQUALIZER_PRESET_FLAT,
    val dynamicRules: List<DynamicEqualizerRule> = emptyList(),
) {
    val bands: List<EqualizerBand>
        get() {
            val freqs = centerFrequenciesForBandCount(bandCount)
            return freqs.mapIndexed { index, freq ->
                EqualizerBand(
                    index = index,
                    centerFrequencyHz = freq,
                    gainDb = bandGainsDb.getOrElse(index) { 0f },
                )
            }
        }
}

val EQUALIZER_PRESETS: List<EqualizerPreset> =
    listOf(
        EqualizerPreset(
            name = EQUALIZER_PRESET_FLAT,
            targetCurve = emptyList(),
        ),
        EqualizerPreset(
            name = "Graves",
            targetCurve =
                listOf(
                    32 to 6.5f,
                    63 to 6.0f,
                    125 to 4.5f,
                    250 to 2.0f,
                    500 to 0.5f,
                    1000 to 0f,
                    4000 to 0f,
                    16000 to 0f,
                ),
        ),
        EqualizerPreset(
            name = "Agudos",
            targetCurve =
                listOf(
                    32 to 0f,
                    250 to 0f,
                    1000 to 1.0f,
                    2000 to 2.5f,
                    4000 to 4.5f,
                    8000 to 6.0f,
                    16000 to 7.0f,
                ),
        ),
        EqualizerPreset(
            name = "Rock",
            targetCurve =
                listOf(
                    32 to 4.5f,
                    63 to 4.0f,
                    125 to 3.0f,
                    250 to 1.5f,
                    500 to -0.5f,
                    1000 to -1.5f,
                    2000 to 0.5f,
                    4000 to 2.5f,
                    8000 to 4.0f,
                    16000 to 4.5f,
                ),
        ),
        EqualizerPreset(
            name = "Pop",
            targetCurve =
                listOf(
                    32 to -1.0f,
                    63 to 1.0f,
                    125 to 2.5f,
                    250 to 3.5f,
                    500 to 2.0f,
                    1000 to 0.0f,
                    2000 to -1.0f,
                    4000 to 1.5f,
                    8000 to 3.0f,
                    16000 to 2.5f,
                ),
        ),
        EqualizerPreset(
            name = "Electrónica",
            targetCurve =
                listOf(
                    32 to 5.5f,
                    63 to 5.0f,
                    125 to 3.5f,
                    250 to 1.0f,
                    500 to -1.0f,
                    1000 to 0.5f,
                    2000 to 1.5f,
                    4000 to 3.0f,
                    8000 to 4.5f,
                    16000 to 5.0f,
                ),
        ),
        EqualizerPreset(
            name = "Jazz",
            targetCurve =
                listOf(
                    32 to 2.0f,
                    63 to 3.0f,
                    125 to 2.0f,
                    250 to 1.5f,
                    500 to -1.0f,
                    1000 to -1.0f,
                    2000 to 0.0f,
                    4000 to 1.5f,
                    8000 to 2.5f,
                    16000 to 3.0f,
                ),
        ),
        EqualizerPreset(
            name = "Clásica",
            targetCurve =
                listOf(
                    32 to 3.5f,
                    63 to 3.0f,
                    125 to 2.0f,
                    250 to 0.0f,
                    500 to 0.0f,
                    1000 to 0.0f,
                    2000 to 1.0f,
                    4000 to 2.0f,
                    8000 to 3.0f,
                    16000 to 3.5f,
                ),
        ),
        EqualizerPreset(
            name = "Vocal",
            targetCurve =
                listOf(
                    32 to -3.0f,
                    63 to -2.0f,
                    125 to 0.0f,
                    250 to 2.0f,
                    500 to 4.0f,
                    1000 to 4.0f,
                    2000 to 3.0f,
                    4000 to 1.0f,
                    8000 to -1.0f,
                    16000 to -2.0f,
                ),
        ),
        EqualizerPreset(
            name = "Acústica",
            targetCurve =
                listOf(
                    32 to 2.5f,
                    63 to 2.5f,
                    125 to 1.5f,
                    250 to 1.0f,
                    500 to 1.0f,
                    1000 to 1.5f,
                    2000 to 2.0f,
                    4000 to 3.0f,
                    8000 to 2.5f,
                    16000 to 2.0f,
                ),
        ),
        EqualizerPreset(
            name = "Metal",
            targetCurve =
                listOf(
                    32 to 5.0f,
                    63 to 4.5f,
                    125 to 2.0f,
                    250 to 0.0f,
                    500 to -2.5f,
                    1000 to -3.0f,
                    2000 to -0.5f,
                    4000 to 3.0f,
                    8000 to 5.0f,
                    16000 to 5.5f,
                ),
        ),
    )

fun centerFrequenciesForBandCount(bandCount: Int): List<Int> =
    when (bandCount.coerceIn(MIN_EQUALIZER_BANDS, MAX_EQUALIZER_BANDS)) {
        5 -> listOf(60, 250, 1000, 4000, 16000)
        6 -> listOf(60, 150, 400, 1000, 3000, 14000)
        7 -> listOf(60, 150, 400, 1000, 2500, 6000, 15000)
        8 -> listOf(50, 100, 250, 500, 1000, 2000, 4000, 16000)
        9 -> listOf(50, 100, 200, 400, 800, 1600, 3200, 6400, 16000)
        10 -> listOf(31, 63, 125, 250, 500, 1000, 2000, 4000, 8000, 16000)
        11 -> listOf(31, 63, 125, 250, 500, 1000, 2000, 4000, 8000, 12000, 16000)
        12 -> listOf(32, 63, 125, 250, 500, 1000, 2000, 4000, 8000, 11000, 13000, 16000)
        else -> listOf(60, 250, 1000, 4000, 16000)
    }

fun formatEqualizerFrequency(freqHz: Int): String =
    if (freqHz >= 1000) {
        if (freqHz % 1000 == 0) {
            "${freqHz / 1000} kHz"
        } else {
            String.format(Locale.US, "%.1f kHz", freqHz / 1000f)
        }
    } else {
        "$freqHz Hz"
    }

fun evaluatePresetGains(
    preset: EqualizerPreset,
    frequencies: List<Int>,
): List<Float> {
    if (preset.targetCurve.isEmpty()) return frequencies.map { 0f }
    val points = preset.targetCurve.sortedBy { it.first }
    return frequencies.map { freq ->
        val logF = log10(freq.toDouble())
        val firstPoint = points.first()
        val lastPoint = points.last()
        val logFirst = log10(firstPoint.first.toDouble())
        val logLast = log10(lastPoint.first.toDouble())

        val gain =
            when {
                logF <= logFirst -> {
                    firstPoint.second
                }

                logF >= logLast -> {
                    lastPoint.second
                }

                else -> {
                    var foundGain = 0f
                    for (i in 0 until points.size - 1) {
                        val p1 = points[i]
                        val p2 = points[i + 1]
                        val lp1 = log10(p1.first.toDouble())
                        val lp2 = log10(p2.first.toDouble())
                        if (logF in lp1..lp2) {
                            val t = (logF - lp1) / (lp2 - lp1)
                            foundGain = (p1.second + t * (p2.second - p1.second)).toFloat()
                            break
                        }
                    }
                    foundGain
                }
            }
        (round(gain * 2f) / 2f).coerceIn(MIN_EQUALIZER_GAIN_DB, MAX_EQUALIZER_GAIN_DB)
    }
}

fun adaptGainsForNewBandCount(
    currentGains: List<Float>,
    currentCount: Int,
    newCount: Int,
    presetName: String,
): List<Float> {
    val newFrequencies = centerFrequenciesForBandCount(newCount)
    if (presetName == EQUALIZER_PRESET_FLAT) {
        return List(newCount) { 0f }
    }
    val preset = EQUALIZER_PRESETS.firstOrNull { it.name == presetName }
    if (preset != null && preset.targetCurve.isNotEmpty()) {
        return evaluatePresetGains(preset, newFrequencies)
    }

    val oldFrequencies = centerFrequenciesForBandCount(currentCount)
    if (oldFrequencies.isEmpty() || currentGains.isEmpty()) return List(newCount) { 0f }

    val points = oldFrequencies.zip(currentGains).sortedBy { it.first }
    val first = points.first()
    val last = points.last()
    val logFirst = log10(first.first.toDouble())
    val logLast = log10(last.first.toDouble())

    return newFrequencies.map { freq ->
        val logF = log10(freq.toDouble())
        val gain =
            when {
                logF <= logFirst -> {
                    first.second
                }

                logF >= logLast -> {
                    last.second
                }

                else -> {
                    var interp = 0f
                    for (i in 0 until points.size - 1) {
                        val p1 = points[i]
                        val p2 = points[i + 1]
                        val lp1 = log10(p1.first.toDouble())
                        val lp2 = log10(p2.first.toDouble())
                        if (logF in lp1..lp2) {
                            val t = (logF - lp1) / (lp2 - lp1)
                            interp = (p1.second + t * (p2.second - p1.second)).toFloat()
                            break
                        }
                    }
                    interp
                }
            }
        (round(gain * 2f) / 2f).coerceIn(MIN_EQUALIZER_GAIN_DB, MAX_EQUALIZER_GAIN_DB)
    }
}

fun encodeBandGains(gains: List<Float>): String = gains.joinToString(",") { String.format(Locale.US, "%.2f", it) }

fun decodeBandGains(
    raw: String?,
    bandCount: Int,
): List<Float> {
    val targetCount = bandCount.coerceIn(MIN_EQUALIZER_BANDS, MAX_EQUALIZER_BANDS)
    if (raw.isNullOrBlank()) {
        return List(targetCount) { 0f }
    }
    val parsed = raw.split(",").mapNotNull { it.trim().toFloatOrNull() }
    if (parsed.isEmpty()) return List(targetCount) { 0f }
    if (parsed.size == targetCount) {
        return parsed.map { it.coerceIn(MIN_EQUALIZER_GAIN_DB, MAX_EQUALIZER_GAIN_DB) }
    }
    return if (parsed.size < targetCount) {
        parsed.map { it.coerceIn(MIN_EQUALIZER_GAIN_DB, MAX_EQUALIZER_GAIN_DB) } +
            List(targetCount - parsed.size) { 0f }
    } else {
        parsed.take(targetCount).map { it.coerceIn(MIN_EQUALIZER_GAIN_DB, MAX_EQUALIZER_GAIN_DB) }
    }
}

fun DynamicEqualizerRule.matchesTrack(track: TrackMeta?): Boolean {
    if (track == null) return false
    val artist = track.artist.orEmpty().trim()
    val title = track.title.orEmpty().trim()
    val album = track.album.orEmpty().trim()

    val normArtist = TrackMatchKeys.normalize(artist)
    val normTitle = TrackMatchKeys.normalize(title)
    val normAlbum = TrackMatchKeys.normalize(album)

    return when (targetType) {
        EqualizerTargetType.SONG -> {
            if (normTitle.isEmpty()) return false
            val compositeKey = if (normArtist.isNotEmpty()) TrackMatchKeys.matchKey(normArtist, normTitle) else normTitle
            targetKey == compositeKey || (targetKey == normTitle && normArtist.isEmpty())
        }

        EqualizerTargetType.ALBUM -> {
            if (normAlbum.isEmpty()) return false
            val compositeKey = if (normArtist.isNotEmpty()) TrackMatchKeys.matchKey(normArtist, normAlbum) else normAlbum
            targetKey == compositeKey || (targetKey == normAlbum && normArtist.isEmpty())
        }

        EqualizerTargetType.ARTIST -> {
            normArtist.isNotEmpty() && targetKey == normArtist
        }

        EqualizerTargetType.CUSTOM_PRESET -> {
            false
        }
    }
}

fun resolveActiveRule(
    track: TrackMeta?,
    rules: List<DynamicEqualizerRule>,
): DynamicEqualizerRule? {
    if (track == null || rules.isEmpty()) return null
    // Priority 1: Song
    val songRule = rules.firstOrNull { it.targetType == EqualizerTargetType.SONG && it.matchesTrack(track) }
    if (songRule != null) return songRule

    // Priority 2: Album
    val albumRule = rules.firstOrNull { it.targetType == EqualizerTargetType.ALBUM && it.matchesTrack(track) }
    if (albumRule != null) return albumRule

    // Priority 3: Artist
    val artistRule = rules.firstOrNull { it.targetType == EqualizerTargetType.ARTIST && it.matchesTrack(track) }
    if (artistRule != null) return artistRule

    return null
}

fun createRuleForTarget(
    targetType: EqualizerTargetType,
    track: TrackMeta?,
    settings: EqualizerSettings,
    customName: String? = null,
): DynamicEqualizerRule? {
    val artist = track?.artist.orEmpty().trim()
    val title = track?.title.orEmpty().trim()
    val album = track?.album.orEmpty().trim()

    val normArtist = TrackMatchKeys.normalize(artist)
    val normTitle = TrackMatchKeys.normalize(title)
    val normAlbum = TrackMatchKeys.normalize(album)

    val (key, name, targetArtist) =
        when (targetType) {
            EqualizerTargetType.SONG -> {
                if (title.isBlank()) return null
                val key = if (normArtist.isNotEmpty()) TrackMatchKeys.matchKey(normArtist, normTitle) else normTitle
                Triple(key, title, artist)
            }

            EqualizerTargetType.ALBUM -> {
                if (album.isBlank()) return null
                val key = if (normArtist.isNotEmpty()) TrackMatchKeys.matchKey(normArtist, normAlbum) else normAlbum
                Triple(key, album, artist)
            }

            EqualizerTargetType.ARTIST -> {
                if (artist.isBlank() || normArtist.isEmpty()) return null
                Triple(normArtist, artist, "")
            }

            EqualizerTargetType.CUSTOM_PRESET -> {
                val trimmedName = customName?.trim()
                if (trimmedName.isNullOrEmpty()) return null
                Triple(TrackMatchKeys.normalize(trimmedName), trimmedName, "")
            }
        }

    return DynamicEqualizerRule(
        targetType = targetType,
        targetKey = key,
        targetName = name,
        targetArtist = targetArtist,
        bandCount = settings.bandCount,
        bandGainsDb = settings.bandGainsDb,
        presetName = settings.presetName,
    )
}

fun encodeDynamicRules(rules: List<DynamicEqualizerRule>): String {
    val array = JSONArray()
    for (rule in rules) {
        val obj = JSONObject()
        obj.put("id", rule.id)
        obj.put("type", rule.targetType.name)
        obj.put("key", rule.targetKey)
        obj.put("name", rule.targetName)
        obj.put("artist", rule.targetArtist)
        obj.put("bandCount", rule.bandCount)
        obj.put("gains", encodeBandGains(rule.bandGainsDb))
        obj.put("preset", rule.presetName)
        obj.put("createdAt", rule.createdAt)
        array.put(obj)
    }
    return array.toString()
}

fun decodeDynamicRules(raw: String?): List<DynamicEqualizerRule> {
    if (raw.isNullOrBlank()) return emptyList()
    return runCatching {
        val array = JSONArray(raw)
        val list = ArrayList<DynamicEqualizerRule>(array.length())
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            val typeStr = obj.optString("type")
            val type = runCatching { EqualizerTargetType.valueOf(typeStr) }.getOrDefault(EqualizerTargetType.SONG)
            val count =
                obj
                    .optInt("bandCount", DEFAULT_EQUALIZER_BAND_COUNT)
                    .coerceIn(MIN_EQUALIZER_BANDS, MAX_EQUALIZER_BANDS)
            val gains = decodeBandGains(obj.optString("gains"), count)
            list.add(
                DynamicEqualizerRule(
                    id = obj.optString("id", UUID.randomUUID().toString()),
                    targetType = type,
                    targetKey = obj.optString("key"),
                    targetName = obj.optString("name"),
                    targetArtist = obj.optString("artist", ""),
                    bandCount = count,
                    bandGainsDb = gains,
                    presetName = obj.optString("preset", EQUALIZER_PRESET_CUSTOM),
                    createdAt = obj.optLong("createdAt", System.currentTimeMillis()),
                ),
            )
        }
        list
    }.getOrDefault(emptyList())
}
