package com.noop.analytics

import com.noop.data.HrSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WxEffortCalibrationTest {
    /** A day at one-minute cadence: [easy] minutes at 95 bpm and [hard] at 140, the rest at 60. */
    private fun day(easy: Int, hard: Int): List<HrSample> {
        val bpm = List(easy) { 95 } + List(hard) { 140 } + List(1440 - easy - hard) { 60 }
        return bpm.mapIndexed { i, b -> HrSample("d", i * 60L, b) }
    }

    private fun target(hr: List<HrSample>, floor: Double, scale: Double): Double {
        val reserve = 190.0 - 50.0
        val load = hr.zip(StrainScorer.sampleDurationsMinutes(hr)).sumOf { (s, d) ->
            StrainScorer.fittedWeight(StrainScorer.pctHRR(s.bpm.toDouble(), 50.0, reserve), floor) * d
        }
        return StrainScorer.fittedCurve21(load * scale)
    }

    @Test
    fun recoversAKnownFloorAndScaleAcrossVaryingDays() {
        val shapes = listOf(60 to 0, 120 to 10, 200 to 30, 90 to 60, 300 to 0, 150 to 45, 40 to 90, 240 to 20)
        val days = shapes.map { (e, h) -> day(e, h) }.map { hr ->
            WxEffortCalibration.Day(hr, 50.0, 190.0, target(hr, 30.0, 2.0))
        }
        val r = WxEffortCalibration.fit(days)!!
        // No sample lies between 20 and 32 %HRR, so floors 20–30 score these days identically.
        assertTrue(r.floorHrr <= 30.0)
        assertEquals(2.0, r.loadScale, 1e-9)
        assertEquals(0.0, r.mae, 1e-9)
        assertTrue(r.improves)
        assertTrue(r.biasPublished < 0)
    }

    @Test
    fun tooFewDaysGivesNoResult() {
        val days = List(3) { WxEffortCalibration.Day(day(60, 10), 50.0, 190.0, 8.0) }
        assertNull(WxEffortCalibration.fit(days))
    }
}
