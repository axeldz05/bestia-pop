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
        private const val DENORMAL_THRESHOLD = 1e-15f
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

    private class CompiledBiquadBank(
        val bandCount: Int,
        val b0: FloatArray,
        val b1: FloatArray,
        val b2: FloatArray,
        val a1: FloatArray,
        val a2: FloatArray,
        val isPassThrough: BooleanArray,
        val hasActiveFilters: Boolean,
    )

    @Volatile
    var isEnabled: Boolean = false
        set(value) {
            val changed = field != value
            field = value
            if (changed && !value) {
                clearFilterStates()
            }
        }

    @Volatile
    private var compiledBank: CompiledBiquadBank? = null

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
            compiledBank = null
            clearFilterStates()
            return
        }

        // Clean any states of discontinued bands
        if (count < MAX_SUPPORTED_BANDS) {
            stateL1.fill(0f, count, MAX_SUPPORTED_BANDS)
            stateL2.fill(0f, count, MAX_SUPPORTED_BANDS)
            stateR1.fill(0f, count, MAX_SUPPORTED_BANDS)
            stateR2.fill(0f, count, MAX_SUPPORTED_BANDS)
        }

        val q = 1.0f + (count - 5).coerceAtLeast(0) * 0.12f
        var nonPassThroughFound = false
        val b0 = FloatArray(count)
        val b1 = FloatArray(count)
        val b2 = FloatArray(count)
        val a1 = FloatArray(count)
        val a2 = FloatArray(count)
        val isPassThrough = BooleanArray(count)

        for (i in 0 until count) {
            val freq = frequencies[i].toFloat()
            val gain = gainsDb[i]
            val coeff = calculatePeakingBiquad(gain, freq, currentSampleRate.toFloat(), q)
            b0[i] = coeff.b0
            b1[i] = coeff.b1
            b2[i] = coeff.b2
            a1[i] = coeff.a1
            a2[i] = coeff.a2
            isPassThrough[i] = coeff.isPassThrough
            if (!coeff.isPassThrough) {
                nonPassThroughFound = true
            }
        }

        compiledBank =
            CompiledBiquadBank(
                bandCount = count,
                b0 = b0,
                b1 = b1,
                b2 = b2,
                a1 = a1,
                a2 = a2,
                isPassThrough = isPassThrough,
                hasActiveFilters = nonPassThroughFound,
            )
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

        val bank = compiledBank
        if (!isEnabled || bank == null || !bank.hasActiveFilters || bank.bandCount == 0) {
            val output = replaceOutputBuffer(size)
            output.put(inputBuffer)
            output.flip()
            return
        }

        val output = replaceOutputBuffer(size)
        val channels = inputAudioFormat.channelCount
        val bandCount = bank.bandCount
        val b0 = bank.b0
        val b1 = bank.b1
        val b2 = bank.b2
        val a1 = bank.a1
        val a2 = bank.a2
        val pass = bank.isPassThrough

        when (channels) {
            1 -> {
                while (inputBuffer.hasRemaining()) {
                    var sample = inputBuffer.short.toFloat()
                    for (b in 0 until bandCount) {
                        if (pass[b]) continue
                        val out = b0[b] * sample + stateL1[b]
                        var next1 = b1[b] * sample - a1[b] * out + stateL2[b]
                        var next2 = b2[b] * sample - a2[b] * out
                        if (kotlin.math.abs(next1) < DENORMAL_THRESHOLD) next1 = 0f
                        if (kotlin.math.abs(next2) < DENORMAL_THRESHOLD) next2 = 0f
                        stateL1[b] = next1
                        stateL2[b] = next2
                        sample = out
                    }
                    output.putShort(softSaturate(sample))
                }
            }

            2 -> {
                while (inputBuffer.hasRemaining()) {
                    var sampleL = inputBuffer.short.toFloat()
                    var sampleR = inputBuffer.short.toFloat()

                    for (b in 0 until bandCount) {
                        if (pass[b]) continue

                        val coeffB0 = b0[b]
                        val coeffB1 = b1[b]
                        val coeffB2 = b2[b]
                        val coeffA1 = a1[b]
                        val coeffA2 = a2[b]

                        val outL = coeffB0 * sampleL + stateL1[b]
                        var nextL1 = coeffB1 * sampleL - coeffA1 * outL + stateL2[b]
                        var nextL2 = coeffB2 * sampleL - coeffA2 * outL
                        if (kotlin.math.abs(nextL1) < DENORMAL_THRESHOLD) nextL1 = 0f
                        if (kotlin.math.abs(nextL2) < DENORMAL_THRESHOLD) nextL2 = 0f
                        stateL1[b] = nextL1
                        stateL2[b] = nextL2
                        sampleL = outL

                        val outR = coeffB0 * sampleR + stateR1[b]
                        var nextR1 = coeffB1 * sampleR - coeffA1 * outR + stateR2[b]
                        var nextR2 = coeffB2 * sampleR - coeffA2 * outR
                        if (kotlin.math.abs(nextR1) < DENORMAL_THRESHOLD) nextR1 = 0f
                        if (kotlin.math.abs(nextR2) < DENORMAL_THRESHOLD) nextR2 = 0f
                        stateR1[b] = nextR1
                        stateR2[b] = nextR2
                        sampleR = outR
                    }

                    output.putShort(softSaturate(sampleL))
                    output.putShort(softSaturate(sampleR))
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
                                    if (pass[b]) continue
                                    val out = b0[b] * sample + stateL1[b]
                                    var next1 = b1[b] * sample - a1[b] * out + stateL2[b]
                                    var next2 = b2[b] * sample - a2[b] * out
                                    if (kotlin.math.abs(next1) < DENORMAL_THRESHOLD) next1 = 0f
                                    if (kotlin.math.abs(next2) < DENORMAL_THRESHOLD) next2 = 0f
                                    stateL1[b] = next1
                                    stateL2[b] = next2
                                    sample = out
                                }
                                softSaturate(sample)
                            }

                            1 -> {
                                var sample = rawSample
                                for (b in 0 until bandCount) {
                                    if (pass[b]) continue
                                    val out = b0[b] * sample + stateR1[b]
                                    var next1 = b1[b] * sample - a1[b] * out + stateR2[b]
                                    var next2 = b2[b] * sample - a2[b] * out
                                    if (kotlin.math.abs(next1) < DENORMAL_THRESHOLD) next1 = 0f
                                    if (kotlin.math.abs(next2) < DENORMAL_THRESHOLD) next2 = 0f
                                    stateR1[b] = next1
                                    stateR2[b] = next2
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
            return value.toInt().toShort()
        }
        val excess = absVal - SOFT_SATURATION_THRESHOLD
        val compressed =
            SOFT_SATURATION_THRESHOLD +
                (SOFT_SATURATION_CAPACITY * excess) / (SOFT_SATURATION_CAPACITY + excess)
        val result = if (value < 0f) -compressed else compressed
        return result.toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
    }
}
