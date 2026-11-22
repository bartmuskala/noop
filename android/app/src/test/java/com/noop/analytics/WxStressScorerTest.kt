package com.noop.analytics

import com.noop.data.HrSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WxStressScorerTest {
    private fun hour(start: Long, bpm: Int, n: Int = 3600) =
        (0 until n).map { HrSample("d", start + it * (3600L / n), bpm) }

    @Test
    fun calmReferenceReadsLowAndMarginReadsHigh() {
        assertEquals(0.0, WxStressScorer.level(60.0, 60.0), 1e-9)
        assertEquals(0.4, WxStressScorer.level(65.0, 60.0), 1e-9)
        assertEquals(1.0, WxStressScorer.level(72.5, 60.0), 1e-9)
        assertEquals(2.0, WxStressScorer.level(85.0, 60.0), 1e-9)
        assertEquals(0.0, WxStressScorer.level(50.0, 60.0), 1e-9)
        assertEquals(3.0, WxStressScorer.level(120.0, 60.0), 1e-9)
    }

    @Test
    fun everyHourScoresAndSparseHoursAreFlagged() {
        val day = WxStressScorer.score(hour(0, 55) + hour(3600, 70, n = 40), emptyList(), 0L, personalReference = 60.0)
        assertEquals(2, day.hours.size)
        assertFalse(day.hours[0].lowConfidence)
        assertTrue(day.hours[1].lowConfidence)
        assertEquals(0.8, day.hours[1].level, 1e-9)
        assertTrue(day.personal)
    }

    @Test
    fun withoutAPersonalReferenceTheDaysOwnFloorIsUsed() {
        // Waking hours 08:00..11:00 at 60, 62, 64, 80 bpm: the 10th percentile is about 60.6.
        val hr = listOf(60, 62, 64, 80).flatMapIndexed { i, b -> hour((8 + i) * 3600L, b) }
        val day = WxStressScorer.score(hr, emptyList(), 0L, personalReference = null)
        assertEquals(60.6, day.reference!!, 0.01)
        assertTrue(day.hours.first().level < 1.0)
        assertTrue(day.hours.last().level in 1.0..2.0)
    }
}
