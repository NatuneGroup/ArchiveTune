/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.playback

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioMixerAttributes
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioOutputProvider
import moe.rukamori.archivetune.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber

/**
 * Android 14+ bit-perfect output to USB DACs, via the platform's preferred-mixer API.
 *
 * How it works: just before ExoPlayer creates an AudioTrack, [plan] asks the platform which
 * bit-perfect mixer configurations the attached USB device offers, picks the one matching the
 * track's sample rate and channel layout with the deepest integer container, and registers it with
 * `AudioManager.setPreferredMixerAttributes`. The AudioTrack is then created in exactly that format
 * (see [BitPerfectAudioOutputProvider]), which is what the platform requires before it routes the
 * stream through its bit-perfect thread instead of the system mixer.
 *
 * Everything degrades rather than fails: no Android 14, no USB device, no matching configuration, or
 * a refused request all answer null, clear any preference this app set, and let playback continue
 * through the normal mixer. [status] records which of those happened for the settings screen.
 */
@UnstableApi
object BitPerfectUsbOutput {
    private const val TAG = "BitPerfectUsb"

    /** What the most recent AudioTrack creation ended up doing. */
    sealed interface Status {
        /** The switch is off. */
        data object Off : Status

        /** The platform predates the preferred-mixer API (Android 14). */
        data object Unsupported : Status

        /** Switch on, but no USB audio output is attached. */
        data object NoDevice : Status

        /** Switch on and a DAC is attached; nothing has played through it yet. */
        data class Ready(
            val deviceName: String,
        ) : Status

        /** The current track is going to the DAC bit-perfect in [format]. */
        data class Active(
            val deviceName: String,
            val format: String,
        ) : Status

        /** A DAC is attached but the current track could not go bit-perfect, for [reason]. */
        data class Fallback(
            val deviceName: String,
            val reason: String,
        ) : Status
    }

    /** Target AudioTrack encoding chosen for one AudioTrack. */
    data class Plan(
        val encoding: Int,
    )

    private val _status = MutableStateFlow<Status>(Status.Off)
    val status: StateFlow<Status> = _status.asStateFlow()

    /** Mirrors the user's switch; read on the playback thread at AudioTrack creation. */
    @Volatile
    var enabled: Boolean = false
        private set

    /**
     * Whether the running players were built with the bit-perfect sink. The switch can be on while
     * this is false (it was flipped after the service started), which the settings screen reports
     * as "takes effect after restart" instead of claiming an output that is not in use.
     */
    @Volatile
    var sinkActive: Boolean = false

    /** Device the current preference is registered on, so it can be cleared later. */
    @Volatile
    private var registeredDeviceId: Int? = null

