package tf.monochrome.android.audio.usb

internal object UsbNativeLoader {
    init { System.loadLibrary("monochrome_usb") }

    @JvmStatic
    fun ensureLoaded() {  }
}
