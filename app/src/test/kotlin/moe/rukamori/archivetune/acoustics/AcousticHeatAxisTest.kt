/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.acoustics

import org.junit.Assert.assertEquals
import org.junit.Test

class AcousticHeatAxisTest {
    @Test
    fun frequencyTicksStayAtOrBelowSixWithNiceSteps() {
        assertEquals(2, AcousticHeatAxis.niceKHzStep(8f))
        assertEquals(5, AcousticHeatAxis.niceKHzStep(22.05f))
        assertEquals(10, AcousticHeatAxis.niceKHzStep(48f))
        assertEquals(1, AcousticHeatAxis.niceKHzStep(6f))
        assertEquals(50, AcousticHeatAxis.niceKHzStep(300f))
        assertEquals(100, AcousticHeatAxis.niceKHzStep(301f))
    }

    @Test
    fun timeTicksStayAtOrBelowSixWithNiceSteps() {
        assertEquals(5, AcousticHeatAxis.niceSecondsStep(20.0))
        assertEquals(10, AcousticHeatAxis.niceSecondsStep(31.0))
        assertEquals(30, AcousticHeatAxis.niceSecondsStep(180.0))
        assertEquals(600, AcousticHeatAxis.niceSecondsStep(3_600.0))
        assertEquals(1_200, AcousticHeatAxis.niceSecondsStep(7_200.0))
    }

    @Test
    fun clockRoundsHalfAwayFromZeroAndDoesNotWrapMinutes() {
        assertEquals("0:00", AcousticHeatAxis.formatClock(0.0))
        assertEquals("1:00", AcousticHeatAxis.formatClock(59.5))
        assertEquals("59:59", AcousticHeatAxis.formatClock(3_599.4))
        assertEquals("60:00", AcousticHeatAxis.formatClock(3_600.0))
    }
}
