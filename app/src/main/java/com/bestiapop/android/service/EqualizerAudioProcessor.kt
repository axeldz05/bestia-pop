package com.bestiapop.android.service

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/**
 * Multi-band parametric/peaking IIR equalizer audio processor for Media3 ExoPlayer.
 * Supports arbitrary band counts from 5 up to 12 bands using Direct Form II Transposed biquad filters.
 * Pure software implementation ensures identical audio behavior across all Android versions and OEMs.
 */
@UnstableApi
class EqualizerAudioProcessor : BaseAudioProcessor() {
    companion object {
        private const val SOFT_SATURATION_THRESHOLD = 21_400f
        private const val SOFT_SATURATION_CAPACITY = 32_767f - SOFT_SATURATION_THRESHOLD
        const val MAX_SUPPORTED_BANDS = 12
    }

    class BiquadCoefficients(
        val b0: Float,
        val b1: Float,
        val b2: Float,
        val a1: Float,
        val a2: Float,
        val isPassThrough: Boolean,
    )

    @Volatile
    var isEnabled: Boolean = false

    @Volatile
    private var activeCoefficients: Array<BiquadCoefficients> = emptyArray()

    @Volatile
    private var hasActiveFilters: Boolean = false

    private var currentSampleRate: Int = 44_100

    private val stateL1 = FloatArray(MAX_SUPPORTED_BANDS)
    private val stateL2 = FloatArray(MAX_SUPPORTED_BANDS)
    private val stateR1 = FloatArray(MAX_SUPPORTED_BANDS)
    private val stateR2 = FloatArray(MAX_SUPPORTED_BANDS)

