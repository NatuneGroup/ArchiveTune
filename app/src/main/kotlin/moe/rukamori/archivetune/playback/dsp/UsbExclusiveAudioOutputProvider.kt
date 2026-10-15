/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 * Portions © 4nx3b — github.com/4nx3b/ArchiveTune (GPL-3.0)
 */

@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package moe.rukamori.archivetune.playback.dsp

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.audio.AudioOutput
import androidx.media3.exoplayer.audio.AudioOutputProvider
import androidx.media3.exoplayer.audio.AudioOutputProvider.FormatConfig
import androidx.media3.exoplayer.audio.AudioOutputProvider.FormatSupport
import androidx.media3.exoplayer.audio.AudioOutputProvider.Listener
import androidx.media3.exoplayer.audio.AudioOutputProvider.OutputConfig
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet
import moe.rukamori.archivetune.playback.DecodedPcmFormat

/**
 * Routes the standard sink's output into an exclusive AAudio stream on a connected USB DAC, past
 * the Android mixer, and hands everything else to [inner] untouched.
 *
 * Only 16-bit and float PCM qualify: with float output off, Media3's standard sink transcodes
 * every other integer depth to 16-bit before it asks the provider, so nothing else ever arrives.
 * The stream is opened here rather than in the output so that a refusal (exclusive not granted,
 * a different rate or channel count granted, no AAudio) falls back to [inner] before Media3
 * holds an output. A refused device stays on [inner] until a USB device comes or goes.
 */
