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
        private const val SOFT_SATURATION_THRESHOLD = 32_000f
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
        val activeCount: Int,
        val b0: FloatArray,
        val b1: FloatArray,
        val b2: FloatArray,
        val a1: FloatArray,
        val a2: FloatArray,
        val stateIndices: IntArray,
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

    @Volatile
    private var currentSampleRate: Int = 44_100

    private val stateLock = Any()
    private val stateL1 = FloatArray(MAX_SUPPORTED_BANDS)
    private val stateL2 = FloatArray(MAX_SUPPORTED_BANDS)
    private val stateR1 = FloatArray(MAX_SUPPORTED_BANDS)
    private val stateR2 = FloatArray(MAX_SUPPORTED_BANDS)

    // Stored band frequencies and gains to recompute when sample rate changes
    @Volatile
    private var cachedFrequencies: List<Int> = emptyList()

    @Volatile
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

        val rate = currentSampleRate.toFloat()
        val q = 1.0f + (count - 5).coerceAtLeast(0) * 0.12f
        var activeCount = 0
        val tempB0 = FloatArray(count)
        val tempB1 = FloatArray(count)
        val tempB2 = FloatArray(count)
        val tempA1 = FloatArray(count)
        val tempA2 = FloatArray(count)
        val tempIndices = IntArray(count)

        synchronized(stateLock) {
            if (count < MAX_SUPPORTED_BANDS) {
                stateL1.fill(0f, count, MAX_SUPPORTED_BANDS)
                stateL2.fill(0f, count, MAX_SUPPORTED_BANDS)
                stateR1.fill(0f, count, MAX_SUPPORTED_BANDS)
                stateR2.fill(0f, count, MAX_SUPPORTED_BANDS)
            }

            for (i in 0 until count) {
                val freq = frequencies[i].toFloat()
                val gain = gainsDb[i]
                val coeff = calculatePeakingBiquad(gain, freq, rate, q)
                if (coeff.isPassThrough) {
                    stateL1[i] = 0f
                    stateL2[i] = 0f
                    stateR1[i] = 0f
                    stateR2[i] = 0f
                } else {
                    tempB0[activeCount] = coeff.b0
                    tempB1[activeCount] = coeff.b1
                    tempB2[activeCount] = coeff.b2
                    tempA1[activeCount] = coeff.a1
                    tempA2[activeCount] = coeff.a2
                    tempIndices[activeCount] = i
                    activeCount++
                }
            }
        }

        compiledBank =
            if (activeCount == 0) {
                null
            } else {
                CompiledBiquadBank(
                    activeCount = activeCount,
                    b0 = tempB0.copyOf(activeCount),
                    b1 = tempB1.copyOf(activeCount),
                    b2 = tempB2.copyOf(activeCount),
                    a1 = tempA1.copyOf(activeCount),
                    a2 = tempA2.copyOf(activeCount),
                    stateIndices = tempIndices.copyOf(activeCount),
                    hasActiveFilters = true,
                )
            }
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
        if (!isEnabled || bank == null || !bank.hasActiveFilters || bank.activeCount == 0) {
            val output = replaceOutputBuffer(size)
            output.put(inputBuffer)
            output.flip()
            return
        }

        val output = replaceOutputBuffer(size)
        val channels = inputAudioFormat.channelCount
        val activeCount = bank.activeCount
        val b0 = bank.b0
        val b1 = bank.b1
        val b2 = bank.b2
        val a1 = bank.a1
        val a2 = bank.a2
        val stateIndices = bank.stateIndices

        synchronized(stateLock) {
            when (channels) {
                1 -> {
                    while (inputBuffer.hasRemaining()) {
                        var sample = inputBuffer.short.toFloat()
                        for (b in 0 until activeCount) {
                            val idx = stateIndices[b]
                            val out = b0[b] * sample + stateL1[idx]
                            var next1 = b1[b] * sample - a1[b] * out + stateL2[idx]
                            var next2 = b2[b] * sample - a2[b] * out
                            if (next1 in -DENORMAL_THRESHOLD..DENORMAL_THRESHOLD) next1 = 0f
                            if (next2 in -DENORMAL_THRESHOLD..DENORMAL_THRESHOLD) next2 = 0f
                            stateL1[idx] = next1
                            stateL2[idx] = next2
                            sample = out
                        }
                        output.putShort(softSaturate(sample))
                    }
                }

                2 -> {
                    while (inputBuffer.hasRemaining()) {
                        var sampleL = inputBuffer.short.toFloat()
                        var sampleR = inputBuffer.short.toFloat()

                        for (b in 0 until activeCount) {
                            val idx = stateIndices[b]
                            val coeffB0 = b0[b]
                            val coeffB1 = b1[b]
                            val coeffB2 = b2[b]
                            val coeffA1 = a1[b]
                            val coeffA2 = a2[b]

                            val outL = coeffB0 * sampleL + stateL1[idx]
                            var nextL1 = coeffB1 * sampleL - coeffA1 * outL + stateL2[idx]
                            var nextL2 = coeffB2 * sampleL - coeffA2 * outL
                            if (nextL1 in -DENORMAL_THRESHOLD..DENORMAL_THRESHOLD) nextL1 = 0f
                            if (nextL2 in -DENORMAL_THRESHOLD..DENORMAL_THRESHOLD) nextL2 = 0f
                            stateL1[idx] = nextL1
                            stateL2[idx] = nextL2
                            sampleL = outL

                            val outR = coeffB0 * sampleR + stateR1[idx]
                            var nextR1 = coeffB1 * sampleR - coeffA1 * outR + stateR2[idx]
                            var nextR2 = coeffB2 * sampleR - coeffA2 * outR
                            if (nextR1 in -DENORMAL_THRESHOLD..DENORMAL_THRESHOLD) nextR1 = 0f
                            if (nextR2 in -DENORMAL_THRESHOLD..DENORMAL_THRESHOLD) nextR2 = 0f
                            stateR1[idx] = nextR1
                            stateR2[idx] = nextR2
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
                                    for (b in 0 until activeCount) {
                                        val idx = stateIndices[b]
                                        val out = b0[b] * sample + stateL1[idx]
                                        var next1 = b1[b] * sample - a1[b] * out + stateL2[idx]
                                        var next2 = b2[b] * sample - a2[b] * out
                                        if (next1 in -DENORMAL_THRESHOLD..DENORMAL_THRESHOLD) next1 = 0f
                                        if (next2 in -DENORMAL_THRESHOLD..DENORMAL_THRESHOLD) next2 = 0f
                                        stateL1[idx] = next1
                                        stateL2[idx] = next2
                                        sample = out
                                    }
                                    softSaturate(sample)
                                }

                                1 -> {
                                    var sample = rawSample
                                    for (b in 0 until activeCount) {
                                        val idx = stateIndices[b]
                                        val out = b0[b] * sample + stateR1[idx]
                                        var next1 = b1[b] * sample - a1[b] * out + stateR2[idx]
                                        var next2 = b2[b] * sample - a2[b] * out
                                        if (next1 in -DENORMAL_THRESHOLD..DENORMAL_THRESHOLD) next1 = 0f
                                        if (next2 in -DENORMAL_THRESHOLD..DENORMAL_THRESHOLD) next2 = 0f
                                        stateR1[idx] = next1
                                        stateR2[idx] = next2
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
        synchronized(stateLock) {
            stateL1.fill(0f)
            stateL2.fill(0f)
            stateR1.fill(0f)
            stateR2.fill(0f)
        }
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
