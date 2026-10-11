package tf.monochrome.android.audio.usb

import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BypassVolumeController @Inject constructor() {
    @Volatile
    private var volume: Float = 1.0f

    fun setVolume(v: Float) {
        volume = v.coerceIn(0f, 1f)
    }

    fun getVolume(): Float = volume
}
