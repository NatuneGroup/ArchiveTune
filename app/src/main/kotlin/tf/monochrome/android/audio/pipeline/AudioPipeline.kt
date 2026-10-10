/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © 4nx3b — github.com/4nx3b (audio pipeline inspector)
 */

package tf.monochrome.android.audio.pipeline

enum class PipelineStage(val title: String) {
    TRACK("Track Info"),
    DECODER("Decoder"),
    RESAMPLER("Resampler"),
    DSP("DSP"),
    OUTPUT("Output Device"),
}

data class PipelineField(val label: String, val value: String?) {
    val display: String get() = value ?: EM_DASH
    val isKnown: Boolean get() = value != null
}

data class PipelineSection(
    val stage: PipelineStage,
    val fields: List<PipelineField>,
    val note: String? = null,

    val engaged: Boolean = true,

    val bypassed: Boolean = false,
)

data class AudioPipelineSnapshot(val sections: List<PipelineSection>)

const val EM_DASH = "—"

data class DecodedStream(
    val mimeType: String? = null,
    val sampleRate: Int? = null,
    val channelCount: Int? = null,

    val bitrate: Int? = null,

    val pcmBits: Int? = null,
    val pcmIsFloat: Boolean = false,
)

data class ChainInput(
    val sampleRate: Int,
    val channelCount: Int,
    val layoutName: String,
    val isFloat: Boolean,
)

enum class OutputPath(val api: String) {
    AUDIO_TRACK("AudioTrack"),

    USB_FRAMEWORK("AudioTrack (USB pinned)"),

    USB_EXCLUSIVE("libusb (UAC exclusive)"),
}

data class UsbStream(
    val sampleRateHz: Int,
    val bitsPerSample: Int,
    val channels: Int,
    val detail: String? = null,
)

data class AudioPipelineInputs(
    val stream: DecodedStream? = null,
    val decoderName: String? = null,
    val chain: ChainInput? = null,

    val taggedCodec: String? = null,
    val taggedBitDepth: Int? = null,
    val taggedBitRateKbps: Int? = null,

    val speedRatio: Float = 1f,
    val dspBlockFrames: Int? = null,
    val eqPresetName: String? = null,

    val stereoWidthDb: Float? = null,
    val visualizerFftSize: Int? = null,
    val outputPath: OutputPath = OutputPath.AUDIO_TRACK,
    val deviceName: String? = null,

    val halSampleRateHz: Int? = null,
    val usb: UsbStream? = null,
)

internal fun hz(value: Int?): String? = value?.takeIf { it > 0 }?.let { "$it Hz" }

internal fun bits(value: Int?, isFloat: Boolean = false): String? =
    value?.takeIf { it > 0 }?.let { if (isFloat) "$it-bit float" else "$it-bit" }

internal fun kbps(bitsPerSecond: Int?): String? =
    bitsPerSecond?.takeIf { it > 0 }?.let { "${(it + 500) / 1000} kbps" }

internal fun millis(value: Double?): String? =
    value?.takeIf { it.isFinite() && it > 0 }
        ?.let { String.format(java.util.Locale.ROOT, "%.1f ms", it) }

internal fun channels(count: Int?, layout: String?): String? {
    if (count == null || count <= 0) return null
    val name = layout?.takeIf { it.isNotBlank() }
    return if (name == null) "$count" else "$count ($name)"
}

internal fun codecName(mimeType: String?, tagged: String?): String? {
    val mime = mimeType?.lowercase()
    val fromMime = when {
        mime == null -> null
        mime.endsWith("/flac") -> "FLAC"
        mime.endsWith("/alac") -> "ALAC"
        mime.endsWith("/mpeg") || mime.endsWith("/mp3") -> "MP3"
        mime.endsWith("/mp4a-latm") || mime.contains("aac") -> "AAC"
        mime.endsWith("/opus") -> "Opus"
        mime.endsWith("/vorbis") -> "Vorbis"
        mime.endsWith("/raw") -> "PCM"
        mime.endsWith("/wav") || mime.endsWith("/x-wav") -> "WAV"
        mime.endsWith("/eac3-joc") -> "E-AC-3 JOC"
        mime.endsWith("/eac3") -> "E-AC-3"
        mime.endsWith("/ac3") -> "AC-3"
        mime.endsWith("/ape") -> "APE"
        mime.endsWith("/x-ms-wma") -> "WMA"
        else -> mime.substringAfterLast('/').uppercase().takeIf { it.isNotBlank() }
    }
    return fromMime ?: tagged?.takeIf { it.isNotBlank() }
}

internal fun latencyMs(frames: Int?, sampleRate: Int?): Double? {
    if (frames == null || frames <= 0) return null
    if (sampleRate == null || sampleRate <= 0) return null
    return frames * 1000.0 / sampleRate
}

