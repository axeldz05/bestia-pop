package com.bestiapop.android.service

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer

/**
 * Scales left/right PCM channels independently. Gains are read per buffer so
 * settings changes apply immediately without flushing the sink.
 */
@UnstableApi
class StereoBalanceAudioProcessor : BaseAudioProcessor() {

    @Volatile
    var leftGain: Float = 1f

    @Volatile
    var rightGain: Float = 1f

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            return AudioProcessor.AudioFormat.NOT_SET
        }
        return inputAudioFormat
    }

    override fun isActive(): Boolean {
        return super.isActive() && (leftGain < 0.999f || rightGain < 0.999f)
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val position = inputBuffer.position()
        val limit = inputBuffer.limit()
        val size = limit - position
        if (size == 0) return

        val left = leftGain
        val right = rightGain

        if (left >= 0.999f && right >= 0.999f) {
            val output = replaceOutputBuffer(size)
            output.put(inputBuffer)
            output.flip()
            return
        }

        val output = replaceOutputBuffer(size)
        val channels = inputAudioFormat.channelCount

        when (channels) {
            1 -> {
                val monoGain = (left + right) * 0.5f
                var i = position
                while (i < limit) {
                    val sample = inputBuffer.getShort(i)
                    output.putShort(scaleSample(sample, monoGain))
                    i += 2
                }
            }
            else -> {
                // Interleaved L/R (and ignore extra channels beyond stereo pair).
                var i = position
                var channel = 0
                while (i < limit) {
                    val sample = inputBuffer.getShort(i)
                    val gain = when (channel % channels) {
                        0 -> left
                        1 -> right
                        else -> 1f
                    }
                    output.putShort(scaleSample(sample, gain))
                    i += 2
                    channel++
                }
            }
        }

        inputBuffer.position(limit)
        output.flip()
    }

    private fun scaleSample(sample: Short, gain: Float): Short {
        if (gain >= 0.999f) return sample
        if (gain <= 0.001f) return 0
        val scaled = (sample * gain).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
        return scaled.toShort()
    }
}
