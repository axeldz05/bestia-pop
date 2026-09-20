package com.bestiapop.android.data.util

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer

/**
 * Extracts pure audio tracks from muxed media containers (e.g. MP4 video with AAC audio)
 * into standard audio containers (e.g. M4A) via direct bitstream copy without re-encoding.
 */
object MediaDemuxer {
    private const val DEFAULT_BUFFER_SIZE = 64 * 1024

    /**
     * Extracts the primary audio track from [sourceFile] and remuxes it into [destinationFile].
     *
     * @param sourceFile Input media container file (e.g. format 18 MP4).
     * @param destinationFile Output audio container file (e.g. M4A).
     * @return true if an audio track was successfully found and demuxed, false otherwise.
     */
    fun extractAudioTrack(
        sourceFile: File,
        destinationFile: File,
    ): Boolean {
        if (!sourceFile.exists() || sourceFile.length() == 0L) return false

        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        var muxerStarted = false

        return try {
            extractor.setDataSource(sourceFile.absolutePath)

            var audioTrackIndex = -1
            var audioFormat: MediaFormat? = null

            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME)
                if (mime?.startsWith("audio/") == true) {
                    audioTrackIndex = i
                    audioFormat = format
                    break
                }
            }

            if (audioTrackIndex < 0 || audioFormat == null) {
                return false
            }

            extractor.selectTrack(audioTrackIndex)

            destinationFile.parentFile?.mkdirs()
            if (destinationFile.exists()) {
                destinationFile.delete()
            }

            muxer = MediaMuxer(destinationFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val muxerTrackIndex = muxer.addTrack(audioFormat)
            muxer.start()
            muxerStarted = true

            val bufferSize =
                if (audioFormat.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                    audioFormat.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE).coerceAtLeast(DEFAULT_BUFFER_SIZE)
                } else {
                    DEFAULT_BUFFER_SIZE
                }

            val buffer = ByteBuffer.allocate(bufferSize)
            val bufferInfo = MediaCodec.BufferInfo()

            while (true) {
                val sampleSize = extractor.readSampleData(buffer, 0)
                if (sampleSize < 0) break

                bufferInfo.offset = 0
                bufferInfo.size = sampleSize
                bufferInfo.presentationTimeUs = extractor.sampleTime
                var flags = 0
                val sampleFlags = extractor.sampleFlags
                if ((sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0) {
                    flags = flags or MediaCodec.BUFFER_FLAG_KEY_FRAME
                }
                if ((sampleFlags and MediaExtractor.SAMPLE_FLAG_PARTIAL_FRAME) != 0) {
                    flags = flags or MediaCodec.BUFFER_FLAG_PARTIAL_FRAME
                }
                bufferInfo.flags = flags

                muxer.writeSampleData(muxerTrackIndex, buffer, bufferInfo)
                extractor.advance()
            }

            destinationFile.exists() && destinationFile.length() > 0L
        } catch (e: Exception) {
            e.printStackTrace()
            if (destinationFile.exists()) {
                destinationFile.delete()
            }
            false
        } finally {
            try {
                if (muxerStarted && muxer != null) {
                    muxer.stop()
                }
            } catch (_: Exception) {
            }
            try {
                muxer?.release()
            } catch (_: Exception) {
            }
            try {
                extractor.release()
            } catch (_: Exception) {
            }
        }
    }
}
