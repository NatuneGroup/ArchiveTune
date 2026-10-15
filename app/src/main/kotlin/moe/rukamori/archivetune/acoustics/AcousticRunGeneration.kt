/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.acoustics

/** Accessed under the controller's lock, alongside job and resource ownership. */
internal class AcousticRunGeneration {
    private var generation = 0L
    private var active = true
    var closed = false
        private set

    val canStart: Boolean get() = !closed && active

    fun begin(): Long {
        check(canStart)
        return ++generation
    }

    fun accepts(token: Long): Boolean = canStart && token == generation

    fun cancel() {
        generation++
    }

    fun setActive(value: Boolean) {
        active = value
    }

    fun close() {
        closed = true
    }
}
