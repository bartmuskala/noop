package com.noop.analytics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The fitted Effort curve (#2438 step 1) must reproduce the example days published with the proposal,
 * on the 0–21 axis, from minutes spent at each zone's midpoint (55/65/75/85/95 %HRR).
 */
class FittedEffortCurveTest {

    private val mids = listOf(55.0, 65.0, 75.0, 85.0, 95.0)

    private fun day(vararg minutesPerZone: Double): Double =
        StrainScorer.fittedCurve21(minutesPerZone.indices.sumOf { StrainScorer.fittedWeight(mids[it]) * minutesPerZone[it] })

    @Test
    fun reproducesThePublishedExampleDays() {
        assertEquals(6.7, day(30.0, 0.0, 0.0, 0.0, 0.0), 0.1)
        assertEquals(15.1, day(0.0, 0.0, 60.0, 0.0, 0.0), 0.1)
        assertEquals(17.5, day(0.0, 0.0, 0.0, 60.0, 0.0), 0.1)
        assertEquals(18.2, day(0.0, 20.0, 30.0, 30.0, 10.0), 0.1)
        assertEquals(20.4, day(0.0, 0.0, 30.0, 150.0, 30.0), 0.1)
        assertEquals(20.6, day(0.0, 300.0, 300.0, 0.0, 0.0), 0.1)
    }

    @Test
    fun nothingBelowTheFloorAndNeverTwentyOne() {
        assertEquals(0.0, StrainScorer.fittedWeight(49.9), 0.0)
        assertEquals(0.0, StrainScorer.fittedCurve21(0.0), 0.0)
        assertTrue(StrainScorer.fittedCurve21(1e9) < 21.0)
    }

    @Test
    fun storedAxisIsTheTwentyOneAxisRescaled() {
        val load = 120.0
        assertEquals(StrainScorer.fittedCurve21(load) * 100.0 / 21.0, StrainScorer.fittedEffort(load), 0.01)
    }
}
