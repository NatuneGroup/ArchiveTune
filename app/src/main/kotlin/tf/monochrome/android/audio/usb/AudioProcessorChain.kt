package tf.monochrome.android.audio.usb

import android.util.Log
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer

@UnstableApi
internal class AudioProcessorChain(
    private val processors: List<AudioProcessor>,
) {
    private val active = BooleanArray(processors.size)
    private var outputFormat: AudioProcessor.AudioFormat =
        AudioProcessor.AudioFormat.NOT_SET

    fun configure(input: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        var fmt = input
        for (i in processors.indices) {
            val p = processors[i]
            try {
                val out = p.configure(fmt)
                active[i] = p.isActive
                if (active[i] && out != AudioProcessor.AudioFormat.NOT_SET) {
                    fmt = out
                }
            } catch (e: AudioProcessor.UnhandledAudioFormatException) {
                active[i] = false
            }
            p.flush()
        }
        outputFormat = fmt
        Log.i(TAG, "configure($input) -> $fmt; chain: ${membership()}")
        return fmt
    }

    private fun membership(): String = processors.indices.joinToString(", ") { i ->
        val name = processors[i].javaClass.simpleName
        if (active[i]) name else "($name skipped)"
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
        for (p in processors) p.flush()
    }

    fun reset() {
        for (p in processors) p.reset()
    }

    fun outputFormat(): AudioProcessor.AudioFormat = outputFormat

    fun anyActive(): Boolean = active.any { it }

    private companion object {
        const val TAG = "AudioProcessorChain"
    }
}
