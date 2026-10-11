package tf.monochrome.android.audio

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UsbAudioRouter @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val audioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val callbackHandler = Handler(Looper.getMainLooper())

    private val _usbOutputDevice = MutableStateFlow<AudioDeviceInfo?>(initialUsbDevice())
    val usbOutputDevice: StateFlow<AudioDeviceInfo?> = _usbOutputDevice.asStateFlow()

    init {
        audioManager.registerAudioDeviceCallback(
            object : AudioDeviceCallback() {
                override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
                    refresh()
                }

                override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
                    refresh()
                }
            },
            callbackHandler,
        )
    }

    private fun refresh() {
        _usbOutputDevice.value = currentUsbOutput()
    }

    private fun initialUsbDevice(): AudioDeviceInfo? = currentUsbOutput()

    private fun currentUsbOutput(): AudioDeviceInfo? {
        val outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)

        return outputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_USB_HEADSET }
            ?: outputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_USB_DEVICE }
            ?: outputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_USB_ACCESSORY }
    }

    fun describe(device: AudioDeviceInfo): String =
        device.productName?.toString()?.takeIf { it.isNotBlank() }
            ?: when (device.type) {
                AudioDeviceInfo.TYPE_USB_HEADSET -> "USB Headset"
                AudioDeviceInfo.TYPE_USB_DEVICE -> "USB Audio Device"
                AudioDeviceInfo.TYPE_USB_ACCESSORY -> "USB Accessory"
                else -> "USB Output"
            }
}
