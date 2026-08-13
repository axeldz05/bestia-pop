package com.bestiapop.android.service

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Test

@UnstableApi
class StereoBalanceAudioProcessorTest {

    @Test
    fun stereoInput_appliesIndependentLeftAndRightGains() {
        val processor = configuredProcessor(channels = 2, leftGain = 0.5f, rightGain = 0.25f)

        val output = queue(processor, 10_000, 10_000, -8_000, -8_000)

        assertEquals(
            listOf(5_000, 2_500, -4_000, -2_000).map(Int::toShort),
            output
        )
    }

    @Test
    fun monoInput_usesAverageOfBothChannelGains() {
        val processor = configuredProcessor(channels = 1, leftGain = 0f, rightGain = 1f)

        assertEquals(
            listOf(10_000, -10_000).map(Int::toShort),
            queue(processor, 20_000, -20_000)
        )
    }

    @Test
    fun gainChanges_applyToNextBufferWithoutFlushingProcessor() {
        val processor = configuredProcessor(channels = 2, leftGain = 1f, rightGain = 1f)
        assertEquals(listOf(1_000, 1_000).map(Int::toShort), queue(processor, 1_000, 1_000))

        processor.leftGain = 0f
        processor.rightGain = 0.5f

        assertEquals(listOf(0, 500).map(Int::toShort), queue(processor, 1_000, 1_000))
    }

    private fun configuredProcessor(
        channels: Int,
        leftGain: Float,
        rightGain: Float
    ) = StereoBalanceAudioProcessor().apply {
        configure(AudioProcessor.AudioFormat(48_000, channels, C.ENCODING_PCM_16BIT))
        this.leftGain = leftGain
        this.rightGain = rightGain
        flush(AudioProcessor.StreamMetadata.DEFAULT)
    }

    private fun queue(
        processor: StereoBalanceAudioProcessor,
        vararg samples: Int
    ): List<Short> {
        val input = ByteBuffer.allocateDirect(samples.size * Short.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
        samples.forEach { input.putShort(it.toShort()) }
        input.flip()

        processor.queueInput(input)

        val output = processor.output
        return buildList {
            while (output.remaining() >= Short.SIZE_BYTES) {
                add(output.short)
            }
        }
    }
}
