/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.playback

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CrossfadeErrorCleanupTest {
    @Test
    fun successfulHandoffDoesNotRetryOrCancelButRestoresLiveSkipSilencePreference() = runTest {
        for (preference in listOf(false, true)) {
            val events = mutableListOf<String>()
            var livePreference = !preference
            var restored: Boolean? = null
            cleanupCrossfadeLoopFailure(
                handoff = {
                    assertFalse(it)
                    events += "handoff"
                    livePreference = preference
                },
                cancel = { events += "cancel" },
                clearMixing = { events += "mixing" },
                clearPauseAtEnd = { events += "pause" },
                restoreSkipSilence = { restored = livePreference; events += "skip" },
            )
            assertEquals(listOf("handoff", "skip"), events)
            assertEquals(preference, restored)
        }
    }

    @Test
    fun failedPromotionRetriesActualHandoffPolicyAsUnusableAndCarriesHeardPosition() = runTest {
        val events = mutableListOf<Boolean>()
        val fallbackCleanup = mutableListOf<String>()
        var action: CrossfadeHandoffAction? = null
        var restored = false
        cleanupCrossfadeLoopFailure(
            handoff = { unusable ->
                events += unusable
                if (!unusable) throw IllegalStateException("promotion fixture")
                val snapshot = CrossfadeHandoffSnapshot(
                    playbackRequested = true, outgoingRanOut = true, progress = 0.993f,
                    incomingState = CrossfadeIncomingState.ERROR, incomingItemMatchesTarget = true,
                    incomingHeardMs = 5_000, stallElapsedMs = 0,
                )
                action = CrossfadeHandoffPolicy.resolveFallback(
                    CrossfadeHandoffPolicy.decide(snapshot), CrossfadeHandoffAction.RESTORE_OUTGOING, snapshot,
                )
            },
            cancel = { fallbackCleanup += "cancel" },
            clearMixing = { fallbackCleanup += "mixing" },
            clearPauseAtEnd = { fallbackCleanup += "pause" },
            restoreSkipSilence = { restored = true },
        )
        assertEquals(listOf(false, true), events)
        assertEquals(CrossfadeHandoffAction.CARRY_TO_PRIMARY, action)
        assertTrue(fallbackCleanup.isEmpty())
        assertTrue(restored)
    }

    @Test
    fun nestedThrowableCleanupFailuresStillClearFlagsAndRestoreSkipSilence() = runTest {
        val events = mutableListOf<String>()
        cleanupCrossfadeLoopFailure(
            handoff = { events += "handoff:$it"; throw AssertionError("fixture") },
            cancel = { events += "cancel"; throw AssertionError("fixture") },
            clearMixing = { events += "mixing"; throw IllegalStateException("fixture") },
            clearPauseAtEnd = { events += "pause"; throw AssertionError("fixture") },
            restoreSkipSilence = { events += "skip" },
        )
        assertEquals(listOf("handoff:false", "handoff:true", "cancel", "mixing", "pause", "skip"), events)
    }

    @Test
    fun throwingSkipSilenceSetterDoesNotEscapeExceptionalCleanup() = runTest {
        var attempts = 0
        cleanupCrossfadeLoopFailure(
            handoff = {},
            cancel = {},
            clearMixing = {},
            clearPauseAtEnd = {},
            restoreSkipSilence = { attempts++; throw IllegalStateException("fixture") },
        )
        assertEquals(1, attempts)
    }
}
