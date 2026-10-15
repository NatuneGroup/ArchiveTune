/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.acoustics

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.ceil

internal object AcousticAuditDecoder {
    private const val DEQUEUE_US = 10_000L

    fun decode(input: AcousticInput, work: AcousticWork, progress: (Float) -> Unit): AcousticPcm {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            work.check()
            // MediaDataSource owns all reads: no unbounded native HTTP/ContentProvider input.
            extractor.setDataSource(input)
            work.check()
            if (extractor.drmInitData != null) throw AcousticUnavailable("drm_unsupported")
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw AcousticUnavailable("no_audio_track")
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: throw AcousticUnavailable("codec_unknown")
            val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) {
                format.getLong(MediaFormat.KEY_DURATION).coerceAtLeast(0)
            } else 0L
            val wantedStartUs = durationUs / 3
            val wantedEndUs = wantedStartUs + AcousticAuditLimits.SAMPLE_SECONDS * 1_000_000L
            extractor.seekTo(wantedStartUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
            work.check()
            format.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            val decoder = MediaCodec.createDecoderByType(mime)
            codec = decoder
            decoder.configure(format, null, null, 0)
            work.check()
            decoder.start()
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var rate = 0
            var channels = 0
            var encoding = 0
            var pcm = FloatArray(0)
            var frames = 0
            var actualStartUs = -1L
            var budgetFrames = 0
            var lastAdvance = System.nanoTime()
            var truncated = false
            while (!outputDone) {
                work.check()
                if (System.nanoTime() - lastAdvance > AcousticAuditLimits.STALL_MS * 1_000_000) {
                    throw AcousticUnavailable("decoder_stalled")
                }
                if (!inputDone) {
                    val index = decoder.dequeueInputBuffer(DEQUEUE_US)
                    if (index >= 0) {
                        val buffer = decoder.getInputBuffer(index) ?: throw AcousticUnavailable("decoder_unavailable")
                        if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_ENCRYPTED != 0) {
                            throw AcousticUnavailable("encrypted_unsupported")
                        }
                        val pts = extractor.sampleTime
                        val count = if (pts < 0 || pts >= wantedEndUs) -1 else extractor.readSampleData(buffer, 0)
                        work.check()
                        if (count < 0) {
                            decoder.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(index, 0, count, pts, 0)
                            extractor.advance()
                        }
                    }
                }
                when (val index = decoder.dequeueOutputBuffer(info, DEQUEUE_US)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val output = decoder.outputFormat
                        if (!output.containsKey(MediaFormat.KEY_SAMPLE_RATE) ||
                            !output.containsKey(MediaFormat.KEY_CHANNEL_COUNT) ||
                            !output.containsKey(MediaFormat.KEY_PCM_ENCODING)
                        ) throw AcousticUnavailable("decoded_format_unknown")
                        val newRate = output.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        val newChannels = output.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        val newEncoding = output.getInteger(MediaFormat.KEY_PCM_ENCODING)
                        if (newRate !in 8_000..192_000 || newChannels !in 1..8 ||
                            newEncoding !in listOf(AudioFormat.ENCODING_PCM_16BIT, AudioFormat.ENCODING_PCM_FLOAT)
                        ) throw AcousticUnavailable("decoded_format_unsupported")
                        if (rate != 0 && (rate != newRate || channels != newChannels || encoding != newEncoding)) {
                            throw AcousticUnavailable("decoded_format_changed")
                        }
                        if (rate == 0) {
                            rate = newRate
                            channels = newChannels
                            encoding = newEncoding
                            budgetFrames = minOf(
                                rate * AcousticAuditLimits.SAMPLE_SECONDS,
                                AcousticAuditLimits.MAX_PCM_FRAMES,
                                AcousticAuditLimits.MAX_PCM_BYTES / Float.SIZE_BYTES / channels,
                            )
                            val runtime = Runtime.getRuntime()
                            val available = runtime.maxMemory() - runtime.totalMemory() + runtime.freeMemory()
                            val required = budgetFrames.toLong() * channels * Float.SIZE_BYTES +
                                AcousticAuditLimits.MAX_HEAT_CELLS * Float.SIZE_BYTES + 8L * 1024 * 1024
                            if (available < required) throw AcousticUnavailable("insufficient_heap_headroom")
                            pcm = FloatArray(budgetFrames * channels)
                        }
                    }
                    else -> if (index >= 0) {
                        try {
                            if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                                if (rate == 0) throw AcousticUnavailable("decoded_format_unknown")
                                val buffer = decoder.getOutputBuffer(index)
                                    ?: throw AcousticUnavailable("decoder_unavailable")
                                val bytesPerSample = if (encoding == AudioFormat.ENCODING_PCM_FLOAT) 4 else 2
                                val bytesPerFrame = bytesPerSample * channels
                                if (info.offset < 0 || info.size > buffer.capacity() - info.offset ||
                                    info.size % bytesPerFrame != 0 || info.presentationTimeUs < 0
                                ) throw AcousticUnavailable("decoded_buffer_invalid")
                                val chunkFrames = info.size / bytesPerFrame
                                var skip = ceil(
                                    (wantedStartUs - info.presentationTimeUs).coerceAtLeast(0).toDouble() * rate / 1_000_000,
                                ).toInt().coerceIn(0, chunkFrames)
                                buffer.order(ByteOrder.LITTLE_ENDIAN)
                                while (skip < chunkFrames && frames < budgetFrames) {
                                    if (skip % 1024 == 0) work.check()
                                    val pts = info.presentationTimeUs + skip * 1_000_000L / rate
                                    if (pts >= wantedEndUs) {
                                        outputDone = true
                                        break
                                    }
                                    if (actualStartUs < 0) actualStartUs = pts
                                    if (abs(pts - (actualStartUs + frames * 1_000_000L / rate)) > 10_000L) {
                                        throw AcousticUnavailable("decoded_timestamps_discontinuous")
                                    }
                                    for (channel in 0 until channels) {
                                        val offset = info.offset + (skip * channels + channel) * bytesPerSample
                                        val value = if (encoding == AudioFormat.ENCODING_PCM_FLOAT) {
                                            buffer.getFloat(offset)
                                        } else buffer.getShort(offset) / 32768f
                                        if (!value.isFinite()) throw AcousticUnavailable("non_finite_pcm")
                                        pcm[frames * channels + channel] = value
                                    }
                                    frames++
                                    skip++
                                }
                                // Input activity alone cannot keep a decoder that emits no PCM alive.
                                if (chunkFrames > 0) lastAdvance = System.nanoTime()
                                if (frames >= budgetFrames) {
                                    truncated = budgetFrames < rate * AcousticAuditLimits.SAMPLE_SECONDS
                                    outputDone = true
                                }
                                progress(frames.toFloat() / budgetFrames)
                            }
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                        } finally {
                            decoder.releaseOutputBuffer(index, false)
                        }
                    }
                }
            }
            work.check()
            if (frames < 2048 || actualStartUs < 0) throw AcousticUnavailable("insufficient_pcm")
            // Keep the bounded allocation; frames identifies the populated prefix without a second PCM copy.
            return AcousticPcm(
                pcm, rate, channels, actualStartUs, frames, truncated,
                encoding == AudioFormat.ENCODING_PCM_FLOAT,
            )
        } catch (_: MediaCodec.CodecException) {
            throw AcousticUnavailable("codec_unsupported")
        } catch (_: IllegalArgumentException) {
            throw AcousticUnavailable("container_or_codec_unsupported")
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }
}
