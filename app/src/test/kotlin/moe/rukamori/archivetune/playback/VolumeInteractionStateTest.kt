/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The slider shows the finger's intent while dragging and during a short grace period afterwards;
 * stale observer callbacks must not snap it back, and only a fresh read or the timeout settles it.
 */
class VolumeInteractionStateTest {
    private val range = MusicVolumeRange(min = 0, max = 15)

    @Test
    fun range_mapsLevelsAndFractionsWithClamping() {
        assertEquals(0f, range.fraction(0), 0f)
        assertEquals(1f, range.fraction(15), 0f)
        assertEquals(1f, range.fraction(40), 0f)
        assertEquals(0, range.level(0f))
        assertEquals(15, range.level(1f))
        assertEquals(15, range.level(2f))
        assertEquals(0, range.level(Float.NaN))
        assertEquals(8, range.level(0.5f))
    }

    @Test
    fun range_tinyPositiveFractionNeverMutes() {
        assertEquals(1, range.level(0.01f))
        assertEquals(3, MusicVolumeRange(min = 2, max = 10).level(0.001f))
    }

    @Test
    fun range_nonZeroMinimum() {
        val offset = MusicVolumeRange(min = 1, max = 7)

        assertEquals(0f, offset.fraction(1), 0f)
        assertEquals(1f, offset.fraction(7), 0f)
        assertEquals(1, offset.level(0f))
        assertEquals(7, offset.level(1f))
    }

    @Test
    fun idleObservation_updatesDisplay() {
        val state = VolumeInteractionState(range, initialLevel = 5)

        state.observe(9)

        assertEquals(VolumeInteractionPhase.Idle, state.phase)
        assertEquals(range.fraction(9), state.displayFraction, 0f)
    }

    @Test
    fun drag_holdsIntentAgainstOldObservations() {
        val state = VolumeInteractionState(range, initialLevel = 5)

        state.start()
        assertEquals(8, state.change(0.5f))
        state.observe(3)

        assertEquals(VolumeInteractionPhase.Dragging, state.phase)
        assertEquals(0.5f, state.displayFraction, 0f)
        assertEquals(3, state.observedLevel)
    }

    @Test
    fun drag_dedupesSameIntegerButKeepsContinuousDisplay() {
        val state = VolumeInteractionState(range, initialLevel = 5)

        state.start()
        assertEquals(8, state.change(0.5f))
        assertNull(state.change(0.51f))

        assertEquals(0.51f, state.displayFraction, 0f)
        assertEquals(8, state.expectedLevel)
    }

    @Test
    fun change_withoutStart_beginsDragAndIgnoresNonFinite() {
        val state = VolumeInteractionState(range, initialLevel = 5)

        assertNull(state.change(Float.NaN))
        assertEquals(VolumeInteractionPhase.Idle, state.phase)

        assertEquals(15, state.change(1f))
        assertEquals(VolumeInteractionPhase.Dragging, state.phase)
    }

    @Test
    fun gracePeriod_holdsIntentUntilDeadlineThenReconciles() {
        val state = VolumeInteractionState(range, initialLevel = 5)
        state.start()
        state.change(0.5f)
        val generation = state.finish(nowMillis = 1_000L)

        assertEquals(1_350L, state.confirmationDeadline)

        state.observe(8)
        assertEquals(VolumeInteractionPhase.AwaitingConfirmation, state.phase)
        assertEquals(0.5f, state.displayFraction, 0f)

        state.observe(6)
        state.expire(generation, nowMillis = 1_349L)
        assertEquals(VolumeInteractionPhase.AwaitingConfirmation, state.phase)
        assertEquals(0.5f, state.displayFraction, 0f)

        state.expire(generation, nowMillis = 1_350L)
        assertEquals(VolumeInteractionPhase.Idle, state.phase)
        assertEquals(range.fraction(6), state.displayFraction, 0f)
        assertNull(state.expectedLevel)
    }

    @Test
    fun freshMatchingRead_settlesBeforeDeadline() {
        val state = VolumeInteractionState(range, initialLevel = 5)
        state.start()
        state.change(0.5f)
        state.finish(nowMillis = 1_000L)

        state.observe(7, fresh = true)
        assertEquals(VolumeInteractionPhase.AwaitingConfirmation, state.phase)

        state.observe(8, fresh = true)
        assertEquals(VolumeInteractionPhase.Idle, state.phase)
        assertEquals(range.fraction(8), state.displayFraction, 0f)
    }

    @Test
    fun obsoleteGenerationTimeout_doesNotSettleNewDrag() {
        val state = VolumeInteractionState(range, initialLevel = 5)
        state.start()
        state.change(0.5f)
        val first = state.finish(nowMillis = 1_000L)

        state.start()
        state.change(0.9f)
        state.expire(first, nowMillis = 5_000L)

        assertEquals(VolumeInteractionPhase.Dragging, state.phase)
        assertEquals(0.9f, state.displayFraction, 0f)
    }

    @Test
    fun reset_returnsToObservedValue() {
        val state = VolumeInteractionState(range, initialLevel = 5)
        state.start()
        state.change(0.9f)

        state.reset()

        assertEquals(VolumeInteractionPhase.Idle, state.phase)
        assertEquals(range.fraction(5), state.displayFraction, 0f)
        assertNull(state.expectedLevel)
    }
}
