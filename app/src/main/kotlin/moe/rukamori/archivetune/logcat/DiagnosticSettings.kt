/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.logcat

internal val diagnosticPreferenceNames = listOf(
    "audioSourceOrder", "audioQuality", "autoChoosePlaybackClient", "playbackClient",
    "tidalEnabled", "tidalAudioQuality", "deezerEnabled", "deezerAudioQuality", "qobuzEnabled", "qobuzAudioQuality",
    "appleMusicSourceEnabled", "appleMusicQuality", "amazonMusicEnabled", "qqMusicEnabled", "qqMusicAudioQuality",
    "networkMetered", "preloadLowDataMode", "audioOffload", "bitPerfectUsbOutput",
    "crossfadeEnabled", "crossfadeDuration", "crossfadeGapless", "automixEnabled", "automixPerformanceMode",
    "skipSilence", "audioNormalization", "wakelock", "playerDesignStyle", "lyricsAnimationStyle",
    "apple_music_experience", "liquidGlassEnabled", "homeSource", "navigationBarStyle", "miniPlayerBackgroundStyle",
)

internal fun diagnosticPreferences(values: Map<String, Any>): String =
    diagnosticPreferenceNames.joinToString("\n") { name ->
        "$name=${DiagnosticRedaction.redact(values[name]?.toString() ?: "default (unset)")}"
    }
