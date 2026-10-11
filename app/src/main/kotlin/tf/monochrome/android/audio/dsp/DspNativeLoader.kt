package tf.monochrome.android.audio.dsp

internal object DspNativeLoader {
    init { System.loadLibrary("monochrome_dsp") }

    @JvmStatic
    fun ensureLoaded() {
    }
}
