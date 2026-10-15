/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © LastWave-native — github.com/Clash-Projects/LastWave-native
 */

package com.lastwave.app.playback

import android.media.AudioManager
import android.media.AudioTrack
import moe.rukamori.archivetune.R

data class UsbDacInfo(
    val name: String,
    val vendorId: Int = -1,
    val productId: Int = -1,

    val sampleRatesHz: List<Int> = emptyList(),
    val channelCounts: List<Int> = emptyList(),

    val usbPermissionGranted: Boolean = false,

    val hasUsbPeripheral: Boolean = false,

    val deviceId: Int = -1,
)

data class SignalPathInput(
    val sourceLabel: String,
    val sourceRateHz: Int?,
    val sourceBitDepth: Int?,
    val isLossless: Boolean,

    val appOutputRateHz: Int,

    val platformMixerRateHz: Int,
    val dspBypassEnabled: Boolean,
    val crossfadeMixing: Boolean,
    val speed: Float,

    val appVolume: Float,
    val systemVolume: Int,
    val systemVolumeMax: Int,

    val systemVolumeFixed: Boolean,
    val dac: UsbDacInfo?,
    val routedToDac: Boolean,

    val routeVerified: Boolean,
    val driftPpm: Double?,
    val glitchCount: Long,
    val isPlaying: Boolean,
    val platformBitPerfectConfigured: Boolean = false,

    val usbExclusiveActive: Boolean = false,

    val exclusiveClockMatched: Boolean = false,

    val exclusiveHardwareVolume: Boolean = false,

    val exclusiveFailureReason: String? = null,

    val clockFallbackResampled: Boolean = false,
)

data class PathCheck(
    val labelRes: Int,
    val detailRes: Int,
    val detailArgs: List<Any> = emptyList(),
    val passed: Boolean,
)

data class SignalPathReport(
    val checks: List<PathCheck>,
    val bitPerfect: Boolean,
    val sourceLabel: String,
    val sourceRateHz: Int?,
    val appRateHz: Int,
    val platformRateHz: Int,
    val dacName: String?,
    val driftPpm: Double?,
    val glitchCount: Long,
    val isPlaying: Boolean,
    val usbExclusiveActive: Boolean = false,
    val clockFallbackResampled: Boolean = false,
) {
    companion object {
        fun initial() = SignalPathReport(
            checks = listOf(
                PathCheck(R.string.signal_stream, R.string.signal_detail_no_stream, passed = false),
            ),
            bitPerfect = false,
            sourceLabel = "—",
            sourceRateHz = null,
            appRateHz = 0,
            platformRateHz = 0,
            dacName = null,
            driftPpm = null,
            glitchCount = 0L,
            isPlaying = false,
            usbExclusiveActive = false,
            clockFallbackResampled = false,
        )
    }
}

fun AudioManager.mixerRateHz(): Int = runCatching {
    AudioTrack.getNativeOutputSampleRate(AudioManager.STREAM_MUSIC)
}.getOrDefault(0)

