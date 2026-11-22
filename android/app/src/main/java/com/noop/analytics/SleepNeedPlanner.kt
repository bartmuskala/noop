package com.noop.analytics

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Tonight's sleep target, built from the day instead of one fixed number (fork).
 *
 *   need     = baseline + debt share + effort extra + illness extra − naps
 *   in bed   = need ÷ personal efficiency
 *   bedtime  = wake − in bed − sleep latency, never more than [MAX_ADVANCE_MIN] before the usual bedtime
 *
 * Each term maps to an existing NOOP quantity and to the literature it leans on:
 *  - baseline: [RestScorer.personalizedNeedHours], the upper quartile of recent nights with an age floor
 *    (AASM/SRS adult consensus, Watson 2015);
 *  - debt: a third of the rolling [SleepDebt] balance, at most [DEBT_CAP_MIN] (debt accumulates and is
 *    not repaid by one long night: Van Dongen 2003, Belenky 2003, Banks 2010);
 *  - effort: zero below [EFFORT_FLOOR_21], then [effortSlope] minutes per Effort unit up to
 *    [EFFORT_FULL_21] (exercise reliably adds slow-wave sleep but only a little total sleep,
 *    Kredlow 2015, Youngstedt 1997; athletes are advised 8–10 h, Walsh 2021). The default slope
 *    (40 min across 10→18) is an assumption; [fitEffortSlope] replaces it with the wearer's own
 *    once there are enough free nights;
 *  - illness: [ILLNESS_EXTRA_MIN] when NOOP's illness watch is raised (Besedovsky 2019);
 *  - stress adds NO time: it lengthens sleep onset rather than raising need (Kim & Dimsdale 2007), so a
 *    high-stress day only moves the wind-down earlier.
 *
 * Bedtimes earlier than the usual one by more than [MAX_ADVANCE_MIN] fall in the evening "forbidden
 * zone" for sleep onset (Lavie 1986); the cut part is carried to the next night instead.
 *
 * Pure: no clock, no Android, so every term and cap is unit-tested.
 */
object SleepNeedPlanner {
    const val DEBT_SHARE = 1.0 / 3.0
    const val DEBT_CAP_MIN = 60.0
    const val EFFORT_FLOOR_21 = 10.0
    const val EFFORT_FULL_21 = 18.0
    /** Default minutes of extra need per Effort unit (0–21) above the floor: 40 min over 10→18. */
    const val DEFAULT_EFFORT_SLOPE = 40.0 / (EFFORT_FULL_21 - EFFORT_FLOOR_21)
    const val MAX_EFFORT_SLOPE = 8.0
    const val ILLNESS_EXTRA_MIN = 30.0
    const val LATENCY_MIN = 15
    const val MAX_ADVANCE_MIN = 60
    const val EFFICIENCY_MIN = 0.80
    const val EFFICIENCY_MAX = 0.95
    const val DEFAULT_EFFICIENCY = 0.90
    const val HIGH_STRESS = 2.0
    const val WIND_DOWN_MIN = 60
    const val STRESS_WIND_DOWN_EXTRA_MIN = 30
    const val MIN_FIT_NIGHTS = 10
    const val MIN_FIT_R = 0.3

    data class Input(
        /** Asleep minutes of recent nights (oldest first), for the baseline. */
        val nightlyAsleepMin: List<Double>,
        /** Current rolling sleep-debt balance in minutes (>= 0), or null when unknown. */
        val debtMin: Double?,
        /** The day's Effort on the 0–21 axis, or null when not scored. */
        val effort21: Double?,
        /** Minutes slept in naps on the day. */
        val napMin: Double = 0.0,
        /** Whether the illness watch is raised for the day. */
        val illness: Boolean = false,
        /** Sleep efficiency of recent nights, as percentages (0–100). */
        val efficienciesPct: List<Double> = emptyList(),
        /** Wake time tomorrow, minute of day. */
        val wakeMinute: Int,
        /** The usual bedtime, minute of day, or null when unknown. */
        val habitualBedMinute: Int? = null,
        /** The day's average stress level (0–3), or null. */
        val stressLevel: Double? = null,
        val effortSlope: Double = DEFAULT_EFFORT_SLOPE,
        val age: Int? = null,
    )

