/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.playback

internal suspend fun cleanupCrossfadeLoopFailure(
    handoff: suspend (incomingUnusable: Boolean) -> Unit,
    cancel: () -> Unit,
    clearMixing: () -> Unit,
    clearPauseAtEnd: () -> Unit,
    restoreSkipSilence: () -> Unit,
) {
    // A failed promotion must re-enter the policy as unusable, preserving the committed
    // incoming position. Do not let a second cleanup failure skip the surviving player's setting.
    val handedOff = runCatching { handoff(false) }.isSuccess ||
        runCatching { handoff(true) }.isSuccess
    if (!handedOff) {
        runCatching(cancel)
        runCatching(clearMixing)
        runCatching(clearPauseAtEnd)
    }
    runCatching(restoreSkipSilence)
}
