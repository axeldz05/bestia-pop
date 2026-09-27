package com.bestiapop.android.service

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer

/**
 * Scales left/right PCM channels independently and provides warm soft-knee saturation
 * boost for loudness amplification. Gains are read per buffer so settings changes
 * apply immediately without flushing the sink.
 */
@UnstableApi
class StereoBalanceAudioProcessor : BaseAudioProcessor() {
    companion object {
        /**
         * Linear threshold for 16-bit PCM boost (~65% of full scale).
         * Amplitudes below this threshold are scaled 100% linearly for pure clarity.
         */
        private const val SOFT_SATURATION_THRESHOLD = 21_400f

        /**
         * Headroom between threshold and maximum 16-bit short value (32767 - 21400 = 11367).
         */
        private const val SOFT_SATURATION_CAPACITY = 32_767f - SOFT_SATURATION_THRESHOLD
    }

    @Volatile
    var leftGain: Float = 1f

    @Volatile
    var rightGain: Float = 1f

    @Volatile
    var boostGain: Float = 1f

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            return AudioProcessor.AudioFormat.NOT_SET
        }
        return inputAudioFormat
    }

    override fun isActive(): Boolean = super.isActive()

    override fun queueInput(inputBuffer: ByteBuffer) {
        val position = inputBuffer.position()
        val limit = inputBuffer.limit()
        val size = limit - position
        if (size == 0) return

        val effLeft = leftGain * boostGain
        val effRight = rightGain * boostGain

        if (effLeft >= 0.999f && effLeft <= 1.001f && effRight >= 0.999f && effRight <= 1.001f) {
            val output = replaceOutputBuffer(size)
            output.put(inputBuffer)
            output.flip()
            return
        }

        val output = replaceOutputBuffer(size)
        val channels = inputAudioFormat.channelCount

        when (channels) {
            1 -> {
                val monoGain = (effLeft + effRight) * 0.5f
                while (inputBuffer.hasRemaining()) {
                    val sample = inputBuffer.short
                    output.putShort(scaleSample(sample, monoGain))
                }
            }

            2 -> {
                while (inputBuffer.hasRemaining()) {
                    val sampleL = inputBuffer.short
                    val sampleR = inputBuffer.short
                    output.putShort(scaleSample(sampleL, effLeft))
                    output.putShort(scaleSample(sampleR, effRight))
                }
            }

            else -> {
                // Interleaved multi-channel (> 2 channels): process stereo pair L and R, pass through remaining channels.
                var i = position
                var channel = 0
                while (i < limit) {
                    val sample = inputBuffer.getShort(i)
                    val gain =
                        when (channel % channels) {
                            0 -> effLeft
                            1 -> effRight
                            else -> 1f
                        }
                    output.putShort(scaleSample(sample, gain))
                    i += 2
                    channel++
                }
                inputBuffer.position(limit)
            }
        }

        output.flip()
    }

    internal fun scaleSample(
        sample: Short,
        gain: Float,
    ): Short {
        if (gain >= 0.999f && gain <= 1.001f) return sample
        if (gain <= 0.001f) return 0
        if (gain < 1.0f) {
            return (sample * gain).toInt().toShort()
        }

        // Soft-knee saturation for boost (gain > 1.0f).
        // Amplifies low/medium signals linearly and softly compresses peaks to avoid hard clipping.
        val multiplied = sample * gain
        val absVal = if (multiplied < 0) -multiplied else multiplied

        if (absVal <= SOFT_SATURATION_THRESHOLD) {
            return multiplied.toInt().toShort()
        }

        val excess = absVal - SOFT_SATURATION_THRESHOLD
        val compressed =
            SOFT_SATURATION_THRESHOLD +
                (SOFT_SATURATION_CAPACITY * excess) / (SOFT_SATURATION_CAPACITY + excess)
        val result = if (multiplied < 0) -compressed else compressed
        return result.toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
    }
}