    // Stored band frequencies and gains to recompute when sample rate changes
    private var cachedFrequencies: List<Int> = emptyList()
    private var cachedGainsDb: List<Float> = emptyList()

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            return AudioProcessor.AudioFormat.NOT_SET
        }
        val newSampleRate = inputAudioFormat.sampleRate
        if (newSampleRate != currentSampleRate) {
            currentSampleRate = newSampleRate
            recomputeCoefficients(cachedFrequencies, cachedGainsDb)
        }
        return inputAudioFormat
    }

    override fun isActive(): Boolean = super.isActive()

    fun updateBands(
        frequencies: List<Int>,
        gainsDb: List<Float>,
    ) {
        cachedFrequencies = frequencies
        cachedGainsDb = gainsDb
        recomputeCoefficients(frequencies, gainsDb)
    }

    private fun recomputeCoefficients(
        frequencies: List<Int>,
        gainsDb: List<Float>,
    ) {
        val count = minOf(frequencies.size, gainsDb.size, MAX_SUPPORTED_BANDS)
        if (count == 0) {
            activeCoefficients = emptyArray()
            hasActiveFilters = false
            return
        }

        val q = 1.0f + (count - 5).coerceAtLeast(0) * 0.12f
        var nonPassThroughFound = false
        val newCoeffs =
            Array(count) { i ->
                val freq = frequencies[i].toFloat()
                val gain = gainsDb[i]
                val coeff = calculatePeakingBiquad(gain, freq, currentSampleRate.toFloat(), q)
                if (!coeff.isPassThrough) {
                    nonPassThroughFound = true
                }
                coeff
            }

        activeCoefficients = newCoeffs
        hasActiveFilters = nonPassThroughFound
    }

    private fun calculatePeakingBiquad(
        gainDb: Float,
        frequencyHz: Float,
        sampleRate: Float,
        q: Float,
    ): BiquadCoefficients {
        if (kotlin.math.abs(gainDb) < 0.05f) {
            return BiquadCoefficients(1f, 0f, 0f, 0f, 0f, isPassThrough = true)
        }

        val clampedFreq = frequencyHz.coerceIn(20f, sampleRate * 0.45f)
        val aVal = 10f.pow(gainDb / 40f)
        val w0 = (2.0 * Math.PI * clampedFreq / sampleRate).toFloat()
        val alpha = (sin(w0.toDouble()) / (2.0 * q)).toFloat()
        val cosW0 = cos(w0.toDouble()).toFloat()

        val b0 = 1f + alpha * aVal
        val b1 = -2f * cosW0
        val b2 = 1f - alpha * aVal
        val a0 = 1f + alpha / aVal
        val a1 = -2f * cosW0
        val a2 = 1f - alpha / aVal

        val invA0 = 1f / a0
        return BiquadCoefficients(
            b0 = b0 * invA0,
            b1 = b1 * invA0,
            b2 = b2 * invA0,
            a1 = a1 * invA0,
            a2 = a2 * invA0,
            isPassThrough = false,
        )
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val position = inputBuffer.position()
        val limit = inputBuffer.limit()
        val size = limit - position
        if (size == 0) return

        val coeffs = activeCoefficients
        if (!isEnabled || !hasActiveFilters || coeffs.isEmpty()) {
            val output = replaceOutputBuffer(size)
            output.put(inputBuffer)
            output.flip()
            return
        }

        val output = replaceOutputBuffer(size)
        val channels = inputAudioFormat.channelCount
        val bandCount = coeffs.size

        when (channels) {
            1 -> {
                var i = position
                while (i < limit) {
                    var sample = inputBuffer.getShort(i).toFloat()
                    for (b in 0 until bandCount) {
                        val coeff = coeffs[b]
                        if (coeff.isPassThrough) continue
                        val out = coeff.b0 * sample + stateL1[b]
                        stateL1[b] = coeff.b1 * sample - coeff.a1 * out + stateL2[b]
                        stateL2[b] = coeff.b2 * sample - coeff.a2 * out
                        sample = out
                    }
                    output.putShort(softSaturate(sample))
                    i += 2
                }
            }

            2 -> {
                var i = position
                while (i < limit) {
                    var sampleL = inputBuffer.getShort(i).toFloat()
                    var sampleR = inputBuffer.getShort(i + 2).toFloat()

                    for (b in 0 until bandCount) {
                        val coeff = coeffs[b]
                        if (coeff.isPassThrough) continue

                        val outL = coeff.b0 * sampleL + stateL1[b]
                        stateL1[b] = coeff.b1 * sampleL - coeff.a1 * outL + stateL2[b]
                        stateL2[b] = coeff.b2 * sampleL - coeff.a2 * outL
                        sampleL = outL

                        val outR = coeff.b0 * sampleR + stateR1[b]
                        stateR1[b] = coeff.b1 * sampleR - coeff.a1 * outR + stateR2[b]
                        stateR2[b] = coeff.b2 * sampleR - coeff.a2 * outR
                        sampleR = outR
                    }

                    output.putShort(softSaturate(sampleL))
                    output.putShort(softSaturate(sampleR))
                    i += 4
                }
            }

            else -> {
                // Multi-channel (> 2): process stereo pair L and R, pass through remaining channels
                var i = position
                var channel = 0
                while (i < limit) {
                    val rawSample = inputBuffer.getShort(i).toFloat()
                    val outSample =
                        when (channel % channels) {
                            0 -> {
                                var sample = rawSample
                                for (b in 0 until bandCount) {
                                    val coeff = coeffs[b]
                                    if (coeff.isPassThrough) continue
                                    val out = coeff.b0 * sample + stateL1[b]
                                    stateL1[b] = coeff.b1 * sample - coeff.a1 * out + stateL2[b]
                                    stateL2[b] = coeff.b2 * sample - coeff.a2 * out
                                    sample = out
                                }
                                softSaturate(sample)
                            }

                            1 -> {
                                var sample = rawSample
                                for (b in 0 until bandCount) {
                                    val coeff = coeffs[b]
                                    if (coeff.isPassThrough) continue
                                    val out = coeff.b0 * sample + stateR1[b]
                                    stateR1[b] = coeff.b1 * sample - coeff.a1 * out + stateR2[b]
                                    stateR2[b] = coeff.b2 * sample - coeff.a2 * out
                                    sample = out
                                }
                                softSaturate(sample)
                            }

                            else -> {
                                rawSample.toInt().toShort()
                            }
                        }
                    output.putShort(outSample)
                    i += 2
                    channel++
                }
            }
        }

        inputBuffer.position(limit)
        output.flip()
    }

    override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) {
        super.onFlush(streamMetadata)
        clearFilterStates()
    }

    override fun onReset() {
        clearFilterStates()
    }

    private fun clearFilterStates() {
        stateL1.fill(0f)
        stateL2.fill(0f)
        stateR1.fill(0f)
        stateR2.fill(0f)
    }

    internal fun softSaturate(value: Float): Short {
        val absVal = if (value < 0f) -value else value
        if (absVal <= SOFT_SATURATION_THRESHOLD) {
            return value.toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
        val excess = absVal - SOFT_SATURATION_THRESHOLD
        val compressed =
            SOFT_SATURATION_THRESHOLD +
                (SOFT_SATURATION_CAPACITY * excess) / (SOFT_SATURATION_CAPACITY + excess)
        val result = if (value < 0f) -compressed else compressed
        return result.toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
    }
}
