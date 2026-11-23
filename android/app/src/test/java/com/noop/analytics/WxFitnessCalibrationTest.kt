package com.noop.analytics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class WxFitnessCalibrationTest {
    @Test
    fun parsesTheWhoopWeekLines() {
        val refs = WxFitnessCalibration.parseRefs("""
            05/7 - 11/7: age was 38,5, vo2max was 53 ml/kg/min
            28/6 - 4/7: 38,5 en 53
            19/4 - 25/4: 39,3 en 52
            nonsense
        """.trimIndent(), LocalDate.parse("2026-11-23"))
        assertEquals(listOf("2026-07-11", "2026-07-04", "2026-04-25"), refs.map { it.saturday })
        assertEquals(38.5, refs[1].age!!, 1e-9)
        assertEquals(53.0, refs[1].vo2!!, 1e-9)
    }

    @Test
    fun offsetsAreTheMedianDifference() {
        val refs = listOf(WxFitnessCalibration.Ref("2026-07-11", 38.5, 53.0), WxFitnessCalibration.Ref("2026-07-04", 38.5, 53.0),
            WxFitnessCalibration.Ref("2026-06-27", 38.6, 52.0))
        val (a, v, n) = WxFitnessCalibration.offsets(refs,
            mapOf("2026-07-11" to 36.5, "2026-07-04" to 37.5, "2026-06-27" to 36.6), mapOf("2026-07-11" to 50.0, "2026-07-04" to 51.0))
        assertEquals(2.0, a!!, 1e-9)
        assertEquals(2.5, v!!, 1e-9)
        assertEquals(3, n)
    }

    @Test
    fun paIndexFromSessionsAndCoverage() {
        // Four rides a week for four weeks, 90 minutes, half of them hard: 2.5 (3-4 days) x 3 (half or more hard) x 1.0.
        val sessions = (0 until 28).filter { it % 7 in setOf(0, 2, 4, 5) }.map { d ->
            WxFitnessCalibration.Session(LocalDate.parse("2026-07-11").minusDays(d.toLong()).toString(), 90.0, d % 2 == 0)
        }
        assertEquals(7.5, WxFitnessCalibration.paIndex(sessions, "2026-07-11", "2026-01-01")!!, 1e-9)
        assertEquals(0.0, WxFitnessCalibration.paIndex(emptyList(), "2026-07-11", "2026-01-01")!!, 1e-9)
        assertNull(WxFitnessCalibration.paIndex(sessions, "2026-07-11", "2026-07-01"))
    }
}
