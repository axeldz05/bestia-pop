package com.bestiapop.android.testutil

import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.PI
import kotlin.math.sin

internal object PcmWavFixture {
    const val HEADER_SIZE_BYTES = 44

    fun generate(durationMs: Int, toneHz: Double? = null): ByteArray {
        require(durationMs > 0) { "durationMs must be positive" }
        require(toneHz == null || toneHz > 0.0) { "toneHz must be positive when provided" }

        val sampleCount = SAMPLE_RATE_HZ * durationMs / MILLIS_PER_SECOND
        val dataSize = sampleCount * CHANNEL_COUNT * BYTES_PER_SAMPLE
        return ByteArrayOutputStream(HEADER_SIZE_BYTES + dataSize).use { output ->
            output.write("RIFF".toByteArray(Charsets.US_ASCII))
            output.writeLittleEndianInt(36 + dataSize)
            output.write("WAVEfmt ".toByteArray(Charsets.US_ASCII))
            output.writeLittleEndianInt(16)
            output.writeLittleEndianShort(PCM_FORMAT)
            output.writeLittleEndianShort(CHANNEL_COUNT)
            output.writeLittleEndianInt(SAMPLE_RATE_HZ)
            output.writeLittleEndianInt(SAMPLE_RATE_HZ * CHANNEL_COUNT * BYTES_PER_SAMPLE)
            output.writeLittleEndianShort(CHANNEL_COUNT * BYTES_PER_SAMPLE)
            output.writeLittleEndianShort(BITS_PER_SAMPLE)
            output.write("data".toByteArray(Charsets.US_ASCII))
            output.writeLittleEndianInt(dataSize)

            if (toneHz == null) {
                output.write(ByteArray(dataSize))
            } else {
                repeat(sampleCount) { sampleIndex ->
                    val phase = 2.0 * PI * toneHz * sampleIndex / SAMPLE_RATE_HZ
                    val sample = (sin(phase) * Short.MAX_VALUE * TONE_AMPLITUDE).toInt()
                    output.writeLittleEndianShort(sample)
                }
            }
            output.toByteArray()
        }
    }

    fun write(file: File, durationMs: Int, toneHz: Double? = null) {
        file.writeBytes(generate(durationMs, toneHz))
    }

    private fun ByteArrayOutputStream.writeLittleEndianInt(value: Int) {
        repeat(Int.SIZE_BYTES) { byteIndex ->
            write(value ushr (byteIndex * Byte.SIZE_BITS) and 0xff)
        }
    }

    private fun ByteArrayOutputStream.writeLittleEndianShort(value: Int) {
        repeat(Short.SIZE_BYTES) { byteIndex ->
            write(value ushr (byteIndex * Byte.SIZE_BITS) and 0xff)
        }
    }

    private const val PCM_FORMAT = 1
    private const val CHANNEL_COUNT = 1
    private const val SAMPLE_RATE_HZ = 16_000
    private const val BITS_PER_SAMPLE = 16
    private const val BYTES_PER_SAMPLE = BITS_PER_SAMPLE / Byte.SIZE_BITS
    private const val MILLIS_PER_SECOND = 1_000
    private const val TONE_AMPLITUDE = 0.2
}
