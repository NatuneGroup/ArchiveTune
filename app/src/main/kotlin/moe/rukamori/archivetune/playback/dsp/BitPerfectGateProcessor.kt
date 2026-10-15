/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.playback.dsp

import java.nio.ByteBuffer
import android.content.Context
import android.util.Log
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.exoplayer.audio.SilenceSkippingAudioProcessor

@androidx.media3.common.util.UnstableApi
class BitPerfectGateProcessor(
    private val contextProvider: () -> Context?,
    private val engineOrDspEngaged: () -> Boolean,
    private val usbExclusiveActive: () -> Boolean,
    private val effectiveVolume: () -> Float,
    private val enginesEngaged: () -> Boolean = { false },
    private val onRouteEvaluated: (() -> Unit)? = null,
) : BaseAudioProcessor() {
    @Volatile private var sonicAudioProcessor: SonicAudioProcessor? = null
    @Volatile private var silenceSkippingAudioProcessor: SilenceSkippingAudioProcessor? = null

    fun attachTransparentTargets(
        sonicAudioProcessor: SonicAudioProcessor,
        silenceSkippingAudioProcessor: SilenceSkippingAudioProcessor,
    ) {
        this.sonicAudioProcessor = sonicAudioProcessor
        this.silenceSkippingAudioProcessor = silenceSkippingAudioProcessor
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        val context = contextProvider()
        if (context == null) {
            BitPerfectRuntime.clearTrack()
        } else {
            val bypass = BitPerfectRuntime.evaluateTrack(
                context = context,
                inputEncoding = inputAudioFormat.encoding,
                inputSampleRate = inputAudioFormat.sampleRate,
                inputChannels = inputAudioFormat.channelCount,
                engineOrDspEngaged = engineOrDspEngaged(),
                usbExclusive = usbExclusiveActive(),
                effectiveVolume = effectiveVolume(),
                enginesEngaged = enginesEngaged(),
            )

            onRouteEvaluated?.invoke()
            if (bypass) {
                silenceSkippingAudioProcessor?.setEnabled(false)
                sonicAudioProcessor?.setSpeed(1f)
                sonicAudioProcessor?.setPitch(1f)
                Log.i(
                    TAG,
                    "Bit-Perfect ENGAGED: ${BitPerfectRuntime.bitDepthOf(inputAudioFormat.encoding)}bit/" +
                        "${inputAudioFormat.sampleRate}Hz/${inputAudioFormat.channelCount}ch " +
                        "passes the chain untouched",
                )
            } else if (BitPerfectRuntime.requested) {
                silenceSkippingAudioProcessor?.setEnabled(false)
                sonicAudioProcessor?.setSpeed(1f)
                sonicAudioProcessor?.setPitch(1f)
            }
        }
        return AudioProcessor.AudioFormat.NOT_SET
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
    }

    private companion object {
        const val TAG = "BitPerfectRuntime"
    }
}
