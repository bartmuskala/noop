package com.noop.analytics

import com.noop.data.HrSample
import kotlin.math.abs

/**
 * Fork: fit the Effort curve to the wearer's own WHOOP history.
 *
 * The fitted curve was tuned on published workout examples and counts nothing below 50 % of heart-rate
 * reserve, so a day of ordinary life without a workout scores very low where WHOOP gives it several
 * points. Where a day has both WHOOP's own Effort (imported) and NOOP's strap heart rate, this searches
 * a small grid of two settings, the floor below which a minute counts nothing and a scale on the day's
 * load, for the pair whose scores sit closest to WHOOP's (mean absolute error on WHOOP's 0–21 axis).
 * Nothing is invented: with too few such days, or no improvement over the published curve, the result
 * says so and the published curve stays.
 */
object WxEffortCalibration {
    const val MIN_DAYS = 7
    val FLOORS = listOf(20.0, 25.0, 30.0, 35.0, 40.0, 45.0, 50.0)
    val SCALES = listOf(0.5, 0.75, 1.0, 1.25, 1.5, 2.0, 2.5, 3.0, 4.0)

    /** One day with both scores' inputs: the strap HR, the resting HR and HRmax used, WHOOP's 0–21 Effort. */
    data class Day(val hr: List<HrSample>, val restingHR: Double, val maxHR: Double, val whoop21: Double)

    data class Result(
        val days: Int,
        val floorHrr: Double,
        val loadScale: Double,
        /** Mean absolute error on the 0–21 axis, with the fit and with the published curve. */
        val mae: Double,
        val maePublished: Double,
        /** Mean signed error (NOOP − WHOOP) before the fit: negative means NOOP scored lower. */
        val biasPublished: Double,
    ) {
        val improves: Boolean get() = mae < maePublished - 0.1
    }

    /** Null when fewer than [MIN_DAYS] usable days. */
    fun fit(days: List<Day>): Result? {
        // Per day: each sample's %HRR and duration, computed once.
        val prepared = days.mapNotNull { d ->
            if (d.hr.size < StrainScorer.minReadings || d.maxHR <= d.restingHR) return@mapNotNull null
            val reserve = d.maxHR - d.restingHR
            val pct = d.hr.map { StrainScorer.pctHRR(it.bpm.toDouble(), d.restingHR, reserve) }
            Triple(pct, StrainScorer.sampleDurationsMinutes(d.hr), d.whoop21)
        }
        if (prepared.size < MIN_DAYS) return null
        fun load(p: Pair<List<Double>, List<Double>>, floor: Double): Double {
            var acc = 0.0
            for (i in p.first.indices) acc += StrainScorer.fittedWeight(p.first[i], floor) * p.second[i]
            return acc
        }
        fun errors(floor: Double, scale: Double): List<Double> = prepared.map { (pct, dur, w) ->
            StrainScorer.fittedCurve21(load(pct to dur, floor) * scale) - w
        }
        val published = errors(StrainScorer.fittedFloorHrr, 1.0)
        var best = Triple(StrainScorer.fittedFloorHrr, 1.0, published.map { abs(it) }.average())
        for (f in FLOORS) {
            // Load per day at this floor, reused across the scales.
            val loads = prepared.map { (pct, dur, _) -> load(pct to dur, f) }
            for (sc in SCALES) {
                val mae = loads.indices.map { abs(StrainScorer.fittedCurve21(loads[it] * sc) - prepared[it].third) }.average()
                if (mae < best.third) best = Triple(f, sc, mae)
            }
        }
        return Result(
            days = prepared.size,
            floorHrr = best.first,
            loadScale = best.second,
            mae = best.third,
            maePublished = published.map { abs(it) }.average(),
            biasPublished = published.average(),
        )
    }
}
