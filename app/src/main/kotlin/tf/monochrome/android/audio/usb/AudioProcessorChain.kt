/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 * Portions © tryptz — github.com/tryptz/Tryptify
 */

package tf.monochrome.android.audio.usb

import android.util.Log
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer

@UnstableApi
internal class AudioProcessorChain(
    private val processors: List<AudioProcessor>,
) {
    private var active = BooleanArray(processors.size)

    // Media3 configures a sink while the previous track is still draining through it, and only
    // flushes once that drain is done. Membership and the processors' formats therefore wait for
    // flush(), as AudioProcessor's own configure/flush contract does.
    private var pendingActive: BooleanArray? = null
    private var outputFormat: AudioProcessor.AudioFormat =
        AudioProcessor.AudioFormat.NOT_SET

    fun configure(input: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        var fmt = input
        val nextActive = BooleanArray(processors.size)
        for (i in processors.indices) {
            val p = processors[i]
            try {
                val out = p.configure(fmt)
                nextActive[i] = p.isActive
                if (nextActive[i] && out != AudioProcessor.AudioFormat.NOT_SET) {
                    fmt = out
                }
            } catch (e: AudioProcessor.UnhandledAudioFormatException) {
                nextActive[i] = false
            }
        }
        pendingActive = nextActive
        outputFormat = fmt
        Log.i(TAG, "configure($input) -> $fmt; chain: ${membership(nextActive)}")
        return fmt
    }

    private fun membership(flags: BooleanArray = active): String = processors.indices.joinToString(", ") { i ->
        val name = processors[i].javaClass.simpleName
        if (flags[i]) name else "($name skipped)"
    }

    fun process(input: ByteBuffer): ByteBuffer {
        var current = input
        for (i in processors.indices) {
            if (!active[i]) continue
            val p = processors[i]
            if (current.hasRemaining()) {
                p.queueInput(current)
            }
            current = p.getOutput()
        }
        return current
    }

    fun refreshActive() {
        if (pendingActive != null) return
        var changed = false
        for (i in processors.indices) {
            val nowActive = processors[i].isActive
            if (nowActive == active[i]) continue
            active[i] = nowActive
            changed = true
            if (nowActive) processors[i].flush()
        }
        if (changed) Log.i(TAG, "membership changed -> ${membership()}")
    }

    fun queueEndOfStreamAndDrain(): ByteBuffer {
        for (i in processors.indices) {
            if (!active[i]) continue
            runCatching { processors[i].queueEndOfStream() }
                .onFailure { Log.w(TAG, "EOS failed for ${processors[i].javaClass.simpleName}", it) }
        }
        return process(AudioProcessor.EMPTY_BUFFER)
    }

    fun flush() {
        pendingActive?.let {
            active = it
            pendingActive = null
        }
        for (p in processors) p.flush()
    }

    fun reset() {
        for (p in processors) p.reset()
        pendingActive = null
        active = BooleanArray(processors.size)
        outputFormat = AudioProcessor.AudioFormat.NOT_SET
    }

    fun outputFormat(): AudioProcessor.AudioFormat = outputFormat

    fun anyActive(): Boolean = active.any { it }

    private companion object {
        const val TAG = "AudioProcessorChain"
    }
}