class UsbExclusiveAudioOutputProvider(
    context: Context,
    private val inner: AudioOutputProvider,
    private val exclusiveWanted: () -> Boolean,
    private val chainProcessing: () -> Boolean,
    private val decodedFormat: () -> DecodedPcmFormat?,
) : AudioOutputProvider {
    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private val listeners = CopyOnWriteArraySet<Listener>()
    private val refusedDeviceIds: MutableSet<Int> = ConcurrentHashMap.newKeySet<Int>()

    @Volatile
    private var lastExclusiveConfig: OutputConfig? = null

    @Volatile
    private var current: AaudioExclusiveAudioOutput? = null

    private val deviceCallback =
        object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
                if (addedDevices.any { it.isUsbSink() }) onUsbDevicesChanged()
            }

            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
                if (removedDevices.any { it.isUsbSink() }) onUsbDevicesChanged()
            }
        }

    init {
        runCatching { audioManager?.registerAudioDeviceCallback(deviceCallback, Handler(Looper.getMainLooper())) }
            .onFailure { Log.w(TAG, "USB device callback unavailable", it) }
    }

    private fun onUsbDevicesChanged() {
        refusedDeviceIds.clear()
        listeners.forEach { it.onFormatSupportChanged() }
    }

    private fun AudioDeviceInfo.isUsbSink(): Boolean = isSink && type in USB_SINK_TYPES

    private fun currentUsbDevice(): AudioDeviceInfo? {
        val manager = audioManager ?: return null
        val usb =
            runCatching { manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS) }
                .getOrNull()
                ?.firstOrNull { it.isUsbSink() }
                ?: return null
        // Media follows the most recent route: a USB DAC that is plugged in but not where music
        // currently goes (Bluetooth connected later, say) is left alone.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val routed =
                runCatching { manager.getAudioDevicesForAttributes(MEDIA_ATTRIBUTES) }.getOrNull().orEmpty()
            if (routed.isNotEmpty() && routed.none { it.type in USB_SINK_TYPES }) return null
        }
        return usb
    }

    private fun exclusiveDeviceFor(formatConfig: FormatConfig): AudioDeviceInfo? {
        if (!exclusiveWanted() || !FloatDsp.available) return null
        if (formatConfig.enableOffload || formatConfig.enableTunneling) return null
        val format = formatConfig.format
        if (format.sampleMimeType != MimeTypes.AUDIO_RAW) return null
        if (format.pcmEncoding != C.ENCODING_PCM_16BIT && format.pcmEncoding != C.ENCODING_PCM_FLOAT) return null
        if (format.channelCount !in 1..MAX_EXCLUSIVE_CHANNELS || format.sampleRate <= 0) return null
        val device = currentUsbDevice() ?: return null
        return device.takeIf { it.id !in refusedDeviceIds }
    }

    override fun getFormatSupport(formatConfig: FormatConfig): FormatSupport =
        if (exclusiveDeviceFor(formatConfig) != null) {
            FormatSupport.Builder()
                .setFormatSupportLevel(AudioOutputProvider.FORMAT_SUPPORTED_DIRECTLY)
                .build()
        } else {
            inner.getFormatSupport(formatConfig)
        }

    override fun getOutputConfig(formatConfig: FormatConfig): OutputConfig {
        if (exclusiveDeviceFor(formatConfig) == null) {
            lastExclusiveConfig = null
            return inner.getOutputConfig(formatConfig)
        }
        val format = formatConfig.format
        val encoding = format.pcmEncoding
        val channels = format.channelCount
        val sampleRate = format.sampleRate
        // Also a valid AudioTrack configuration, so a refused open can hand it to [inner] as is.
        val config =
            OutputConfig.Builder()
                .setSampleRate(sampleRate)
                .setChannelMask(channelMaskFor(channels))
                .setEncoding(encoding)
                .setBufferSize((sampleRate / 4) * Util.getPcmFrameSize(encoding, channels))
                .setAudioSessionId(formatConfig.audioSessionId)
                .setAudioAttributes(formatConfig.audioAttributes)
                .setVirtualDeviceId(formatConfig.virtualDeviceId)
                .setIsOffload(false)
                .setIsTunneling(false)
                .setUsePlaybackParameters(false)
                .setUseOffloadGapless(false)
                .build()
        lastExclusiveConfig = config
        // A reused output (same config, next track) never comes back through getAudioOutput.
        current?.let { publishEngaged(config, sampleRate) }
        return config
    }

    override fun getAudioOutput(config: OutputConfig): AudioOutput {
        // The previous output is being replaced; it must let go of the device before the new
        // stream asks for it exclusively, and Media3 may release it later on another thread.
        val previous = current
        current = null
        previous?.closeStreamNow()
        val device = currentUsbDevice()
        if (config != lastExclusiveConfig || !exclusiveWanted() || device == null || device.id in refusedDeviceIds) {
            publishDisengaged()
            return inner.getAudioOutput(config)
        }

        val channels = Integer.bitCount(config.channelMask).coerceIn(1, MAX_EXCLUSIVE_CHANNELS)
        val stream = AaudioNativeStream()
        val result =
            runCatching {
                stream.open(
                    sampleRate = config.sampleRate,
                    channels = channels,
                    deviceId = device.id,
                    exclusive = true,
                    bufferCapacityFrames = config.sampleRate / 4,
                )
            }.getOrElse {
                Log.w(TAG, "Exclusive AAudio open threw", it)
                AaudioNativeStream.ERROR_INTERNAL
            }
        val granted =
            result == 0 &&
                stream.isExclusive() &&
                stream.sampleRate() == config.sampleRate &&
                stream.channelCount() == channels
        if (!granted) {
            Log.w(
                TAG,
                "Exclusive AAudio refused on device ${device.id} (result=$result rate=${stream.sampleRate()} " +
                    "ch=${stream.channelCount()} wanted ${config.sampleRate}Hz/$channels); using the standard output",
            )
            stream.release()
            refusedDeviceIds.add(device.id)
            publishDisengaged()
            return inner.getAudioOutput(config)
        }
        Log.i(
            TAG,
            "Exclusive AAudio engaged on ${device.productName}: ${config.sampleRate}Hz/${channels}ch " +
                "in=${AudioEngineRouterProcessor.encodingName(config.encoding)} buffer=${stream.bufferSizeFrames()}",
        )
        val output =
            AaudioExclusiveAudioOutput(
                config = config,
                stream = stream,
                onWriteFailed = {
                    refusedDeviceIds.add(device.id)
                    publishDisengaged()
                },
                onReleased = { released ->
                    if (current === released) {
                        current = null
                        publishDisengaged()
                    }
                },
            )
        current = output
        publishEngaged(config, stream.sampleRate())
        return output
    }

    override fun addListener(listener: Listener) {
        listeners.add(listener)
        inner.addListener(listener)
    }

    override fun removeListener(listener: Listener) {
        listeners.remove(listener)
        inner.removeListener(listener)
    }

    override fun setClock(clock: androidx.media3.common.util.Clock) {
        inner.setClock(clock)
    }

    override fun release() {
        runCatching { audioManager?.unregisterAudioDeviceCallback(deviceCallback) }
        current?.closeStreamNow()
        current = null
        listeners.clear()
        inner.release()
        publishDisengaged()
    }

    private fun publishEngaged(config: OutputConfig, rate: Int) {
        val bits = BitPerfectRuntime.bitDepthOf(config.encoding)
        runCatching {
            val decoded = decodedFormat()
            BitPerfectRuntime.clearTrack()
            BitPerfectRuntime.evaluateTrack(
                context = appContext,
                inputEncoding = decoded?.encoding ?: config.encoding,
                inputSampleRate = decoded?.sampleRateHz?.takeIf { it > 0 } ?: rate,
                inputChannels = decoded?.channelCount?.takeIf { it > 0 } ?: Integer.bitCount(config.channelMask),
                engineOrDspEngaged = chainProcessing(),
                usbExclusive = true,
                outputSampleRateHz = rate,
            )
            BitPerfectRuntime.notifyUsbExclusive(
                active = true,
                rate = rate,
                bits = bits,
                outputEncoding = config.encoding,
            )
        }.onFailure { Log.w(TAG, "Publishing the exclusive route failed", it) }
        EngineRuntime.publishUsbExclusive(active = true, rateHz = rate, bits = bits)
    }

    private fun publishDisengaged() {
        if (!EngineRuntime.usbExclusiveActive && !BitPerfectRuntime.status.usbExclusiveActive) return
        // Nothing else publishes BitPerfectRuntime on the standard path, so the sheet goes back to
        // hiding the pipeline row rather than showing this route's last numbers.
        BitPerfectRuntime.clearTrack()
        EngineRuntime.publishUsbExclusive(active = false)
    }

    private fun channelMaskFor(channels: Int): Int {
        val mask = Util.getAudioTrackChannelConfig(channels)
        return if (mask != AudioFormat.CHANNEL_INVALID) mask else AudioFormat.CHANNEL_OUT_STEREO
    }

    companion object {
        private const val TAG = "UsbExclusiveAudio"
        private const val MAX_EXCLUSIVE_CHANNELS = 2

        private val USB_SINK_TYPES =
            intArrayOf(
                AudioDeviceInfo.TYPE_USB_DEVICE,
                AudioDeviceInfo.TYPE_USB_HEADSET,
                AudioDeviceInfo.TYPE_USB_ACCESSORY,
            )

        private val MEDIA_ATTRIBUTES: AudioAttributes =
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()

        /** Whether the running service built its players with this provider; set by MusicService. */
        @Volatile
        var installed: Boolean = false
    }
}
