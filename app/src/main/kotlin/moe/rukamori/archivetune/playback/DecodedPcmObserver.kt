/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package moe.rukamori.archivetune.playback

import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer

internal class DecodedPcmObserver(
    private val onFormatObserved: (AudioProcessor.AudioFormat?) -> Unit,
    private val playbackKeyProvider: () -> String? = { null },
) : BaseAudioProcessor() {
    private var reportedFormat: AudioProcessor.AudioFormat? = null
    private var reportedPlaybackKey: String? = null

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat =
        inputAudioFormat

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!inputBuffer.hasRemaining()) return
        val playbackKey = playbackKeyProvider()
        if (reportedFormat != inputAudioFormat || reportedPlaybackKey != playbackKey) {
            reportedFormat = inputAudioFormat
            reportedPlaybackKey = playbackKey
            onFormatObserved(inputAudioFormat)
        }
        replaceOutputBuffer(inputBuffer.remaining()).put(inputBuffer).flip()
    }

    override fun onFlush() {
        reportedFormat = null
        reportedPlaybackKey = null
        onFormatObserved(null)
    }

    override fun onReset() {
        reportedFormat = null
        reportedPlaybackKey = null
        onFormatObserved(null)
    }
}
