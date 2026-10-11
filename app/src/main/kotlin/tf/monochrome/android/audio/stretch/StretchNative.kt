/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 * Portions © tryptz — github.com/tryptz/Tryptify
 */

package tf.monochrome.android.audio.stretch

import java.nio.ByteBuffer

object StretchNative {
    @Volatile private var available: Boolean = false

    init {
        available = try {
            System.loadLibrary("monochrome_stretch")
            true
        } catch (e: UnsatisfiedLinkError) {
            false
        } catch (e: SecurityException) {
            false
        }
    }

    val isAvailable: Boolean get() = available

    external fun nativeCreate(channels: Int, sampleRate: Int): Long

    external fun nativeDestroy(handle: Long)

    external fun nativeSetSemitones(handle: Long, semitones: Float)

    external fun nativeSetEngine(handle: Long, engine: Int, quality: Int)

    external fun nativeReset(handle: Long): Boolean

    external fun nativeLatencyFrames(handle: Long): Int

    external fun nativeMaxBlockFrames(): Int

    external fun nativeProcess(
        handle: Long,
        input: ByteBuffer,
        output: ByteBuffer,
        frames: Int,
    ): Int
}