fun evaluateSignalPath(i: SignalPathInput): SignalPathReport {
    val checks = mutableListOf<PathCheck>()

    val src = i.sourceRateHz?.takeIf { it > 0 }
        ?: i.appOutputRateHz.takeIf { it > 0 && i.usbExclusiveActive }
    val labelBitDepth = Regex("""(?:^|[^\d])(16|24|32)\s*(?:[-_]bit)?\s*[/]""", RegexOption.IGNORE_CASE)
        .find(i.sourceLabel)?.groupValues?.getOrNull(1)?.toIntOrNull()
    val rawBitDepth = labelBitDepth ?: i.sourceBitDepth?.takeIf { it > 0 }
    val bitDepth = when {
        src != null && src > 192_000 -> 32
        src != null && src > 48_000 -> 24
        rawBitDepth != null && rawBitDepth > 16 -> rawBitDepth
        i.sourceLabel.contains("HI-RES", ignoreCase = true) || i.sourceLabel.contains("HI_RES", ignoreCase = true) -> 24
        rawBitDepth != null -> rawBitDepth
        else -> null
    }
    val sourceLabel = if (bitDepth != null && src != null && src > 48_000 && i.sourceLabel.startsWith("16/")) {
        i.sourceLabel.replaceFirst("16/", "$bitDepth/")
    } else {
        i.sourceLabel
    }
    if (src != null) {
        if (bitDepth != null) {
            checks += PathCheck(
                R.string.signal_label_source,
                R.string.signal_detail_src,
                listOf(sourceLabel, bitDepth, src),
                passed = true,
            )
        } else {
            checks += PathCheck(
                R.string.signal_label_source,
                R.string.signal_detail_src_nobit,
                listOf(i.sourceLabel, src),
                passed = true,
            )
        }
    } else {
        checks += PathCheck(
            R.string.signal_label_source,
            R.string.signal_detail_src_unknown,
            passed = false,
        )
    }

    val app = i.appOutputRateHz.takeIf { it > 0 }
    if (src != null && app != null) {
        if (app == src) {
            checks += PathCheck(
                R.string.signal_label_resampler,
                R.string.signal_detail_resample_bypass,
                listOf(src),
                passed = true,
            )
        } else if (i.usbExclusiveActive && i.clockFallbackResampled) {
            checks += PathCheck(
                R.string.signal_label_resampler,
                R.string.signal_detail_resample_dac_unsupported,
                listOf(src, app),
                passed = false,
            )
        } else {
            checks += PathCheck(
                R.string.signal_label_resampler,
                R.string.signal_detail_resample_active,
                listOf(src, app),
                passed = false,
            )
        }
    } else {
        checks += PathCheck(
            R.string.signal_label_resampler,
            R.string.signal_detail_resample_unresolved,
            passed = false,
        )
    }

    if (!i.dspBypassEnabled) {
        checks += PathCheck(
            R.string.signal_label_dsp,
            R.string.signal_detail_dsp_off,
            passed = false,
        )
    } else if (i.crossfadeMixing) {
        checks += PathCheck(
            R.string.signal_label_dsp,
            R.string.signal_detail_dsp_crossfade,
            passed = false,
        )
    } else {
        checks += PathCheck(
            R.string.signal_label_dsp,
            R.string.signal_detail_dsp_bypass,
            passed = true,
        )
    }

    if (i.speed == 1f) {
        checks += PathCheck(
            R.string.signal_label_tempo,
            R.string.signal_detail_tempo_ok,
            passed = true,
        )
    } else {
        checks += PathCheck(
            R.string.signal_label_tempo,
            R.string.signal_detail_tempo_active,
            listOf(i.speed.toString()),
            passed = false,
        )
    }

    if (i.usbExclusiveActive && i.exclusiveHardwareVolume) {
        checks += PathCheck(
            R.string.signal_label_appvol,
            R.string.signal_detail_usb_hw_vol,
            passed = true,
        )
    } else if (i.appVolume == 1f) {
        checks += PathCheck(
            R.string.signal_label_appvol,
            R.string.signal_detail_gain_unity,
            passed = true,
        )
    } else {
        checks += PathCheck(
            R.string.signal_label_appvol,
            R.string.signal_detail_gain_scaled,
            listOf(i.appVolume.toString()),
            passed = false,
        )
    }

    if (i.usbExclusiveActive && i.exclusiveHardwareVolume) {
        checks += PathCheck(
            R.string.signal_label_sysvol,
            R.string.signal_detail_usb_hw_vol,
            passed = true,
        )
    } else if (i.systemVolumeFixed) {
        checks += PathCheck(
            R.string.signal_label_sysvol,
            R.string.signal_detail_sysvol_hw,
            passed = true,
        )
    } else if (i.systemVolumeMax > 0 && i.systemVolume >= i.systemVolumeMax) {
        checks += PathCheck(
            R.string.signal_label_sysvol,
            R.string.signal_detail_sysvol_max,
            passed = true,
        )
    } else if (i.systemVolumeMax > 0) {
        checks += PathCheck(
            R.string.signal_label_sysvol,
            R.string.signal_detail_sysvol_scaled,
            listOf(i.systemVolume, i.systemVolumeMax),
            passed = false,
        )
    } else {
        checks += PathCheck(
            R.string.signal_label_sysvol,
            R.string.signal_detail_unreadable,
            passed = false,
        )
    }

    val plat = i.platformMixerRateHz.takeIf { it > 0 }
    if (i.usbExclusiveActive) {
        checks += PathCheck(
            R.string.signal_label_mixer,
            R.string.signal_detail_usb_exclusive,
            passed = true,
        )
    } else if (i.platformBitPerfectConfigured) {
        checks += PathCheck(
            R.string.signal_label_mixer,
            R.string.signal_detail_mixer_not_exclusive,
            passed = false,
        )
    } else if (app != null && plat != null) {
        if (plat == app) {
            checks += PathCheck(
                R.string.signal_label_mixer,
                R.string.signal_detail_bit_perfect_unavailable,
                listOf(plat),
                passed = false,
            )
        } else {
            checks += PathCheck(
                R.string.signal_label_mixer,
                R.string.signal_detail_mixer_src,
                listOf(app, plat),
                passed = false,
            )
        }
    } else {
        checks += PathCheck(
            R.string.signal_label_mixer,
            R.string.signal_detail_mixer_unreadable,
            passed = false,
        )
    }

    val dac = i.dac
    when {
        dac == null -> checks += PathCheck(
            R.string.signal_label_output,
            R.string.signal_detail_no_dac,
            passed = false,
        )
        !i.routedToDac -> checks += PathCheck(
            R.string.signal_label_output,
            R.string.signal_detail_not_routed,
            listOf(dac.name),
            passed = false,
        )
        i.usbExclusiveActive ->
            checks += PathCheck(
                R.string.signal_label_output,
                R.string.signal_detail_usb_exclusive_route,
                listOf(dac.name),
                passed = true,
            )
        app != null && dac.sampleRatesHz.isNotEmpty() && app in dac.sampleRatesHz ->
            checks += PathCheck(
                R.string.signal_label_output,
                R.string.signal_detail_usb_ok_accepts,
                listOf(dac.name, app),
                passed = true,
            )
        app != null && dac.sampleRatesHz.isNotEmpty() ->
            checks += PathCheck(
                R.string.signal_label_output,
                R.string.signal_detail_usb_ok_rejects,
                listOf(dac.name, app),
                passed = false,
            )
        else -> checks += PathCheck(
            R.string.signal_label_output,
            R.string.signal_detail_usb_ok_plain,
            listOf(dac.name),
            passed = false,
        )
    }

    when {
        !i.usbExclusiveActive && i.exclusiveFailureReason != null -> checks += PathCheck(
            R.string.signal_label_output,
            R.string.signal_detail_exclusive_failed,
            listOf(i.exclusiveFailureReason),
            passed = false,
        )
        !i.usbExclusiveActive -> checks += PathCheck(
            R.string.signal_label_output,
            R.string.signal_detail_route_unverified,
            passed = false,
        )
        i.clockFallbackResampled -> checks += PathCheck(
            R.string.signal_label_output,
            R.string.signal_detail_usb_clock_fallback,
            listOf(src ?: 0, app ?: 0),
            passed = false,
        )
        i.exclusiveClockMatched || i.routeVerified -> checks += PathCheck(
            R.string.signal_label_output,
            R.string.signal_detail_usb_clock_ok,
            passed = true,
        )
        else -> checks += PathCheck(
            R.string.signal_label_output,
            R.string.signal_detail_usb_clock_mismatch,
            passed = false,
        )
    }

    return SignalPathReport(
        checks = checks,
        bitPerfect = i.usbExclusiveActive &&
            !i.clockFallbackResampled &&
            (i.exclusiveClockMatched || i.routeVerified) &&
            checks.all { it.passed },
        sourceLabel = i.sourceLabel,
        sourceRateHz = src,
        appRateHz = app ?: 0,
        platformRateHz = plat ?: 0,
        dacName = dac?.name,
        driftPpm = i.driftPpm,
        glitchCount = i.glitchCount,
        isPlaying = i.isPlaying,
        usbExclusiveActive = i.usbExclusiveActive,
        clockFallbackResampled = i.clockFallbackResampled,
    )
}

