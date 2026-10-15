/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.playback

import kotlin.math.roundToInt

data class MusicVolumeRange(val min: Int, val max: Int) {
    private val span get() = (max - min).coerceAtLeast(1)

    fun fraction(level: Int): Float = ((level - min).toFloat() / span).coerceIn(0f, 1f)

    fun level(fraction: Float): Int {
        val safe = fraction.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f
        val rounded = (min + (safe * span).roundToInt()).coerceIn(min, max)
        return if (safe > 0f && rounded <= min) (min + 1).coerceAtMost(max) else rounded
    }
}

enum class VolumeInteractionPhase { Idle, Dragging, AwaitingConfirmation }

/** Pure device-domain presentation state. Observations are retained even while intent owns display. */
class VolumeInteractionState(range: MusicVolumeRange, initialLevel: Int) {
    var range = range
        private set
    var observedLevel = initialLevel
        private set
    val observedFraction get() = range.fraction(observedLevel)
    var displayFraction = observedFraction
        private set
    var phase = VolumeInteractionPhase.Idle
        private set
    var generation = 0L
        private set
    var expectedLevel: Int? = null
        private set
    var confirmationDeadline: Long? = null
        private set
    private var lastCommandLevel: Int? = null

    fun start() {
        generation++
        phase = VolumeInteractionPhase.Dragging
        expectedLevel = null
        confirmationDeadline = null
        lastCommandLevel = null
    }

    /** Returns only a changed integer command; every finite change still updates display immediately. */
    fun change(fraction: Float): Int? {
        if (!fraction.isFinite()) return null
        if (phase == VolumeInteractionPhase.Idle) start()
        displayFraction = fraction.coerceIn(0f, 1f)
        val level = range.level(displayFraction)
        expectedLevel = level
        if (lastCommandLevel == level) return null
        generation++
        lastCommandLevel = level
        return level
    }

    fun finish(nowMillis: Long, graceMillis: Long = 350L): Long {
        phase = VolumeInteractionPhase.AwaitingConfirmation
        confirmationDeadline = nowMillis + graceMillis
        return generation
    }

    /**
     * A callback has no platform command ID. Only a fresh end/timeout read may confirm the final
     * command, never a queued observer notification or a read taken during an earlier drag.
     */
    fun observe(level: Int, range: MusicVolumeRange = this.range, fresh: Boolean = false) {
        this.range = range
        observedLevel = level
        if (phase == VolumeInteractionPhase.Idle ||
            (phase == VolumeInteractionPhase.AwaitingConfirmation && fresh && level == expectedLevel)
        ) settle()
    }

    fun expire(generation: Long, nowMillis: Long) {
        if (generation == this.generation && phase == VolumeInteractionPhase.AwaitingConfirmation &&
            nowMillis >= (confirmationDeadline ?: Long.MAX_VALUE)
        ) settle()
    }

    fun reset() {
        generation++
        settle()
        lastCommandLevel = null
    }

    private fun settle() {
        phase = VolumeInteractionPhase.Idle
        displayFraction = observedFraction
        expectedLevel = null
        confirmationDeadline = null
        lastCommandLevel = null
    }
}
