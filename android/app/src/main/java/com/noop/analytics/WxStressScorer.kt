package com.noop.analytics

import com.noop.data.GravitySample
import com.noop.data.HrSample
import kotlin.math.floor

/**
 * The fork's hourly stress read (fork). Every hour with any heart rate gets a level, day and night;
 * hours whose reading is less trustworthy are flagged instead of dropped, so the screens can draw them
 * dashed.
 *
 * Level (0–3) is the hour's mean HR above a calm reference, on a straight scale:
 *   level = (meanHr − reference) / [BPM_PER_LEVEL], clamped to 0–3
 * so the calm reference reads 0, about 12 bpm above it is the low/medium border (1.0) and 25 bpm above
 * it the medium/high border (2.0). An ordinary seated hour, a few beats above the calm floor, stays
 * low; walking around reads medium; only a clearly raised hour reads high. (The first version put the
 * calm reference at 0.5 and "high" at +15 bpm, NOOP's validated margin, and on real days that painted
 * most of the waking day red: the calm reference is a floor, so ordinary hours sit well above it.)
 *
 * The reference is the personal daytime calm floor when NOOP has folded one (the setting
 * "personal baseline"), else the day's own calm floor: the 10th percentile of its hourly means.
 *
 * Flags (the hour still scores): fewer than [DaytimeStress.minHourHrSamples] HR samples, or at least
 * [DaytimeStress.activityMaskFraction] of the hour moving above the walk floor, where raised HR is
 * likely exertion rather than stress.
 */
object WxStressScorer {
    const val BPM_PER_LEVEL = 12.5
    const val REFERENCE_PERCENTILE = 0.10

    data class Hour(
        val startTs: Long,
        val level: Double,
        val meanHr: Double,
        val samples: Int,
        val moving: Boolean,
    ) {
        val lowConfidence: Boolean get() = moving || samples < DaytimeStress.minHourHrSamples
    }

    data class Day(val hours: List<Hour>, val reference: Double?, val personal: Boolean) {
        val mean: Double? get() = hours.takeIf { it.isNotEmpty() }?.map { it.level }?.average()
        val latest: Hour? get() = hours.lastOrNull()
    }

    fun level(meanHr: Double, reference: Double): Double =
        ((meanHr - reference) / BPM_PER_LEVEL).coerceIn(0.0, 3.0)

    /**
     * Score [hr] by local hour. [personalReference] is the folded daytime calm floor, or null to use the
     * day's own. [tzOffsetSeconds] places hours on the local clock.
     */
    fun score(
        hr: List<HrSample>,
        gravity: List<GravitySample>,
        tzOffsetSeconds: Long,
        personalReference: Double?,
    ): Day {
        if (hr.isEmpty()) return Day(emptyList(), personalReference, personalReference != null)
        fun bucket(ts: Long) = Math.floorDiv(ts + tzOffsetSeconds, 3600L) * 3600L - tzOffsetSeconds
        val byHour = hr.groupBy { bucket(it.ts) }.toSortedMap()
        val moving = HashMap<Long, Boolean>()
        if (gravity.isNotEmpty()) {
            val act = WorkoutDetector.activitySeries(gravity).groupBy { bucket(it.ts) }
            for ((b, pts) in act) {
                val frac = pts.count { it.intensity > WorkoutDetector.motionThreshold }.toDouble() / pts.size
                moving[b] = frac >= DaytimeStress.activityMaskFraction
            }
        }
        val means = byHour.mapValues { (_, xs) -> xs.map { it.bpm.toDouble() }.average() }
        val reference = personalReference ?: run {
            // The day's own calm floor over its waking hours (all hours when it has none yet).
            val waking = means.filterKeys { b ->
                val h = (Math.floorDiv(b + tzOffsetSeconds, 3600L) % 24).toInt()
                DaytimeStress.isWakingHourOfDay(h)
            }.values.ifEmpty { means.values }.sorted()
            percentile(waking, REFERENCE_PERCENTILE)
        }
        val hours = byHour.map { (b, xs) ->
            val m = means.getValue(b)
            Hour(b, level(m, reference), m, xs.size, moving[b] == true)
        }
        return Day(hours, reference, personalReference != null)
    }

    private fun percentile(sorted: List<Double>, q: Double): Double {
        if (sorted.size == 1) return sorted[0]
        val pos = q * (sorted.size - 1)
        val lo = floor(pos).toInt(); val hi = minOf(lo + 1, sorted.size - 1)
        return sorted[lo] + (sorted[hi] - sorted[lo]) * (pos - lo)
    }
}
