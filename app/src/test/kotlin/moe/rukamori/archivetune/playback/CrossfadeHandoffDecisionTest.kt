/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Decision table for the pure crossfade hand-off policy, including the regression case from
 * the user log (archivetune-log-1791893938310: outgoing ended at fade progress 0.993, the
 * incoming player paused by the old onIsPlayingChanged branch, and the failure recovery
 * restarted the next song from 0:00).
 */
class CrossfadeHandoffDecisionTest {
    private fun snapshot(
        playbackRequested: Boolean = true,
        outgoingRanOut: Boolean = false,
        progress: Float = 0f,
        incomingState: CrossfadeIncomingState = CrossfadeIncomingState.READY_PLAYING,
        incomingItemMatchesTarget: Boolean = true,
        incomingHeardMs: Long = 0L,
        stallElapsedMs: Long = 0L,
    ) = CrossfadeHandoffSnapshot(
        playbackRequested = playbackRequested,
        outgoingRanOut = outgoingRanOut,
        progress = progress,
        incomingState = incomingState,
        incomingItemMatchesTarget = incomingItemMatchesTarget,
        incomingHeardMs = incomingHeardMs,
        stallElapsedMs = stallElapsedMs,
    )

    @Test
    fun logRegression_outgoingEndedAndIncomingPaused_promotesIncoming() {
        assertEquals(
            CrossfadeHandoffAction.PROMOTE,
            CrossfadeHandoffPolicy.decide(
                snapshot(
                    outgoingRanOut = true,
                    progress = 0.993f,
                    incomingState = CrossfadeIncomingState.READY_PAUSED,
                ),
            ),
        )
    }

    @Test
    fun outgoingRanOutWithBufferingIncoming_promotesIncoming() {
        assertEquals(
            CrossfadeHandoffAction.PROMOTE,
            CrossfadeHandoffPolicy.decide(
                snapshot(outgoingRanOut = true, progress = 0.9f, incomingState = CrossfadeIncomingState.BUFFERING),
            ),
        )
        assertEquals(
            CrossfadeHandoffAction.PROMOTE,
            CrossfadeHandoffPolicy.decide(
                snapshot(outgoingRanOut = true, progress = 0.9f, incomingState = CrossfadeIncomingState.READY_PLAYING),
            ),
        )
    }

    @Test
    fun committedFade_withErroredIncomingThatWasHeard_carriesPositionToPrimary() {
        assertEquals(
            CrossfadeHandoffAction.CARRY_TO_PRIMARY,
            CrossfadeHandoffPolicy.decide(
                snapshot(progress = 0.8f, incomingState = CrossfadeIncomingState.ERROR, incomingHeardMs = 4_200L),
            ),
        )
        assertEquals(
            CrossfadeHandoffAction.CARRY_TO_PRIMARY,
            CrossfadeHandoffPolicy.decide(
                snapshot(
                    outgoingRanOut = true,
                    progress = 0.993f,
                    incomingState = CrossfadeIncomingState.ENDED,
                    incomingHeardMs = 4_950L,
                ),
            ),
        )
    }

    @Test
    fun committedFade_withNonTerminalIncoming_neverRestoresOutgoing() {
        val nonTerminalStates =
            listOf(
                CrossfadeIncomingState.READY_PLAYING,
                CrossfadeIncomingState.READY_PAUSED,
                CrossfadeIncomingState.BUFFERING,
            )
        // committed by the outgoing song having run out...
        for (state in nonTerminalStates) {
            assertTrue(
                "state=$state ranOut",
                CrossfadeHandoffPolicy.decide(snapshot(outgoingRanOut = true, progress = 0.2f, incomingState = state)) !=
                    CrossfadeHandoffAction.RESTORE_OUTGOING,
            )
        }
        // ...and committed by the fade being past its midpoint
        for (state in nonTerminalStates) {
            assertTrue(
                "state=$state progress",
                CrossfadeHandoffPolicy.decide(snapshot(progress = 0.5f, incomingState = state)) !=
                    CrossfadeHandoffAction.RESTORE_OUTGOING,
            )
        }
    }

    @Test
    fun uncommittedFade_withIncomingStalledPastGrace_restoresOutgoing() {
        assertEquals(
            CrossfadeHandoffAction.RESTORE_OUTGOING,
            CrossfadeHandoffPolicy.decide(
                snapshot(
                    progress = 0.1f,
                    incomingState = CrossfadeIncomingState.BUFFERING,
                    stallElapsedMs = 800L,
                ),
            ),
        )
    }

    @Test
    fun uncommittedFade_withIncomingStalledBelowGrace_keepsWaiting() {
        assertEquals(
            CrossfadeHandoffAction.CONTINUE,
            CrossfadeHandoffPolicy.decide(
                snapshot(
                    progress = 0.1f,
                    incomingState = CrossfadeIncomingState.BUFFERING,
                    stallElapsedMs = 800L,
                    // streamed/DRM grace is wider, so the same stall keeps waiting
                ),
                uncommittedStallGraceMs = CrossfadeHandoffPolicy.UNCOMMITTED_STALL_GRACE_STREAMED_MS,
            ),
        )
    }