class StreamHealthTracker {
    var driftPpm: Double? = null
        private set
    var glitchCount: Long = 0L
        private set

    private var lastPositionMs = -1L
    private var lastWallMs = 0L
    private var exclusiveOriginFrames = -1L
    private var exclusiveOriginWallMs = 0L

    fun reset() {
        driftPpm = null
        lastPositionMs = -1L
        lastWallMs = 0L
        exclusiveOriginFrames = -1L
        exclusiveOriginWallMs = 0L
    }

    fun sample(positionMs: Long, wallMs: Long, playing: Boolean): Double? {
        if (!playing || positionMs < 0) {
            lastPositionMs = -1L
            if (!playing) driftPpm = null
            return driftPpm
        }
        if (lastPositionMs < 0) {
            lastPositionMs = positionMs
            lastWallMs = wallMs
            return driftPpm
        }
        val wallDelta = wallMs - lastWallMs
        val posDelta = positionMs - lastPositionMs
        lastPositionMs = positionMs
        lastWallMs = wallMs
        if (wallDelta < 400L || wallDelta > 3_000L) return driftPpm
        if (kotlin.math.abs(posDelta - wallDelta) > 1_500L) {
            if (posDelta < -250L) glitchCount++
            return driftPpm
        }
        if (posDelta <= 0L) {
            if (wallDelta >= 800L) glitchCount++
            return driftPpm
        }
        val instant = (posDelta - wallDelta).toDouble() / wallDelta * 1_000_000.0
        driftPpm = if (driftPpm == null) instant else driftPpm!! * 0.85 + instant * 0.15
        return driftPpm
    }

    fun sampleExclusive(framesWritten: Long, rateHz: Int, wallMs: Long, playing: Boolean): Double? {
        if (!playing) {
            exclusiveOriginFrames = -1L
            driftPpm = null
            return null
        }
        if (rateHz <= 0 || framesWritten < 0L) {
            exclusiveOriginFrames = -1L
            return driftPpm
        }
        if (exclusiveOriginFrames < 0L || framesWritten < exclusiveOriginFrames) {
            exclusiveOriginFrames = framesWritten
            exclusiveOriginWallMs = wallMs
            return driftPpm
        }
        val wallDelta = wallMs - exclusiveOriginWallMs
        if (wallDelta < 500L) return driftPpm
        val audioMs = (framesWritten - exclusiveOriginFrames).toDouble() * 1000.0 / rateHz.toDouble()
        if (audioMs < 200.0) return driftPpm
        val instant = ((audioMs - wallDelta) / wallDelta * 1_000_000.0)
            .coerceIn(-50_000.0, 50_000.0)
        driftPpm = if (driftPpm == null) instant else driftPpm!! * 0.85 + instant * 0.15
        return driftPpm
    }
}
