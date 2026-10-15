/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.acoustics

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AcousticRunGenerationTest {
    @Test
    fun cancelRejectsLateResultAndRetryAcceptsOnlyNewGeneration() {
        val generation = AcousticRunGeneration()
        assertTrue(generation.canStart)
        val old = generation.begin()
        assertTrue(generation.accepts(old))
        generation.cancel()
        assertFalse(generation.accepts(old))
        val retry = generation.begin()
        assertTrue(generation.accepts(retry))
        assertFalse(generation.accepts(old))
    }

    @Test
    fun collapseAndReactivationDoNotResurrectCancelledWork() {
        val generation = AcousticRunGeneration()
        val old = generation.begin()
        generation.setActive(false)
        generation.cancel()
        assertFalse(generation.canStart)
        assertFalse(generation.accepts(old))
        generation.setActive(true)
        assertTrue(generation.canStart)
        assertFalse(generation.accepts(old))
        assertTrue(generation.accepts(generation.begin()))
    }

    @Test
    fun closedTargetOwnerCannotPublishOrRestartAfterNewTargetOpens() {
        val oldOwner = AcousticRunGeneration()
        val old = oldOwner.begin()
        oldOwner.close()
        oldOwner.cancel()
        oldOwner.setActive(true)
        assertFalse(oldOwner.canStart)
        assertFalse(oldOwner.accepts(old))
        val newOwner = AcousticRunGeneration()
        assertTrue(newOwner.accepts(newOwner.begin()))
        assertFalse(oldOwner.accepts(old))
    }
}
