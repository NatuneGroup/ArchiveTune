/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */



@file:OptIn(UnstableApi::class)

package moe.rukamori.archivetune.playback

import android.os.Build
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import kotlin.math.abs

/**
 * Pass-through Media3 audio processor that taps the decoded PCM graph and
 * feeds the SpatialFlow music-haptics engine — a faithful port of the
 * analysis half of SpatialFlow's StereoBalanceProcessor
 * (github.com/MythicalSHUB/SpatialFlow, AudioPlaybackService#
 * analyzePcmForHaptics, GPL-3.0):
 *
 *  - 1st-order IIR crossover filters split the mono-mixed signal into
 *    sub-bass / bass / mid / high band states,
 *  - the loop downsamples to every 8th frame to keep the CPU cost negligible,
 *  - mean absolute band values are scaled x4 and coerced into the engine's
 *    0..1 inputs — exactly the normalization SpatialFlow uses.
 *
 * Because the tap sits inside Media3's audio processor chain, it sees the
 * same decoded audio that reaches the speakers (local and streamed alike)
 * and requires NO runtime permission. Samples are never rewritten: the
 * processor copies its input through untouched.
 */
class HapticsPcmProcessor(
    private val engineProvider: () -> SpatialFlowHapticEngine?,
) : BaseAudioProcessor() {

    // Filter states for the pure-PCM premium real-time haptics crossover.
    private var subBassFilterState = 0f
    private var bassFilterState = 0f
    private var midFilterState = 0f

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT &&
            inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT
        ) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        // Pass-through: analysis never rewrites samples.
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return

        val isStereo = inputAudioFormat.channelCount == 2
        val is16Bit = inputAudioFormat.encoding == C.ENCODING_PCM_16BIT

        analyzePcmForHaptics(inputBuffer, is16Bit, isStereo, remaining / (if (is16Bit) 2 else 4) / inputAudioFormat.channelCount)

        replaceOutputBuffer(remaining).put(inputBuffer).flip()
    }

    private fun analyzePcmForHaptics(
        buffer: ByteBuffer,
        is16Bit: Boolean,
        isStereo: Boolean,
        frameCount: Int,
    ) {
        val haptic = engineProvider() ?: return
        if (!haptic.isHapticsEnabled) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O_MR1) return

        var sumSubBass = 0f
        var sumBass = 0f
        var sumMid = 0f
        var sumHigh = 0f
        var count = 0

        // Downsample by processing every 8th frame to save CPU.
        val step = 8
        val bytesPerSample = if (is16Bit) 2 else 4
        val bytesPerFrame = bytesPerSample * (if (isStereo) 2 else 1)
        val startPos = buffer.position()

        for (i in 0 until frameCount step step) {
            val bytePos = startPos + i * bytesPerFrame
            if (bytePos + bytesPerFrame > buffer.limit()) break

            val sampleVal =
                if (is16Bit) {
                    val left = buffer.getShort(bytePos).toFloat()
                    val right = if (isStereo) buffer.getShort(bytePos + 2).toFloat() else left
                    (left + right) / (2f * 32768f)
                } else {
                    val left = buffer.getFloat(bytePos)
                    val right = if (isStereo) buffer.getFloat(bytePos + 4) else left
                    (left + right) / 2f
                }

            // Running DSP crossover filters (1st-order IIR) — SpatialFlow's
            // exact coefficients.
            subBassFilterState = 0.007f * sampleVal + 0.993f * subBassFilterState
            bassFilterState = 0.028f * sampleVal + 0.972f * bassFilterState
            midFilterState = 0.42f * sampleVal + 0.58f * midFilterState

            val subBassSample = subBassFilterState
            val bassSample = bassFilterState - subBassFilterState
            val midSample = midFilterState - bassFilterState
            val highSample = sampleVal - midFilterState

            sumSubBass += abs(subBassSample)
            sumBass += abs(bassSample)
            sumMid += abs(midSample)
            sumHigh += abs(highSample)
            count++
        }

        if (count > 0) {
            // Map the accumulated band absolute averages to normalized
            // visualizer-equivalent inputs (0 to 1 range).
            val subBassEnergy = (sumSubBass / count) * 4f
            val bassEnergy = (sumBass / count) * 4f
            val midEnergy = (sumMid / count) * 4f
            val highEnergy = (sumHigh / count) * 4f

            haptic.processPcmHaptics(
                subBassEnergy.coerceIn(0f, 1f),
                bassEnergy.coerceIn(0f, 1f),
                midEnergy.coerceIn(0f, 1f),
                highEnergy.coerceIn(0f, 1f),
            )
        }
    }
}
