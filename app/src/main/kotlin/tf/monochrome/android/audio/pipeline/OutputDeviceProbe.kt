/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © 4nx3b — github.com/4nx3b (audio pipeline inspector)
 */

package tf.monochrome.android.audio.pipeline

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import javax.inject.Inject
import javax.inject.Singleton

data class RoutedOutput(val name: String, val typeLabel: String)

data class OutputSnapshot(val routed: RoutedOutput?, val halSampleRateHz: Int?)

@Singleton
class OutputDeviceProbe @Inject constructor(
    @ApplicationContext private val appContext: Context,
) {
    private val audioManager =
        appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    /**
     * The device callback is registered only while this is collected, so a closed media-info
     * sheet leaves nothing listening. Registration itself reports the connected devices, which
     * provides the first value.
     */
    val snapshots: Flow<OutputSnapshot> =
        callbackFlow {
            val callback =
                object : AudioDeviceCallback() {
                    override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) {
                        trySend(OutputSnapshot(currentOutput(), halSampleRateHz()))
                    }

                    override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) {
                        trySend(OutputSnapshot(currentOutput(), halSampleRateHz()))
                    }
                }
            audioManager.registerAudioDeviceCallback(callback, Handler(Looper.getMainLooper()))
            awaitClose { audioManager.unregisterAudioDeviceCallback(callback) }
        }.distinctUntilChanged()

    fun halSampleRateHz(): Int? =
        audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)
            ?.toIntOrNull()
            ?.takeIf { it > 0 }

    private fun currentOutput(): RoutedOutput? {
        val outputs = runCatching {
            audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList()
        }.getOrElse { return null }

        val ordered = listOf(
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_USB_ACCESSORY,
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_HDMI,
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
        )
        val device = ordered.firstNotNullOfOrNull { type ->
            outputs.firstOrNull { it.type == type }
        } ?: outputs.firstOrNull() ?: return null
        return RoutedOutput(name = describe(device), typeLabel = typeLabel(device.type))
    }

    private fun describe(device: AudioDeviceInfo): String =
        device.productName?.toString()?.takeIf { it.isNotBlank() }
            ?: typeLabel(device.type)

    private fun typeLabel(type: Int): String = when (type) {
        AudioDeviceInfo.TYPE_USB_HEADSET -> "USB headset"
        AudioDeviceInfo.TYPE_USB_DEVICE -> "USB audio device"
        AudioDeviceInfo.TYPE_USB_ACCESSORY -> "USB accessory"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired headset"
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "Wired headphones"
        AudioDeviceInfo.TYPE_HDMI -> "HDMI"
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Phone speaker"
        else -> "Audio output"
    }
}