    @Test
    fun committedFade_withStalledIncoming_freezesUntilStallLimitThenPromotes() {
        assertEquals(
            CrossfadeHandoffAction.CONTINUE,
            CrossfadeHandoffPolicy.decide(
                snapshot(progress = 0.6f, incomingState = CrossfadeIncomingState.BUFFERING, stallElapsedMs = 800L),
            ),
        )
        assertEquals(
            CrossfadeHandoffAction.PROMOTE,
            CrossfadeHandoffPolicy.decide(
                snapshot(progress = 0.6f, incomingState = CrossfadeIncomingState.BUFFERING, stallElapsedMs = 8_000L),
            ),
        )
    }

    @Test
    fun erroredIncomingBeforeCommit_restoresOutgoing() {
        assertEquals(
            CrossfadeHandoffAction.RESTORE_OUTGOING,
            CrossfadeHandoffPolicy.decide(
                snapshot(progress = 0.2f, incomingState = CrossfadeIncomingState.ERROR, incomingHeardMs = 400L),
            ),
        )
    }

    @Test
    fun erroredIncomingAfterOutgoingRanOutButBarelyHeard_restoresOutgoing() {
        // The caller must then seek to the cue, not 0 — the heard time is below the carry
        // minimum, so restarting at the cue is indistinguishable from carrying.
        assertEquals(
            CrossfadeHandoffAction.RESTORE_OUTGOING,
            CrossfadeHandoffPolicy.decide(
                snapshot(
                    outgoingRanOut = true,
                    progress = 0.993f,
                    incomingState = CrossfadeIncomingState.ERROR,
                    incomingHeardMs = 900L,
                ),
            ),
        )
    }

    @Test
    fun userPause_withNonTerminalIncoming_freezesTheBlend() {
        for (state in
            listOf(
                CrossfadeIncomingState.READY_PLAYING,
                CrossfadeIncomingState.READY_PAUSED,
                CrossfadeIncomingState.BUFFERING,
            )) {
            assertEquals(
                "state=$state",
                CrossfadeHandoffAction.CONTINUE,
                CrossfadeHandoffPolicy.decide(snapshot(playbackRequested = false, progress = 0.3f, incomingState = state)),
            )
        }
    }

    @Test
    fun incomingItemNotMatchingTarget_restoresOutgoing() {
        assertEquals(
            CrossfadeHandoffAction.RESTORE_OUTGOING,
            CrossfadeHandoffPolicy.decide(
                snapshot(outgoingRanOut = true, progress = 0.9f, incomingItemMatchesTarget = false),
            ),
        )
    }

    @Test
    fun terminalIncomingStatesAreTheErrorIdleAndEndedOnes() {
        assertTrue(CrossfadeHandoffPolicy.isPromotableIncoming(CrossfadeIncomingState.BUFFERING))
        assertTrue(CrossfadeHandoffPolicy.isPromotableIncoming(CrossfadeIncomingState.READY_PLAYING))
        assertTrue(CrossfadeHandoffPolicy.isPromotableIncoming(CrossfadeIncomingState.READY_PAUSED))
        assertTrue(!CrossfadeHandoffPolicy.isPromotableIncoming(CrossfadeIncomingState.ERROR))
        assertTrue(!CrossfadeHandoffPolicy.isPromotableIncoming(CrossfadeIncomingState.IDLE))
        assertTrue(!CrossfadeHandoffPolicy.isPromotableIncoming(CrossfadeIncomingState.ENDED))
    }

    @Test
    fun streamedSourcesGetTheWiderStallGrace() {
        assertEquals(
            CrossfadeHandoffPolicy.UNCOMMITTED_STALL_GRACE_STREAMED_MS,
            CrossfadeHandoffPolicy.uncommittedStallGraceMs(streamedOrDrmSource = true),
        )
        assertEquals(
            CrossfadeHandoffPolicy.UNCOMMITTED_STALL_GRACE_MS,
            CrossfadeHandoffPolicy.uncommittedStallGraceMs(streamedOrDrmSource = false),
        )
    }

    @Test
    fun monotonicity_increasingProgressNeverTurnsHealthyDecisionIntoRestore() {
        val nonTerminalStates =
            listOf(
                CrossfadeIncomingState.READY_PLAYING,
                CrossfadeIncomingState.READY_PAUSED,
                CrossfadeIncomingState.BUFFERING,
            )
        for (state in nonTerminalStates) {
            var previous = CrossfadeHandoffPolicy.decide(snapshot(progress = 0f, incomingState = state))
            var progress = 0.05f
            while (progress <= 1f) {
                val current =
                    CrossfadeHandoffPolicy.decide(
                        snapshot(progress = progress, incomingState = state, stallElapsedMs = 100L),
                    )
                // The only allowed direction is RESTORE (early, uncommitted stall) to
                // CONTINUE/PROMOTE as the fade commits — never the other way around.
                if (previous != CrossfadeHandoffAction.RESTORE_OUTGOING) {
                    assertTrue(
                        "state=$state progress=$progress changed $previous into $current",
                        current != CrossfadeHandoffAction.RESTORE_OUTGOING,
                    )
                }
                previous = current
                progress += 0.05f
            }
        }
    }
}
