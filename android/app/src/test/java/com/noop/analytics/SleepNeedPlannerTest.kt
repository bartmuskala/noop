package com.noop.analytics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SleepNeedPlannerTest {
    private val eightHours = List(14) { 480.0 }

    private fun base(
        effort: Double? = 5.0, debt: Double? = 0.0, nap: Double = 0.0, illness: Boolean = false,
        eff: List<Double> = listOf(90.0, 90.0, 90.0), bed: Int? = null, stress: Double? = null,
    ) = SleepNeedPlanner.Input(
        nightlyAsleepMin = eightHours, debtMin = debt, effort21 = effort, napMin = nap, illness = illness,
        efficienciesPct = eff, wakeMinute = 7 * 60, habitualBedMinute = bed, stressLevel = stress,
    )

    @Test
    fun quietDayIsBaselineOverEfficiency() {
        val p = SleepNeedPlanner.plan(base())
        assertEquals(480.0, p.baselineMin, 1e-6)
        assertEquals(0.0, p.effortAddMin, 1e-6)
        assertEquals(480.0, p.needMin, 1e-6)
        assertEquals(0.90, p.efficiency, 1e-9)
        assertEquals(533.33, p.inBedMin, 0.01)
        // 07:00 − 533 − 15 = 21:52.
        assertEquals(21 * 60 + 52, p.bedMinute)
        assertEquals(0, p.carriedMin)
        assertEquals(20 * 60 + 52, p.windDownMinute)
        assertFalse(p.highStress)
    }

    @Test
    fun effortAddsAboveTheFloorAndStopsAtTheTop() {
        assertEquals(20.0, SleepNeedPlanner.plan(base(effort = 14.0)).effortAddMin, 1e-6)
        assertEquals(40.0, SleepNeedPlanner.plan(base(effort = 25.0)).effortAddMin, 1e-6)
        assertEquals(0.0, SleepNeedPlanner.plan(base(effort = null)).effortAddMin, 1e-6)
    }

    @Test
    fun debtRepaysAThirdUpToTheCap() {
        assertEquals(30.0, SleepNeedPlanner.plan(base(debt = 90.0)).debtAddMin, 1e-6)
        assertEquals(60.0, SleepNeedPlanner.plan(base(debt = 240.0)).debtAddMin, 1e-6)
    }

    @Test
    fun illnessAddsAndNapsSubtract() {
        val p = SleepNeedPlanner.plan(base(illness = true, nap = 45.0))
        assertEquals(480.0 + 30.0 - 45.0, p.needMin, 1e-6)
    }

    @Test
    fun bedtimeIsNotPulledMoreThanAnHourBeforeTheUsualOne() {
        // Usual bedtime 23:30: the earliest allowed is 22:30, the need asked for 21:52.
        val p = SleepNeedPlanner.plan(base(bed = 23 * 60 + 30))
        assertEquals(21 * 60 + 52, p.idealBedMinute)
        assertEquals(22 * 60 + 30, p.bedMinute)
        assertEquals(38, p.carriedMin)
    }

    @Test
    fun aUsualBedtimeAfterMidnightStillOrdersCorrectly() {
        // Usual 00:30 → earliest 23:30, later than the ideal 21:52, so the cap applies.
        val p = SleepNeedPlanner.plan(base(bed = 30))
        assertEquals(23 * 60 + 30, p.bedMinute)
    }

    @Test
    fun highStressMovesOnlyTheWindDown() {
        val calm = SleepNeedPlanner.plan(base())
        val tense = SleepNeedPlanner.plan(base(stress = 2.5))
        assertTrue(tense.highStress)
        assertEquals(calm.needMin, tense.needMin, 1e-9)
        assertEquals(calm.bedMinute, tense.bedMinute)
        assertEquals(calm.bedMinute - 90, tense.windDownMinute)
    }

    @Test
    fun efficiencyIsClampedAndAcceptsFractions() {
        assertEquals(0.95, SleepNeedPlanner.plan(base(eff = listOf(99.0))).efficiency, 1e-9)
        assertEquals(0.85, SleepNeedPlanner.plan(base(eff = listOf(0.85))).efficiency, 1e-9)
        assertEquals(SleepNeedPlanner.DEFAULT_EFFICIENCY, SleepNeedPlanner.plan(base(eff = emptyList())).efficiency, 1e-9)
    }

    @Test
    fun personalSlopeNeedsEnoughNightsAndAPositiveRelation() {
        val nights = (0 until 12).map { i -> (6.0 + i) to (400.0 + 3.0 * (6.0 + i) + if (i % 2 == 0) 4.0 else -4.0) }
        assertEquals(3.0, SleepNeedPlanner.fitEffortSlope(nights)!!, 0.5)
        assertNull(SleepNeedPlanner.fitEffortSlope(nights.take(5)))
        assertNull(SleepNeedPlanner.fitEffortSlope(nights.map { it.first to (900.0 - it.second) }))
    }
}
