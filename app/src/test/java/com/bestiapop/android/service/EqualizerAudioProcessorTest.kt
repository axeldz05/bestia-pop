package com.bestiapop.android.service

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

@UnstableApi
class EqualizerAudioProcessorTest {
    @Test
    fun disabledEqualizer_passesAudioThroughDirectly() {
        val processor = configuredProcessor(channels = 2, enabled = false)
        processor.updateBands(listOf(60, 250, 1000, 4000, 16000), listOf(6f, 6f, 6f, 6f, 6f))

        val inputSamples = listOf<Short>(1000, 2000, -3000, 4000)
        val output = queue(processor, inputSamples)

        assertEquals(inputSamples, output)
    }

    @Test
    fun allZeroGain_passesAudioThroughDirectly() {
        val processor = configuredProcessor(channels = 2, enabled = true)
        processor.updateBands(listOf(60, 250, 1000, 4000, 16000), listOf(0f, 0f, 0f, 0f, 0f))

        val inputSamples = listOf<Short>(1500, -1500, 800, -800)
        val output = queue(processor, inputSamples)

        assertEquals(inputSamples, output)
    }

    @Test
    fun enabledWithBoost_modifiesOutputAudio() {
        val processor = configuredProcessor(channels = 2, enabled = true)
        processor.updateBands(
            listOf(60, 250, 1000, 4000, 16000),
            listOf(6f, 6f, 6f, 6f, 6f),
        )

        // Generate a 1 kHz sine-like pulse to see filtering effect
        val inputSamples =
            List(64) { i ->
                (10_000.0 * kotlin.math.sin(2.0 * Math.PI * 1000.0 * i / 48000.0)).toInt().toShort()
            }
        val output = queue(processor, inputSamples)

        assertEquals(inputSamples.size, output.size)
        // With 6 dB boost, output values should differ from input
        assertNotEquals(inputSamples, output)
    }

    @Test
    fun supports12Bands() {
        val processor = configuredProcessor(channels = 2, enabled = true)
        val freqs12 = listOf(32, 63, 125, 250, 500, 1000, 2000, 4000, 8000, 11000, 13000, 16000)
        val gains12 = listOf(3f, 3f, 2f, 1f, 0f, -1f, -2f, 0f, 2f, 3f, 4f, 4f)

        processor.updateBands(freqs12, gains12)

        val inputSamples = List(48) { (it * 500).toShort() }
        val output = queue(processor, inputSamples)

        assertEquals(inputSamples.size, output.size)
    }

    @Test
    fun monoInput_processedCorrectly() {
        val processor = configuredProcessor(channels = 1, enabled = true)
        processor.updateBands(listOf(60, 250, 1000, 4000, 16000), listOf(4f, 0f, -4f, 0f, 4f))

        val inputSamples = List(32) { (it * 300).toShort() }
        val output = queue(processor, inputSamples)

        assertEquals(inputSamples.size, output.size)
    }

    @Test
    fun softSaturation_preventsHardClippingAndWrapping() {
        val processor = EqualizerAudioProcessor()

        // Test softSaturate function directly
        val linear = processor.softSaturate(15_000f)
        assertEquals(15_000.toShort(), linear)

        val boosted = processor.softSaturate(30_000f)
        assertTrue("Boosted sample should be <= 32767", boosted <= 32767)
        assertTrue("Boosted sample should be > 21400", boosted > 21400)

        val extreme = processor.softSaturate(100_000f)
        assertTrue("Extreme sample should not wrap around or exceed max Short", extreme <= 32767 && extreme > 0)

        val negativeExtreme = processor.softSaturate(-100_000f)
        assertTrue("Negative extreme should be negative and >= Short.MIN_VALUE", negativeExtreme >= -32768 && negativeExtreme < 0)
    }

    private fun configuredProcessor(
        channels: Int,
        enabled: Boolean,
    ) = EqualizerAudioProcessor().apply {
        configure(AudioProcessor.AudioFormat(48_000, channels, C.ENCODING_PCM_16BIT))
        this.isEnabled = enabled
        flush(AudioProcessor.StreamMetadata.DEFAULT)
    }

    private fun queue(
        processor: EqualizerAudioProcessor,
        samples: List<Short>,
    ): List<Short> {
        val input =
            ByteBuffer
                .allocateDirect(samples.size * Short.SIZE_BYTES)
                .order(ByteOrder.nativeOrder())
        samples.forEach { input.putShort(it) }
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