fun buildAudioPipelineSnapshot(input: AudioPipelineInputs): AudioPipelineSnapshot {
    val inRate = input.chain?.sampleRate ?: input.stream?.sampleRate

    val pcmBits = input.stream?.pcmBits ?: input.taggedBitDepth
    val pcmIsFloat = input.stream?.pcmIsFloat ?: input.chain?.isFloat ?: false

    val track = PipelineSection(
        PipelineStage.TRACK,
        listOf(
            PipelineField("Format", codecName(input.stream?.mimeType, input.taggedCodec)),
            PipelineField("Bit Depth", bits(pcmBits, pcmIsFloat)),
            PipelineField("Sample Rate", hz(inRate)),
            PipelineField(
                "Bitrate",
                kbps(input.stream?.bitrate)
                    ?: input.taggedBitRateKbps?.takeIf { it > 0 }?.let { "$it kbps" },
            ),
            PipelineField(
                "Channels",
                channels(
                    input.chain?.channelCount ?: input.stream?.channelCount,
                    input.chain?.layoutName,
                ),
            ),
        ),
    )

    val decoder = PipelineSection(
        PipelineStage.DECODER,
        listOf(PipelineField("Decoder Name", input.decoderName?.takeIf { it.isNotBlank() })),
        note = if (input.decoderName.isNullOrBlank()) {
            "Reported once the decoder for this track starts."
        } else {
            null
        },

        engaged = !input.decoderName.isNullOrBlank(),
    )

    val outRate = input.usb?.sampleRateHz ?: input.halSampleRateHz
    val conversion = when {
        outRate == null -> null
        inRate == null -> null
        outRate == inRate -> "None — the output takes the source rate"
        else -> when (input.outputPath) {
            OutputPath.USB_EXCLUSIVE -> "USB DAC (exclusive)"
            OutputPath.USB_FRAMEWORK -> "Android HAL, into a USB DAC"
            OutputPath.AUDIO_TRACK -> "Android HAL"
        }
    }
    val ratio = input.speedRatio
    val speedResampling = kotlin.math.abs(ratio - 1f) >= 1e-4f

    val ratesKnownEqual = outRate != null && inRate != null && outRate == inRate
    val resamplerBypassed = ratesKnownEqual && !speedResampling
    val resampler = PipelineSection(
        PipelineStage.RESAMPLER,
        listOf(
            PipelineField(
                "I/O Rate",
                when {
                    inRate == null -> null
                    outRate == null -> "${inRate} Hz → $EM_DASH"
                    else -> "${inRate} Hz → ${outRate} Hz"
                },
            ),
            PipelineField("Conversion", conversion),
            PipelineField(
                "Speed resampler",
                if (!speedResampling) {
                    "Inactive (1.00×)"
                } else {
                    String.format(java.util.Locale.ROOT, "Active (%.2f×)", ratio)
                },
            ),
        ),
        note = when {
            outRate == null ->
                "This app does not resample. Any conversion happens in Android's " +
                    "mixer or in the DAC, which do not report a rate here."
            resamplerBypassed ->
                "Nothing to do at this rate — the signal goes straight past."
            else -> null
        },
        engaged = !resamplerBypassed,
        bypassed = resamplerBypassed,
    )

    val blockLatency = latencyMs(input.dspBlockFrames, inRate)
    val fftLatency = latencyMs(input.visualizerFftSize, inRate)
    val dsp = PipelineSection(
        PipelineStage.DSP,
        listOf(
            PipelineField(
                "PCM Format",
                when {
                    input.chain == null && pcmBits == null -> null
                    input.chain?.isFloat == true || pcmIsFloat -> "32-bit float"
                    else -> bits(pcmBits) ?: "Integer PCM"
                },
            ),
            PipelineField("Sample Rate", hz(inRate)),
            PipelineField("EQ Preset", input.eqPresetName?.takeIf { it.isNotBlank() }),
            PipelineField(
                "Stereo Expand",
                input.stereoWidthDb?.let {
                    String.format(java.util.Locale.ROOT, "%+.1f dB width", it)
                },
            ),
            PipelineField("Buffers", input.dspBlockFrames?.takeIf { it > 0 }?.let { "$it frames" }),
            PipelineField("Latency", millis(blockLatency)),
            PipelineField("Visualizer Latency", millis(fftLatency)),
            PipelineField("Output API", input.outputPath.api),
        ),
        note = if (input.stereoWidthDb == null) {
            "Stereo Expand reads the Stereo snapin's width, and none is in the mixer."
        } else {
            null
        },

        engaged = input.chain != null,
    )

    val output = PipelineSection(
        PipelineStage.OUTPUT,
        listOfNotNull(
            PipelineField("Device Name", input.deviceName?.takeIf { it.isNotBlank() }),

            PipelineField(
                "Bit Depth In",
                when {
                    input.chain?.isFloat == true || pcmIsFloat -> "32-bit float"
                    else -> bits(pcmBits)
                },
            ),
            PipelineField("Bit Depth Out", bits(input.usb?.bitsPerSample)),
            PipelineField("Sample Rate", hz(outRate)),
            input.usb?.detail?.let { PipelineField("Link", it) },
        ),
        note = if (input.outputPath != OutputPath.USB_EXCLUSIVE) {
            "Android reports what is connected, not what the DAC converts to. " +
                "The out side is only measurable over exclusive USB."
        } else {
            null
        },
    )

    return AudioPipelineSnapshot(listOf(track, decoder, resampler, dsp, output))
}