    @get:androidx.annotation.ChecksSdkIntAtLeast(api = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    val isPlatformSupported: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE

    /**
     * Applies the user's switch. Turning it off removes the preference this app registered so the
     * DAC returns to the shared mixer immediately rather than after the next device reconnect.
     */
    fun setEnabled(
        context: Context,
        value: Boolean,
    ) {
        enabled = value
        if (!value) {
            clear(context)
            _status.value = Status.Off
        } else {
            refreshDeviceStatus(context)
        }
    }

    /** Re-reads the attached devices so the settings screen reflects a plug or unplug. */
    fun refreshDeviceStatus(context: Context) {
        if (!enabled) {
            _status.value = Status.Off
            return
        }
        if (!isPlatformSupported) {
            _status.value = Status.Unsupported
            return
        }
        val device = findUsbOutput(context)
        val current = _status.value
        _status.value =
            when {
                device == null -> Status.NoDevice
                current is Status.Active && current.deviceName == device.displayName() -> current
                current is Status.Fallback && current.deviceName == device.displayName() -> current
                else -> Status.Ready(device.displayName())
            }
    }

    /** True when a USB audio output is attached right now. */
    fun hasUsbOutput(context: Context): Boolean = findUsbOutput(context) != null

    /**
     * Decides how the AudioTrack for [config] should be created. Returns the encoding to create it
     * in when bit-perfect output was granted, or null to create it unchanged through the normal
     * mixer. Never throws: any platform failure is logged and treated as "not granted".
     */
    fun plan(
        context: Context,
        config: AudioOutputProvider.OutputConfig,
    ): Plan? {
        if (!enabled) return null
        if (!isPlatformSupported) {
            _status.value = Status.Unsupported
            return null
        }
        if (config.isOffload || config.isTunneling || !isLinearPcm(config.encoding)) return null
        return runCatching { planApi34(context, config) }
            .onFailure { Timber.tag(TAG).w(it, "bit-perfect negotiation failed") }
            .getOrNull()
    }

    /** Removes the preference this app registered, if any. Safe to call repeatedly. */
    fun clear(context: Context) {
        if (!isPlatformSupported) return
        val deviceId = registeredDeviceId ?: return
        runCatching { clearApi34(context, deviceId) }
            .onFailure { Timber.tag(TAG).w(it, "clearing preferred mixer failed") }
        registeredDeviceId = null
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun planApi34(
        context: Context,
        config: AudioOutputProvider.OutputConfig,
    ): Plan? {
        val audioManager = context.getSystemService(AudioManager::class.java) ?: return null
        val device = findUsbOutput(context)
        if (device == null) {
            clear(context)
            _status.value = Status.NoDevice
            return null
        }
        val name = device.displayName()
        val attributes = config.audioAttributes.platformAudioAttributes
        val candidates =
            audioManager
                .getSupportedMixerAttributes(device)
                .filter { mixer ->
                    val format = mixer.format
                    mixer.mixerBehavior == AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT &&
                        format.sampleRate == config.sampleRate &&
                        format.channelMask == config.channelMask &&
                        encodingRank(format.encoding) > 0
                }
        val best = candidates.maxByOrNull { encodingRank(it.format.encoding) }
        if (best == null) {
            clearApi34(context, device.id)
            registeredDeviceId = null
            val reason =
                context.getString(
                    R.string.bit_perfect_usb_reason_no_mode,
                    name.ifBlank { context.getString(R.string.bit_perfect_usb_default_device) },
                    formatRate(config.sampleRate),
                    channelLabel(config.channelMask),
                )
            Timber.tag(TAG).i(reason)
            _status.value = Status.Fallback(name, reason)
            return null
        }
        val granted = audioManager.setPreferredMixerAttributes(attributes, device, best)
        if (!granted) {
            clearApi34(context, device.id)
            registeredDeviceId = null
            val reason = context.getString(R.string.bit_perfect_usb_reason_refused)
            Timber.tag(TAG).w("%s (%s)", reason, name)
            _status.value = Status.Fallback(name, reason)
            return null
        }
        registeredDeviceId = device.id
        val label = "${encodingLabel(best.format.encoding)} / ${formatRate(config.sampleRate)}"
        Timber.tag(TAG).i("bit-perfect granted on %s: %s", name, label)
        _status.value = Status.Active(name, label)
        return Plan(best.format.encoding)
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun clearApi34(
        context: Context,
        deviceId: Int,
    ) {
        val audioManager = context.getSystemService(AudioManager::class.java) ?: return
        val device =
            audioManager
                .getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                .firstOrNull { it.id == deviceId } ?: return
        val attributes =
            android.media.AudioAttributes
                .Builder()
                .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
        audioManager.clearPreferredMixerAttributes(attributes, device)
    }

    private fun findUsbOutput(context: Context): AudioDeviceInfo? {
        val audioManager = context.getSystemService(AudioManager::class.java) ?: return null
        return audioManager
            .getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .firstOrNull { it.type == AudioDeviceInfo.TYPE_USB_DEVICE || it.type == AudioDeviceInfo.TYPE_USB_HEADSET }
    }

    private fun AudioDeviceInfo.displayName(): String = productName?.toString().orEmpty().trim()

    /**
     * Deeper integer containers first. Float is accepted only as a last resort before 16-bit: it
     * carries 24-bit sources exactly, but the DAC side still converts it.
     */
    private fun encodingRank(encoding: Int): Int =
        when (encoding) {
            AudioFormat.ENCODING_PCM_32BIT -> 4
            AudioFormat.ENCODING_PCM_24BIT_PACKED -> 3
            AudioFormat.ENCODING_PCM_FLOAT -> 2
            AudioFormat.ENCODING_PCM_16BIT -> 1
            else -> 0
        }

    private fun isLinearPcm(encoding: Int): Boolean =
        encoding == C.ENCODING_PCM_16BIT ||
            encoding == C.ENCODING_PCM_24BIT ||
            encoding == C.ENCODING_PCM_32BIT ||
            encoding == C.ENCODING_PCM_FLOAT

    private fun encodingLabel(encoding: Int): String =
        when (encoding) {
            AudioFormat.ENCODING_PCM_32BIT -> "32-bit"
            AudioFormat.ENCODING_PCM_24BIT_PACKED -> "24-bit"
            AudioFormat.ENCODING_PCM_FLOAT -> "32-bit float"
            AudioFormat.ENCODING_PCM_16BIT -> "16-bit"
            else -> "PCM"
        }

    private fun formatRate(sampleRate: Int): String {
        val khz = sampleRate / 1000.0
        return if (sampleRate % 1000 == 0) "${sampleRate / 1000} kHz" else String.format(java.util.Locale.US, "%.1f kHz", khz)
    }

    private fun channelLabel(channelMask: Int): String =
        when (Integer.bitCount(channelMask)) {
            1 -> "mono"
            2 -> "stereo"
            else -> "${Integer.bitCount(channelMask)} channels"
        }
}
