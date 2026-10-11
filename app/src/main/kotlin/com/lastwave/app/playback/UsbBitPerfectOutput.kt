package com.lastwave.app.playback

import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioMixerAttributes
import android.os.Build

class UsbBitPerfectOutput(
    private val manager: AudioManager?,
    private val allowAnyOutputDevice: Boolean = false,
) {
    private var device: AudioDeviceInfo? = null
    private var format: AudioFormat? = null
    private var enabled = false
    private var sourceBits: Int = 0
    private var attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
    private var requestedDevice: AudioDeviceInfo? = null
    private var configuredWireFormat: AudioFormat? = null

    @Synchronized
    fun setSourceBits(bits: Int?) {
        val value = bits ?: 0
        if (value == sourceBits) return
        sourceBits = value
        apply()
    }

    @Synchronized
    fun configuredRateHz(): Int = configuredWireFormat?.sampleRate ?: 0

    @Synchronized
    fun configuredBits(): Int = encodingBits(configuredWireFormat?.encoding)

    @Synchronized
    fun setDevice(value: AudioDeviceInfo?) {
        if (device?.id == value?.id) return
        clear()
        device = value
        apply()
    }

    @Synchronized
    fun setEnabled(value: Boolean) {
        if (enabled == value) return
        enabled = value
        apply()
    }

    @Synchronized
    fun setAttributes(value: AudioAttributes) {
        if (attributes == value) return
        clear()
        attributes = value
        apply()
    }

    @Synchronized
    fun setFormat(value: AudioFormat?) {
        if (sameFormat(format, value)) return
        clear()
        format = value
        apply()
    }

    @Synchronized
    fun isConfigured(): Boolean {
        if (Build.VERSION.SDK_INT < 34 || !enabled || requestedDevice == null) return false
        return runCatching {
            val preferred = manager?.getPreferredMixerAttributes(attributes, requestedDevice!!)
            preferred?.mixerBehavior == AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT &&
                (configuredWireFormat == null || sameFormat(preferred.format, configuredWireFormat))
        }.getOrDefault(false)
    }

    private fun apply() {
        if (Build.VERSION.SDK_INT < 34) {
            if (enabled) {
                android.util.Log.w(
                    TAG,
                    "BIT-PERFECT mixer bypass requires Android 14+ (API 34); " +
                        "SDK=${Build.VERSION.SDK_INT} cannot bypass the shared mixer",
                )
            }
            return
        }
        val target = device
        val pcm = format
        if (!enabled || target == null || pcm == null) {
            clear()
            return
        }
        if (!allowAnyOutputDevice &&
            target.type != AudioDeviceInfo.TYPE_USB_DEVICE &&
            target.type != AudioDeviceInfo.TYPE_USB_HEADSET
        ) {
            return
        }
        runCatching {
            val all = manager?.getSupportedMixerAttributes(target).orEmpty()
            val bitPerfectModes = all.filter {
                it.mixerBehavior == AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT
            }
            if (bitPerfectModes.isEmpty()) {
                android.util.Log.w(
                    TAG,
                    "BIT-PERFECT unsupported by ${target.productName}: " +
                        "no BIT_PERFECT mixer mode advertised — clearing any stale preference",
                )

                clear()
                return
            }

            val supported = bitPerfectModes.firstOrNull { sameFormat(it.format, pcm) }
                ?: bitPerfectModes.firstOrNull { candidate ->
                    candidate.format?.let { fmt ->
                        fmt.sampleRate == pcm.sampleRate &&
                            encodingBits(fmt.encoding).let { bits ->
                                bits >= 24 || (bits == 16 && sourceBits in 0..16)
                            }
                    } == true
                }
            if (supported == null) {
                val want = "${pcm.encoding}/${pcm.sampleRate}Hz/mask=${pcm.channelMask}"
                val have = bitPerfectModes.mapNotNull { it.format }
                    .joinToString { "${it.encoding}/${it.sampleRate}Hz/mask=${it.channelMask}" }
                android.util.Log.w(
                    TAG,
                    "BIT-PERFECT format mismatch: want $want (sourceBits=$sourceBits); " +
                        "${target.productName} offers [$have] — clearing stale preference " +
                        "to prevent mis-routed PCM (buzzing)",
                )

                clear()
                return
            }
            if (manager?.setPreferredMixerAttributes(attributes, target, supported) == true) {
                requestedDevice = target
                configuredWireFormat = supported.format
                android.util.Log.i(
                    TAG,
                    "BIT-PERFECT mixer bypass granted: ${supported.format?.encoding}/" +
                        "${supported.format?.sampleRate}Hz -> ${target.productName}",
                )
            } else {
                android.util.Log.w(TAG, "BIT-PERFECT setPreferredMixerAttributes rejected by platform")
            }
        }
    }

    private fun clear() {
        val previous = requestedDevice
        requestedDevice = null
        configuredWireFormat = null
        if (Build.VERSION.SDK_INT >= 34 && previous != null) {
            runCatching { manager?.clearPreferredMixerAttributes(attributes, previous) }
        }
    }

    private fun encodingBits(encoding: Int?): Int = when (encoding) {
        AudioFormat.ENCODING_PCM_FLOAT -> 32
        AudioFormat.ENCODING_PCM_32BIT -> 32
        AudioFormat.ENCODING_PCM_24BIT_PACKED -> 24
        AudioFormat.ENCODING_PCM_16BIT -> 16
        AudioFormat.ENCODING_PCM_8BIT -> 8
        else -> 0
    }

    private fun sameFormat(a: AudioFormat?, b: AudioFormat?): Boolean {
        if (a == null || b == null) return a == null && b == null
        if (a === b) return true
        return runCatching {
            a.encoding == b.encoding &&
                a.sampleRate == b.sampleRate &&
                a.channelMask == b.channelMask
        }.getOrDefault(false)
    }

    private companion object {
        const val TAG = "UsbBitPerfect"
    }
}