    data class Plan(
        val baselineMin: Double,
        val debtAddMin: Double,
        val effortAddMin: Double,
        val illnessAddMin: Double,
        val napSubMin: Double,
        /** Asleep time tonight should deliver. */
        val needMin: Double,
        val efficiency: Double,
        /** Time in bed that delivers [needMin] at [efficiency]. */
        val inBedMin: Double,
        /** The bedtime the need asks for, before the advance cap. */
        val idealBedMinute: Int,
        /** The bedtime shown, after the advance cap. */
        val bedMinute: Int,
        /** Minutes the cap removed, to be made up on following nights. */
        val carriedMin: Int,
        /** When to start winding down. */
        val windDownMinute: Int,
        val highStress: Boolean,
    )

    fun plan(i: Input): Plan {
        val baseline = RestScorer.personalizedNeedHours(i.nightlyAsleepMin.map { it / 60.0 }, i.age) * 60.0
        val debt = min((i.debtMin ?: 0.0).coerceAtLeast(0.0) * DEBT_SHARE, DEBT_CAP_MIN)
        val slope = i.effortSlope.coerceIn(0.0, MAX_EFFORT_SLOPE)
        val effort = i.effort21?.let { e ->
            (e.coerceIn(EFFORT_FLOOR_21, EFFORT_FULL_21) - EFFORT_FLOOR_21) * slope
        } ?: 0.0
        val illness = if (i.illness) ILLNESS_EXTRA_MIN else 0.0
        val nap = i.napMin.coerceAtLeast(0.0)
        // Naps repay need, but never below the baseline's floor of zero extra.
        val need = max(baseline + debt + effort + illness - nap, baseline * 0.75)
        val eff = median(i.efficienciesPct.filter { it > 0.0 }.map { if (it <= 1.0) it else it / 100.0 })
            ?.coerceIn(EFFICIENCY_MIN, EFFICIENCY_MAX) ?: DEFAULT_EFFICIENCY
        val inBed = need / eff
        val ideal = wrapDay(i.wakeMinute - inBed.roundToInt() - LATENCY_MIN)
        // Compare on an evening-continuous scale (noon → noon) so 23:30 and 00:30 order correctly.
        val idealE = evening(ideal)
        val earliestE = i.habitualBedMinute?.let { evening(it) - MAX_ADVANCE_MIN }
        val bedE = if (earliestE != null && idealE < earliestE) earliestE else idealE
        val carried = bedE - idealE
        val bed = wrapDay(bedE + 12 * 60)
        val high = (i.stressLevel ?: 0.0) >= HIGH_STRESS
        val windDown = wrapDay(bed - WIND_DOWN_MIN - if (high) STRESS_WIND_DOWN_EXTRA_MIN else 0)
        return Plan(baseline, debt, effort, illness, nap, need, eff, inBed, ideal, bed, carried, windDown, high)
    }

    /**
     * The wearer's own minutes of sleep per Effort unit, from free nights: (previous day's Effort on
     * 0–21, asleep minutes that night). Null unless there are [MIN_FIT_NIGHTS] nights, the relation is
     * positive and its correlation reaches [MIN_FIT_R]; the slope is capped at [MAX_EFFORT_SLOPE].
     */
    fun fitEffortSlope(nights: List<Pair<Double, Double>>): Double? {
        if (nights.size < MIN_FIT_NIGHTS) return null
        val mx = nights.map { it.first }.average()
        val my = nights.map { it.second }.average()
        var sxy = 0.0; var sxx = 0.0; var syy = 0.0
        for ((x, y) in nights) { sxy += (x - mx) * (y - my); sxx += (x - mx) * (x - mx); syy += (y - my) * (y - my) }
        if (sxx <= 0.0 || syy <= 0.0) return null
        val slope = sxy / sxx
        val r = sxy / sqrt(sxx * syy)
        if (slope <= 0.0 || r < MIN_FIT_R) return null
        return min(slope, MAX_EFFORT_SLOPE)
    }

    /** Minute of day → minutes on a noon-to-noon scale (12:00 = 0, 00:00 = 720). */
    fun evening(minuteOfDay: Int): Int = wrapDay(minuteOfDay - 12 * 60)

    private fun wrapDay(m: Int): Int = ((m % 1440) + 1440) % 1440

    private fun median(xs: List<Double>): Double? {
        if (xs.isEmpty()) return null
        val s = xs.sorted()
        return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
    }
}
